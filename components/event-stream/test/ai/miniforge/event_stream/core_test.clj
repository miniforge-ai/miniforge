(ns ai.miniforge.event-stream.core-test
  "Direct unit tests for event-stream core — chain events, error handling,
   OCI events, control-plane events, and sink integration."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Helpers

(defn no-op-stream
  "Create an event stream with no sinks for isolated testing."
  []
  (core/create-event-stream {:sinks []}))

(defn collect-stream
  "Create an event stream with a collecting sink for verifying sink calls."
  []
  (let [collected (atom [])
        sink (fn [event] (swap! collected conj event))
        stream (core/create-event-stream {:sinks [sink]})]
    {:stream stream :collected collected}))

;------------------------------------------------------------------------------ Layer 0
;; create-envelope

(deftest create-envelope-test
  (testing "produces well-formed event map"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          env (core/create-envelope stream :test/event wf-id "hello")]
      (is (= :test/event (:event/type env)))
      (is (uuid? (:event/id env)))
      (is (inst? (:event/timestamp env)))
      (is (= core/event-version (:event/version env)))
      (is (= 0 (:event/sequence-number env)))
      (is (= wf-id (:workflow/id env)))
      (is (= "hello" (:message env)))))

  (testing "sequence numbers increment for same workflow"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          e1 (core/create-envelope stream :a wf-id "first")
          e2 (core/create-envelope stream :b wf-id "second")
          e3 (core/create-envelope stream :c wf-id "third")]
      (is (= 0 (:event/sequence-number e1)))
      (is (= 1 (:event/sequence-number e2)))
      (is (= 2 (:event/sequence-number e3)))))

  (testing "different workflows have independent sequences"
    (let [stream (no-op-stream)
          wf-1 (random-uuid)
          wf-2 (random-uuid)
          e1a (core/create-envelope stream :a wf-1 "1a")
          e2a (core/create-envelope stream :a wf-2 "2a")
          e1b (core/create-envelope stream :b wf-1 "1b")]
      (is (= 0 (:event/sequence-number e1a)))
      (is (= 0 (:event/sequence-number e2a)))
      (is (= 1 (:event/sequence-number e1b)))))

  (testing "nil workflow-id still produces valid envelope"
    (let [stream (no-op-stream)
          env (core/create-envelope stream :test/nil-wf nil "no workflow")]
      (is (nil? (:workflow/id env)))
      (is (= 0 (:event/sequence-number env))))))

;------------------------------------------------------------------------------ Layer 1
;; publish! sink integration

(deftest publish-calls-sinks-test
  (testing "publish! calls all configured sinks"
    (let [{:keys [stream collected]} (collect-stream)
          event (core/create-envelope stream :test/sink (random-uuid) "sink test")]
      (core/publish! stream event)
      (is (= 1 (count @collected)))
      (is (= :test/sink (:event/type (first @collected)))))))

(deftest publish-sink-error-handling-test
  (testing "publish! continues when a sink throws"
    (let [good-received (atom [])
          bad-sink (fn [_] (throw (Exception. "sink boom")))
          good-sink (fn [e] (swap! good-received conj e))
          stream (core/create-event-stream {:sinks [bad-sink good-sink]})
          event (core/create-envelope stream :test/err (random-uuid) "error test")]
      (core/publish! stream event)
      ;; Event still stored in memory and good sink received it
      (is (= 1 (count (:events @stream))))
      (is (= 1 (count @good-received))))))

(deftest publish-subscriber-error-handling-test
  (testing "publish! continues when a subscriber callback throws"
    (let [stream (no-op-stream)
          received (atom [])]
      (core/subscribe! stream :bad (fn [_] (throw (Exception. "callback boom"))))
      (core/subscribe! stream :good (fn [e] (swap! received conj e)))
      (let [event (core/create-envelope stream :test/cb (random-uuid) "cb test")]
        (core/publish! stream event)
        ;; Good subscriber still received the event
        (is (= 1 (count @received)))))))

;------------------------------------------------------------------------------ Layer 1
;; subscribe! and unsubscribe!

(deftest subscribe-with-filter-test
  (testing "subscriber with filter only receives matching events"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          received (atom [])]
      (core/subscribe! stream :filtered
                       (fn [e] (swap! received conj e))
                       (fn [e] (= :special (:event/type e))))
      (core/publish! stream (core/create-envelope stream :normal wf-id "skip"))
      (core/publish! stream (core/create-envelope stream :special wf-id "keep"))
      (is (= 1 (count @received)))
      (is (= :special (:event/type (first @received)))))))

(deftest unsubscribe-test
  (testing "unsubscribed callback no longer receives events"
    (let [stream (no-op-stream)
          received (atom [])]
      (core/subscribe! stream :temp (fn [e] (swap! received conj e)))
      (core/publish! stream (core/create-envelope stream :a (random-uuid) "before"))
      (core/unsubscribe! stream :temp)
      (core/publish! stream (core/create-envelope stream :b (random-uuid) "after"))
      (is (= 1 (count @received))))))

