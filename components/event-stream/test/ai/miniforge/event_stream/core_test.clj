;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.event-stream.core-test
  "Direct unit tests for event-stream core — chain events, error handling,
   OCI events, control-plane events, and sink integration."
  (:require
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.phase.interface :as phase]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.compound-events :as compound]
   [ai.miniforge.event-stream.phase-events :as phase-events]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Helpers
(defn ^{:stratum 0} no-op-stream
  "Create an event stream with no sinks for isolated testing."
  []
  (core/create-event-stream {:sinks []}))

(defn ^{:stratum 0} collect-stream
  "Create an event stream with a collecting sink for verifying sink calls."
  []
  (let [collected (atom [])
        sink (fn [event] (swap! collected conj event))
        stream (core/create-event-stream {:sinks [sink]})]
    {:stream stream :collected collected}))

(deftest ^{:stratum 0} create-envelope-propagates-snowflake-anomaly
  (let [generator {:state     (atom {:last-ts -1
                                     :last-seq -1
                                     :worker-id 0})
                   :worker-id 0
                   :lease     {:worker-id 0}
                   :now-fn    (constantly 0)}
        stream (core/create-event-stream {:sinks []
                                          :snowflake-generator generator})
        result (core/create-envelope stream :test/event (random-uuid) "hello")]
    (is (response/anomaly-map? result))
    (is (= :anomalies/incorrect (:anomaly/category result)))
    (is (= :pre-epoch-clock (:reason result)))))

(deftest ^{:stratum 0} create-envelope-propagates-anomaly-generator
  (let [generator-anomaly (response/make-anomaly
                           :anomalies/busy
                           "No worker slots"
                           {:reason :workers-exhausted})
        stream (core/create-event-stream {:sinks []
                                          :snowflake-generator generator-anomaly})
        result (core/create-envelope stream :test/event (random-uuid) "hello")]
    (is (identical? generator-anomaly result))))

(deftest ^{:stratum 0} create-envelope-does-not-consume-sequence-on-id-anomaly
  (let [wf-id (random-uuid)
        generator {:state     (atom {:last-ts -1
                                     :last-seq -1
                                     :worker-id 0})
                   :worker-id 0
                   :lease     {:worker-id 0}
                   :now-fn    (constantly 0)}
        stream (core/create-event-stream {:sinks []
                                          :snowflake-generator generator})
        result (core/create-envelope stream :test/event wf-id "bad")]
    (is (response/anomaly-map? result))
    (swap! stream dissoc :snowflake-generator)
    (is (= 0 (:event/sequence-number
              (core/create-envelope stream :test/event wf-id "good"))))))