;------------------------------------------------------------------------------ Layer 2
;; Query API

(deftest get-events-combined-filters-test
  (testing "workflow-id + event-type filters compose"
    (let [stream (no-op-stream)
          wf-1 (random-uuid)
          wf-2 (random-uuid)]
      (core/publish! stream (core/workflow-started stream wf-1))
      (core/publish! stream (core/phase-started stream wf-1 :plan))
      (core/publish! stream (core/workflow-started stream wf-2))
      (let [results (core/get-events stream {:workflow-id wf-1
                                              :event-type :workflow/started})]
        (is (= 1 (count results)))
        (is (= wf-1 (:workflow/id (first results))))))))

(deftest get-latest-status-nil-agent-test
  (testing "get-latest-status with nil agent-id returns latest status across agents"
    (let [stream (no-op-stream)
          wf-id (random-uuid)]
      (core/publish! stream (core/agent-status stream wf-id :a :thinking "a thinking"))
      (core/publish! stream (core/agent-status stream wf-id :b :generating "b generating"))
      (let [latest (core/get-latest-status stream wf-id)]
        (is (= :generating (:status/type latest)))))))

;------------------------------------------------------------------------------ Layer 3
;; Chain event constructors

(deftest chain-envelope-test
  (testing "chain-envelope uses nil workflow-id and event-type as message"
    (let [stream (no-op-stream)
          env (core/chain-envelope stream :chain/started)]
      (is (= :chain/started (:event/type env)))
      (is (nil? (:workflow/id env)))
      (is (= "started" (:message env))))))

(deftest chain-started-test
  (testing "chain-started includes chain-id and step-count"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          event (core/chain-started stream chain-id 5)]
      (is (= :chain/started (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= 5 (:chain/step-count event))))))

(deftest chain-step-started-test
  (testing "chain-step-started includes step metadata"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          step-id :plan
          wf-id (random-uuid)
          event (core/chain-step-started stream chain-id step-id 0 wf-id)]
      (is (= :chain/step-started (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= step-id (:step/id event)))
      (is (= 0 (:step/index event)))
      (is (= wf-id (:step/workflow-id event))))))

(deftest chain-step-completed-test
  (testing "chain-step-completed captures step index"
    (let [stream (no-op-stream)
          event (core/chain-step-completed stream (random-uuid) :implement 1)]
      (is (= :chain/step-completed (:event/type event)))
      (is (= 1 (:step/index event))))))

(deftest chain-step-failed-test
  (testing "chain-step-failed captures error"
    (let [stream (no-op-stream)
          error {:message "compilation failed"}
          event (core/chain-step-failed stream (random-uuid) :implement 1 error)]
      (is (= :chain/step-failed (:event/type event)))
      (is (= error (:chain/error event))))))

(deftest chain-completed-test
  (testing "chain-completed captures duration and step count"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          event (core/chain-completed stream chain-id 12000 3)]
      (is (= :chain/completed (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= 12000 (:chain/duration-ms event)))
      (is (= 3 (:chain/step-count event))))))

(deftest chain-failed-test
  (testing "chain-failed captures failed step and error"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          event (core/chain-failed stream chain-id :review {:message "timeout"})]
      (is (= :chain/failed (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= :review (:chain/failed-step event)))
      (is (= {:message "timeout"} (:chain/error event))))))

;------------------------------------------------------------------------------ Layer 4
;; OCI container events

(deftest container-started-test
  (testing "container-started creates event with container-id"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          event (core/container-started stream wf-id "ctr-123")]
      (is (= :oci/container-started (:event/type event)))
      (is (= "ctr-123" (:oci/container-id event)))))

  (testing "container-started includes opts"
    (let [stream (no-op-stream)
          event (core/container-started stream (random-uuid) "ctr-456"
                                        {:image-digest "sha256:abc"
                                         :trust-level :verified})]
      (is (= "sha256:abc" (:oci/image-digest event)))
      (is (= :verified (:oci/trust-level event))))))

(deftest container-completed-test
  (testing "container-completed captures exit code"
    (let [stream (no-op-stream)
          event (core/container-completed stream (random-uuid) "ctr-789" 0 5000)]
      (is (= :oci/container-completed (:event/type event)))
      (is (= 0 (:oci/exit-code event)))
      (is (= 5000 (:oci/duration-ms event)))))

  (testing "container-completed with non-zero exit code"
    (let [stream (no-op-stream)
          event (core/container-completed stream (random-uuid) "ctr-fail" 1)]
      (is (= 1 (:oci/exit-code event)))
      (is (nil? (:oci/duration-ms event))))))