(deftest ^{:stratum 0} publish-sink-error-handling-test
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

;; Dependency health event constructors
(defn- ^{:stratum 0} dependency-health-entity
  [overrides]
  (merge {:dependency/id :anthropic
          :dependency/source :external-provider
          :dependency/kind :provider
          :dependency/status :degraded
          :dependency/failure-count 1
          :dependency/window-size 5
          :dependency/incident-counts {:degraded 1}}
         overrides))

;------------------------------------------------------------------------------ Layer 1

;; create-envelope
(deftest ^{:stratum 1} create-envelope-test
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

;; knowledge failure constructors
(deftest ^{:stratum 1} knowledge-promotion-failed-accepts-failure-data-test
  (testing "promotion failure event can be built from error data without ex-info"
    (let [stream (no-op-stream)
          event (core/knowledge-promotion-failed stream
                                                 {:zettel/id "z-1"
                                                  :error "promotion blocked"})]
      (is (= :knowledge/promotion-failed (:event/type event)))
      (is (= "promotion blocked" (:knowledge/error event)))
      (is (= "Knowledge promotion failed: promotion blocked"
             (:message event))))))

(deftest ^{:stratum 1} knowledge-failure-constructors-preserve-throwable-messages-test
  (testing "existing Throwable callers keep the same message behavior"
    (let [stream (no-op-stream)
          err (ex-info "synthesis failed" {:cause :test})
          event (core/knowledge-synthesis-failed stream err)]
      (is (= :knowledge/synthesis-failed (:event/type event)))
      (is (= "synthesis failed" (:knowledge/error event))))))

;; publish! sink integration
(deftest ^{:stratum 1} publish-calls-sinks-test
  (testing "publish! calls all configured sinks"
    (let [{:keys [stream collected]} (collect-stream)
          event (core/create-envelope stream :test/sink (random-uuid) "sink test")]
      (core/publish! stream event)
      (is (= 1 (count @collected)))
      (is (= :test/sink (:event/type (first @collected)))))))

(deftest ^{:stratum 1} publish-returns-anomaly-without-delivery
  (let [{:keys [stream collected]} (collect-stream)
        anomaly (response/make-anomaly
                 :anomalies/incorrect
                 "invalid event"
                 {:reason :pre-epoch-clock})
        result (core/publish! stream anomaly)]
    (is (identical? anomaly result))
    (is (empty? @collected))
    (is (empty? (:events @stream)))))

(deftest ^{:stratum 1} publish-subscriber-error-handling-test
  (testing "publish! continues when a subscriber callback throws"
    (let [stream (no-op-stream)
          received (atom [])]
      (core/subscribe! stream :bad (fn [_] (throw (Exception. "callback boom"))))
      (core/subscribe! stream :good (fn [e] (swap! received conj e)))
      (let [event (core/create-envelope stream :test/cb (random-uuid) "cb test")]
        (core/publish! stream event)
        ;; Good subscriber still received the event
        (is (= 1 (count @received)))))))

;; subscribe! and unsubscribe!
(deftest ^{:stratum 1} subscribe-with-filter-test
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

(deftest ^{:stratum 1} unsubscribe-test
  (testing "unsubscribed callback no longer receives events"
    (let [stream (no-op-stream)
          received (atom [])]
      (core/subscribe! stream :temp (fn [e] (swap! received conj e)))
      (core/publish! stream (core/create-envelope stream :a (random-uuid) "before"))
      (core/unsubscribe! stream :temp)
      (core/publish! stream (core/create-envelope stream :b (random-uuid) "after"))
      (is (= 1 (count @received))))))

;; Query API
(deftest ^{:stratum 1} get-events-combined-filters-test
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

(deftest ^{:stratum 1} get-latest-status-nil-agent-test
  (testing "get-latest-status with nil agent-id returns latest status across agents"
    (let [stream (no-op-stream)
          wf-id (random-uuid)]
      (core/publish! stream (core/agent-status stream wf-id :a :thinking "a thinking"))
      (core/publish! stream (core/agent-status stream wf-id :b :generating "b generating"))
      (let [latest (core/get-latest-status stream wf-id)]
        (is (= :generating (:status/type latest)))))))

;; Chain event constructors
(deftest ^{:stratum 1} workflow-started-routing-trigger-event-id-test
  (testing "2-arg and 3-arg call shapes preserved (backward compat)"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          e2     (core/workflow-started stream wf-id)
          e3     (core/workflow-started stream wf-id {:name "demo"})]
      (is (= :workflow/started (:event/type e2)))
      (is (= :workflow/started (:event/type e3)))
      (is (= {:name "demo"} (:workflow/spec e3)))
      (is (not (contains? e2 :routing/trigger-event-id))
          ":routing/trigger-event-id MUST be absent on the legacy call shapes")
      (is (not (contains? e3 :routing/trigger-event-id)))))

  (testing "4-arg call shape with :routing/trigger-event-id emits the field"
    (let [stream   (no-op-stream)
          wf-id    (random-uuid)
          trigger  (random-uuid)
          event    (core/workflow-started stream wf-id nil
                                          {:routing/trigger-event-id trigger})]
      (is (= :workflow/started (:event/type event)))
      (is (= trigger (:routing/trigger-event-id event))
          "explicit routing-trigger-event-id MUST land on the envelope")))

  (testing "nil :routing/trigger-event-id is treated as absent (no envelope key)"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/workflow-started stream wf-id nil
                                        {:routing/trigger-event-id nil})]
      (is (not (contains? event :routing/trigger-event-id))
          (str "nil opts value must not pollute the envelope — N5-delta-4 §4.3 "
               "leaves the field optional/absent on operator-initiated workflows")))))

(deftest ^{:stratum 1} chain-envelope-test
  (testing "chain-envelope uses nil workflow-id and event-type as message"
    (let [stream (no-op-stream)
          env (core/chain-envelope stream :chain/started)]
      (is (= :chain/started (:event/type env)))
      (is (nil? (:workflow/id env)))
      (is (= "started" (:message env))))))

(deftest ^{:stratum 1} chain-started-test
  (testing "chain-started includes chain-id and step-count"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          event (compound/chain-started stream chain-id 5)]
      (is (= :chain/started (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= 5 (:chain/step-count event))))))