;------------------------------------------------------------------------------ Layer 4
;; Tool supervision events

(deftest tool-use-evaluated-test
  (testing "basic tool evaluation event"
    (let [stream (no-op-stream)
          event (core/tool-use-evaluated stream (random-uuid) "file-write" :allow)]
      (is (= :supervision/tool-use-evaluated (:event/type event)))
      (is (= "file-write" (:tool/name event)))
      (is (= :allow (:supervision/decision event)))))

  (testing "includes optional fields"
    (let [stream (no-op-stream)
          event (core/tool-use-evaluated stream (random-uuid) "shell-exec" :deny
                                         {:reasoning "Dangerous command"
                                          :meta-eval? true
                                          :confidence 0.95
                                          :phase :implement})]
      (is (= "Dangerous command" (:supervision/reasoning event)))
      (is (true? (:supervision/meta-eval? event)))
      (is (= 0.95 (:supervision/confidence event)))
      (is (= :implement (:workflow/phase event))))))

;------------------------------------------------------------------------------ Layer 5
;; Control plane events

(deftest cp-agent-registered-test
  (testing "creates registration event"
    (let [stream (no-op-stream)
          event (core/cp-agent-registered stream (random-uuid) "agent-1" :anthropic)]
      (is (= :control-plane/agent-registered (:event/type event)))
      (is (= "agent-1" (:cp/agent-id event)))
      (is (= :anthropic (:cp/vendor event)))))

  (testing "includes optional name"
    (let [stream (no-op-stream)
          event (core/cp-agent-registered stream (random-uuid) "agent-1" :anthropic
                                          {:name "Implementer"})]
      (is (= "Implementer" (:cp/agent-name event))))))

(deftest cp-agent-heartbeat-test
  (testing "creates heartbeat event"
    (let [stream (no-op-stream)
          event (core/cp-agent-heartbeat stream (random-uuid) "agent-1" :active)]
      (is (= :control-plane/agent-heartbeat (:event/type event)))
      (is (= "agent-1" (:cp/agent-id event)))
      (is (= :active (:cp/status event))))))

(deftest cp-agent-state-changed-test
  (testing "creates state-change event with from/to"
    (let [stream (no-op-stream)
          event (core/cp-agent-state-changed stream (random-uuid) "agent-1" :idle :active)]
      (is (= :control-plane/agent-state-changed (:event/type event)))
      (is (= :idle (:cp/from-status event)))
      (is (= :active (:cp/to-status event))))))

(deftest cp-decision-created-test
  (testing "creates decision event"
    (let [stream (no-op-stream)
          decision-id (random-uuid)
          event (core/cp-decision-created stream (random-uuid) "agent-1" decision-id
                                          "Approve budget increase" :high)]
      (is (= :control-plane/decision-created (:event/type event)))
      (is (= decision-id (:cp/decision-id event)))
      (is (= "Approve budget increase" (:cp/summary event)))
      (is (= :high (:cp/priority event))))))

(deftest cp-decision-resolved-test
  (testing "creates resolved event"
    (let [stream (no-op-stream)
          decision-id (random-uuid)
          event (core/cp-decision-resolved stream (random-uuid) decision-id "approved")]
      (is (= :control-plane/decision-resolved (:event/type event)))
      (is (= decision-id (:cp/decision-id event)))
      (is (= "approved" (:cp/resolution event))))))

;------------------------------------------------------------------------------ Layer 3
;; Workflow failed edge cases

(deftest workflow-failed-with-exception-test
  (testing "workflow-failed from Throwable extracts message and type"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          ex (ex-info "LLM timeout" {:code :timeout})
          event (core/workflow-failed stream wf-id ex)]
      (is (= :workflow/failed (:event/type event)))
      (is (string? (:workflow/failure-reason event)))
      (is (map? (:workflow/error-details event))))))

(deftest workflow-failed-with-plain-map-test
  (testing "workflow-failed from plain error map"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          event (core/workflow-failed stream wf-id {:message "API error" :code 500})]
      (is (= :workflow/failed (:event/type event)))
      (is (= "API error" (:workflow/failure-reason event))))))

;------------------------------------------------------------------------------ Layer 3
;; Phase completed redirect

(deftest phase-completed-redirect-test
  (testing "phase-completed with redirect-to field"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          event (core/phase-completed stream wf-id :review
                                       {:outcome :redirect
                                        :redirect-to :implement})]
      (is (= :redirect (:phase/outcome event)))
      (is (= :implement (:phase/redirect-to event))))))

(deftest phase-completed-with-error-test
  (testing "phase-completed captures error details"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          event (core/phase-completed stream wf-id :implement
                                       {:outcome :failure
                                        :error {:message "compile error"
                                                :line 42}})]
      (is (= :failure (:phase/outcome event)))
      (is (= {:message "compile error" :line 42} (:phase/error event))))))