(deftest ^{:stratum 1} chain-step-started-test
  (testing "chain-step-started includes step metadata"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          step-id :plan
          wf-id (random-uuid)
          event (compound/chain-step-started stream chain-id step-id 0 wf-id)]
      (is (= :chain/step-started (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= step-id (:step/id event)))
      (is (= 0 (:step/index event)))
      (is (= wf-id (:step/workflow-id event))))))

(deftest ^{:stratum 1} chain-step-completed-test
  (testing "chain-step-completed captures step index"
    (let [stream (no-op-stream)
          event (compound/chain-step-completed stream (random-uuid) :implement 1)]
      (is (= :chain/step-completed (:event/type event)))
      (is (= 1 (:step/index event))))))

(deftest ^{:stratum 1} chain-step-failed-test
  (testing "chain-step-failed captures error"
    (let [stream (no-op-stream)
          error {:message "compilation failed"}
          event (compound/chain-step-failed stream (random-uuid) :implement 1 error)]
      (is (= :chain/step-failed (:event/type event)))
      (is (= error (:chain/error event))))))

(deftest ^{:stratum 1} chain-completed-test
  (testing "chain-completed captures duration and step count"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          event (compound/chain-completed stream chain-id 12000 3)]
      (is (= :chain/completed (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= 12000 (:chain/duration-ms event)))
      (is (= 3 (:chain/step-count event))))))

(deftest ^{:stratum 1} chain-failed-test
  (testing "chain-failed captures failed step and error"
    (let [stream (no-op-stream)
          chain-id (random-uuid)
          event (compound/chain-failed stream chain-id :review {:message "timeout"})]
      (is (= :chain/failed (:event/type event)))
      (is (= chain-id (:chain/id event)))
      (is (= :review (:chain/failed-step event)))
      (is (= {:message "timeout"} (:chain/error event))))))

;; OCI container events
(deftest ^{:stratum 1} container-started-test
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

(deftest ^{:stratum 1} container-completed-test
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

;; Tool supervision events
(deftest ^{:stratum 1} tool-use-evaluated-test
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

;; Control plane events
(deftest ^{:stratum 1} cp-agent-registered-test
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
      (is (= "Implementer" (:cp/agent-name event)))))

  (testing "includes richer registration metadata when present"
    (let [stream (no-op-stream)
          event (core/cp-agent-registered stream (random-uuid) "agent-1" :anthropic
                                          {:external-id "ext-1"
                                           :capabilities #{:code-generation}
                                           :metadata {:workflow-id "wf-1"}
                                           :tags #{:native}
                                           :heartbeat-interval-ms 15000})]
      (is (= "ext-1" (:cp/external-id event)))
      (is (= [:code-generation] (:cp/capabilities event)))
      (is (= {:workflow-id "wf-1"} (:cp/metadata event)))
      (is (= [:native] (:cp/tags event)))
      (is (= 15000 (:cp/heartbeat-interval-ms event))))))

(deftest ^{:stratum 1} cp-agent-heartbeat-test
  (testing "creates heartbeat event"
    (let [stream (no-op-stream)
          event (core/cp-agent-heartbeat stream (random-uuid) "agent-1" :active)]
      (is (= :control-plane/agent-heartbeat (:event/type event)))
      (is (= "agent-1" (:cp/agent-id event)))
      (is (= :active (:cp/status event)))))

  (testing "includes task and metrics when present"
    (let [stream (no-op-stream)
          event (core/cp-agent-heartbeat stream (random-uuid) "agent-1" :active
                                         {:task "Reviewing PR"
                                          :metrics {:tokens 42}})]
      (is (= "Reviewing PR" (:cp/task event)))
      (is (= {:tokens 42} (:cp/metrics event))))))

(deftest ^{:stratum 1} cp-agent-state-changed-test
  (testing "creates state-change event with from/to"
    (let [stream (no-op-stream)
          event (core/cp-agent-state-changed stream (random-uuid) "agent-1" :idle :active)]
      (is (= :control-plane/agent-state-changed (:event/type event)))
      (is (= :idle (:cp/from-status event)))
      (is (= :active (:cp/to-status event))))))

(deftest ^{:stratum 1} cp-decision-created-test
  (testing "creates decision event"
    (let [stream (no-op-stream)
          decision-id (random-uuid)
          event (core/cp-decision-created stream (random-uuid) "agent-1" decision-id
                                          "Approve budget increase" :high)]
      (is (= :control-plane/decision-created (:event/type event)))
      (is (= decision-id (:cp/decision-id event)))
      (is (= "Approve budget increase" (:cp/summary event)))
      (is (= :high (:cp/priority event)))))

  (testing "includes richer decision fields when present"
    (let [stream (no-op-stream)
          decision-id (random-uuid)
          deadline (java.util.Date.)
          event (core/cp-decision-created stream (random-uuid) "agent-1" decision-id
                                          "Choose release target"
                                          {:priority :medium
                                           :type :choice
                                           :context "Production cutover"
                                           :options ["blue" "green"]
                                           :deadline deadline})]
      (is (= :choice (:cp/type event)))
      (is (= "Production cutover" (:cp/context event)))
      (is (= ["blue" "green"] (:cp/options event)))
      (is (= deadline (:cp/deadline event))))))

(deftest ^{:stratum 1} cp-decision-resolved-test
  (testing "creates resolved event"
    (let [stream (no-op-stream)
          decision-id (random-uuid)
          event (core/cp-decision-resolved stream (random-uuid) decision-id "approved")]
      (is (= :control-plane/decision-resolved (:event/type event)))
      (is (= decision-id (:cp/decision-id event)))
      (is (= "approved" (:cp/resolution event)))))

  (testing "includes optional comment"
    (let [stream (no-op-stream)
          decision-id (random-uuid)
          event (core/cp-decision-resolved stream (random-uuid) decision-id
                                           "approved"
                                           "Matches rollout plan")]
      (is (= "Matches rollout plan" (:cp/comment event))))))

(deftest ^{:stratum 1} intervention-requested-test
  (testing "creates supervisory intervention requested event"
    (let [stream (no-op-stream)
          workflow-id (random-uuid)
          intervention {:intervention/id (random-uuid)
                        :intervention/type :pause
                        :intervention/target-type :workflow
                        :intervention/target-id workflow-id
                        :intervention/requested-by "operator@example.com"
                        :intervention/request-source :tui
                        :intervention/state :proposed
                        :intervention/requested-at (java.util.Date.)
                        :intervention/updated-at (java.util.Date.)}
          event (core/intervention-requested stream workflow-id intervention)]
      (is (= :supervisory/intervention-requested (:event/type event)))
      (is (= :pause (:intervention/type event)))
      (is (= :workflow (:intervention/target-type event)))
      (is (= :proposed (:intervention/state event)))
      (is (= (messages/t :supervisory/intervention-requested {:type "pause"})
             (:message event))))))

(deftest ^{:stratum 1} intervention-state-changed-test
  (testing "creates supervisory intervention lifecycle event"
    (let [stream (no-op-stream)
          intervention-id (random-uuid)
          event (core/intervention-state-changed
                 stream
                 (random-uuid)
                 intervention-id
                 :applied
                 {:intervention/from-state :dispatched
                  :intervention/type :pause
                  :intervention/outcome {:paused true}})]
      (is (= :supervisory/intervention-state-changed (:event/type event)))
      (is (= intervention-id (:intervention/id event)))
      (is (= :dispatched (:intervention/from-state event)))
      (is (= :applied (:intervention/state event)))
      (is (= {:paused true} (:intervention/outcome event)))
      (is (= (messages/t :supervisory/intervention-state-changed
                         {:intervention-id intervention-id
                          :state "applied"})
             (:message event))))))

;; Workflow failed edge cases
(deftest ^{:stratum 1} workflow-failed-with-exception-test
  (testing "workflow-failed from Throwable extracts message and type"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          ex (ex-info "LLM timeout" {:code :timeout})
          event (core/workflow-failed stream wf-id ex)]
      (is (= :workflow/failed (:event/type event)))
      (is (string? (:workflow/failure-reason event)))
      (is (map? (:workflow/error-details event))))))

(deftest ^{:stratum 1} workflow-failed-with-plain-map-test
  (testing "workflow-failed from plain error map"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          event (core/workflow-failed stream wf-id {:message "API error" :code 500})]
      (is (= :workflow/failed (:event/type event)))
      (is (= "API error" (:workflow/failure-reason event))))))

;; Phase completed transition request
(deftest ^{:stratum 1} phase-completed-transition-request-test
  (testing "phase-completed preserves transition requests and legacy redirect projection"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          result (phase/request-redirect {:outcome :failure} :implement)
          event (phase-events/phase-completed stream wf-id :review result)]
      (is (= :failure (:phase/outcome event)))
      (is (= :transition/redirect
             (get-in event [:phase/transition-request :transition/type])))
      (is (= :implement
             (get-in event [:phase/transition-request :transition/target])))
      (is (= :implement (:phase/redirect-to event))))))

(deftest ^{:stratum 1} phase-completed-with-error-test
  (testing "phase-completed captures error details"
    (let [stream (no-op-stream)
          wf-id (random-uuid)
          event (phase-events/phase-completed stream wf-id :implement
                                       {:outcome :failure
                                        :error {:message "compile error"
                                                :line 42}})]
      (is (= :failure (:phase/outcome event)))
      (is (= {:message "compile error" :line 42} (:phase/error event))))))

(deftest ^{:stratum 1} dependency-health-updated-test
  (testing "dependency-health-updated carries dependency projection and prior status"
    (let [stream (no-op-stream)
          dependency (dependency-health-entity {})
          event (compound/dependency-health-updated stream dependency :healthy)]
      (is (= :dependency/health-updated (:event/type event)))
      (is (= :anthropic (:dependency/id event)))
      (is (= :degraded (:dependency/status event)))
      (is (= :healthy (:dependency/previous-status event)))
      (is (= (messages/t :dependency/health-updated
                         {:dependency-id "anthropic"
                          :status "degraded"})
             (:message event))))))

(deftest ^{:stratum 1} dependency-recovered-test
  (testing "dependency-recovered carries healthy projection and prior status"
    (let [stream (no-op-stream)
          dependency (dependency-health-entity {:dependency/status :healthy
                                                :dependency/failure-count 0
                                                :dependency/incident-counts {}})
          event (compound/dependency-recovered stream dependency :degraded)]
      (is (= :dependency/recovered (:event/type event)))
      (is (= :healthy (:dependency/status event)))
      (is (= :degraded (:dependency/previous-status event)))
      (is (= (messages/t :dependency/recovered
                         {:dependency-id "anthropic"
                          :status "healthy"})
             (:message event))))))

;; Routing trigger events (N5-delta-4 §4.2)
(deftest ^{:stratum 1} pr-monitor-review-comments-arrived-test
  (testing "without :comments/agent-session-id (shorter overload)"
    (let [stream (no-op-stream)
          event  (core/pr-monitor-review-comments-arrived
                   stream "miniforge-ai/miniforge" 999 3)]
      (is (= :pr-monitor/review-comments-arrived (:event/type event)))
      (is (= "miniforge-ai/miniforge" (:pr/repo event)))
      (is (= 999 (:pr/number event)))
      (is (= 3 (:comments/count event)))
      (is (not (contains? event :comments/agent-session-id)))))

  (testing "with :comments/agent-session-id threaded onto the envelope"
    (let [stream     (no-op-stream)
          session-id (random-uuid)
          event      (core/pr-monitor-review-comments-arrived
                       stream "miniforge-ai/miniforge" 999 3 session-id)]
      (is (= session-id (:comments/agent-session-id event))))))

(deftest ^{:stratum 1} pr-monitor-ci-failed-test
  (testing "carries pr/repo, pr/number, ci/check-name, ci/conclusion"
    (let [stream (no-op-stream)
          event  (core/pr-monitor-ci-failed stream "miniforge-ai/miniforge"
                                            999 "tests" :failure)]
      (is (= :pr-monitor/ci-failed (:event/type event)))
      (is (= "miniforge-ai/miniforge" (:pr/repo event)))
      (is (= 999 (:pr/number event)))
      (is (= "tests" (:ci/check-name event)))
      (is (= :failure (:ci/conclusion event))))))

(deftest ^{:stratum 1} standards-review-posted-test
  (testing "without :affected/workflow-run-id (shorter overload)"
    (let [stream (no-op-stream)
          event  (core/standards-review-posted
                   stream "miniforge-ai/miniforge" 999 :advisory)]
      (is (= :standards-review/posted (:event/type event)))
      (is (= "miniforge-ai/miniforge" (:pr/repo event)))
      (is (= 999 (:pr/number event)))
      (is (= :advisory (:review/severity event)))
      (is (not (contains? event :affected/workflow-run-id)))))

  (testing "with :affected/workflow-run-id threaded onto the envelope"
    (let [stream  (no-op-stream)
          wf-id   (random-uuid)
          event   (core/standards-review-posted
                    stream "miniforge-ai/miniforge" 999 :blocking wf-id)]
      (is (= wf-id (:affected/workflow-run-id event)))
      (is (= :blocking (:review/severity event))))))
