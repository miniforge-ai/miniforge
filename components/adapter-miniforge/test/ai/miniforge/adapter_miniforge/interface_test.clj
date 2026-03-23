(ns ai.miniforge.adapter-miniforge.interface-test
  "Tests for the miniforge native adapter."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.adapter-miniforge.interface :as sut]
   [ai.miniforge.control-plane-adapter.protocol :as proto]
   [ai.miniforge.event-stream.interface :as es]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def ^:private sample-workflow-event
  {:event/type    :agent-started
   :workflow/id   "wf-123"
   :workflow/name "Build Feature X"
   :message       "Starting agent"})

(def ^:private sample-agent-record
  {:agent/id          #uuid "00000000-0000-0000-0000-000000000001"
   :agent/external-id "wf-123"
   :agent/status      :running
   :agent/metadata    {:workflow-id "wf-123"
                       :event-type  :agent-started}})

;; ---------------------------------------------------------------------------
;; Layer 0 — Event → status mapping
;; ---------------------------------------------------------------------------

(deftest event-type->status-mapping-test
  (testing "all known event types map to expected statuses"
    (let [mapping @#'sut/event-type->status]
      (are [event-type expected-status]
           (= expected-status (get mapping event-type))
        :workflow-started   :running
        :phase-started      :running
        :phase-completed    :idle
        :agent-started      :running
        :agent-completed    :idle
        :agent-failed       :failed
        :workflow-completed :completed
        :workflow-failed    :failed)))

  (testing "unknown event type returns nil"
    (is (nil? (get @#'sut/event-type->status :unknown-event)))))

;; ---------------------------------------------------------------------------
;; Layer 0 — event->agent-info
;; ---------------------------------------------------------------------------

(deftest event->agent-info-test
  (let [extract #'sut/event->agent-info]

    (testing "extracts full agent info from a well-formed event"
      (let [info (extract sample-workflow-event)]
        (is (= :miniforge (:agent/vendor info)))
        (is (= "wf-123" (:agent/external-id info)))
        (is (= "Miniforge: Build Feature X" (:agent/name info)))
        (is (= #{:code-generation :test-writing :code-review}
               (:agent/capabilities info)))
        (is (= {:workflow-id "wf-123"
                :event-type  :agent-started}
               (:agent/metadata info)))))

    (testing "falls back to :message when :workflow/name is nil"
      (let [event (dissoc sample-workflow-event :workflow/name)
            info  (extract event)]
        (is (= "Miniforge: Starting agent" (:agent/name info)))))

    (testing "falls back to workflow-id when both name and message are nil"
      (let [event (dissoc sample-workflow-event :workflow/name :message)
            info  (extract event)]
        (is (= "Miniforge: wf-123" (:agent/name info)))))

    (testing "handles nil workflow-id gracefully"
      (let [event {:event/type :agent-started}
            info  (extract event)]
        (is (= "" (:agent/external-id info)))
        (is (string? (:agent/name info)))))))

;; ---------------------------------------------------------------------------
;; Layer 1 — Protocol: adapter-id
;; ---------------------------------------------------------------------------

(deftest adapter-id-test
  (let [adapter (sut/create-adapter :fake-stream)]
    (testing "returns :miniforge keyword"
      (is (= :miniforge (proto/adapter-id adapter))))))

;; ---------------------------------------------------------------------------
;; Layer 1 — Protocol: discover-agents
;; ---------------------------------------------------------------------------

(deftest discover-agents-test
  (let [adapter (sut/create-adapter :fake-stream)]
    (testing "returns empty vec (agents discovered via subscription)"
      (is (= [] (proto/discover-agents adapter {}))))
    (testing "returns empty vec regardless of config"
      (is (= [] (proto/discover-agents adapter {:some "config"}))))))

;; ---------------------------------------------------------------------------
;; Layer 1 — Protocol: poll-agent-status
;; ---------------------------------------------------------------------------

(deftest poll-agent-status-test
  (let [adapter (sut/create-adapter :fake-stream)]
    (testing "returns current status from agent record"
      (is (= {:status :running}
             (proto/poll-agent-status adapter {:agent/status :running}))))
    (testing "returns nil status when agent record has no status"
      (is (= {:status nil}
             (proto/poll-agent-status adapter {}))))))

;; ---------------------------------------------------------------------------
;; Layer 1 — Protocol: deliver-decision
;; ---------------------------------------------------------------------------

(deftest deliver-decision-test
  (let [adapter (sut/create-adapter :fake-stream)]
    (testing "returns delivered true (stub pending approval wiring)"
      (let [result (proto/deliver-decision adapter sample-agent-record {:decision/id "d-1"})]
        (is (true? (:delivered? result)))))))

;; ---------------------------------------------------------------------------
;; Layer 1 — Protocol: send-command
;; ---------------------------------------------------------------------------

(deftest send-command-test
  (let [commands-sent (atom [])
        fake-control  (reify Object)
        adapter       (sut/create-adapter :fake-stream)
        agent-with-cs (assoc-in sample-agent-record
                                [:agent/metadata :control-state]
                                fake-control)]

    (testing "dispatches :pause command"
      (with-redefs [es/pause! (fn [cs] (swap! commands-sent conj [:pause cs]))]
        (let [result (proto/send-command adapter agent-with-cs :pause)]
          (is (true? (:success? result)))
          (is (= [[:pause fake-control]] @commands-sent)))))

    (reset! commands-sent [])

    (testing "dispatches :resume command"
      (with-redefs [es/resume! (fn [cs] (swap! commands-sent conj [:resume cs]))]
        (let [result (proto/send-command adapter agent-with-cs :resume)]
          (is (true? (:success? result))))))

    (reset! commands-sent [])

    (testing "dispatches :cancel command"
      (with-redefs [es/cancel! (fn [cs] (swap! commands-sent conj [:cancel cs]))]
        (let [result (proto/send-command adapter agent-with-cs :cancel)]
          (is (true? (:success? result))))))))

(deftest send-command-no-control-state-test
  (let [adapter (sut/create-adapter :fake-stream)]
    (testing "returns failure when no control-state in metadata"
      (let [result (proto/send-command adapter sample-agent-record :pause)]
        (is (false? (:success? result)))
        (is (string? (:error result)))))))

(deftest send-command-nil-metadata-test
  (let [adapter (sut/create-adapter :fake-stream)
        agent   (dissoc sample-agent-record :agent/metadata)]
    (testing "returns failure when metadata is nil"
      (let [result (proto/send-command adapter agent :cancel)]
        (is (false? (:success? result)))))))

;; ---------------------------------------------------------------------------
;; Layer 2 — Factory
;; ---------------------------------------------------------------------------

(deftest create-adapter-test
  (testing "creates a MiniforgeAdapter record"
    (let [adapter (sut/create-adapter :my-stream)]
      (is (instance? ai.miniforge.adapter_miniforge.interface.MiniforgeAdapter adapter))
      (is (= :my-stream (:event-stream adapter))))))

;; ---------------------------------------------------------------------------
;; Layer 2 — Event bridge
;; ---------------------------------------------------------------------------

(deftest create-event-bridge-test
  (let [captured-events (atom [])
        captured-sub    (atom nil)
        fake-stream     (reify Object)]
    (testing "subscribes to event stream and returns sub-key"
      (with-redefs [es/subscribe!
                    (fn [stream key handler]
                      (reset! captured-sub {:stream stream :key key :handler handler}))]
        (let [sub-key (sut/create-event-bridge
                       fake-stream
                       (fn [evt] (swap! captured-events conj evt)))]
          (is (= :control-plane-bridge sub-key))
          (is (= fake-stream (:stream @captured-sub)))
          (is (= :control-plane-bridge (:key @captured-sub))))))))

(deftest create-event-bridge-dispatches-relevant-events-test
  (let [captured-events (atom [])
        handler-ref     (atom nil)]
    (with-redefs [es/subscribe!
                  (fn [_stream _key handler]
                    (reset! handler-ref handler))]
      (sut/create-event-bridge :fake-stream
                               (fn [evt] (swap! captured-events conj evt)))
      (let [handler @handler-ref]
        (testing "dispatches :agent-started with correct mapping"
          (handler sample-workflow-event)
          (is (= 1 (count @captured-events)))
          (let [evt (first @captured-events)]
            (is (= :agent-started (:event-type evt)))
            (is (= :running (:status evt)))
            (is (= :miniforge (get-in evt [:agent-info :agent/vendor])))))

        (testing "dispatches :workflow-completed"
          (handler {:event/type :workflow-completed :workflow/id "wf-456"})
          (is (= 2 (count @captured-events)))
          (is (= :completed (:status (second @captured-events)))))

        (testing "ignores irrelevant event types"
          (handler {:event/type :some-random-event :workflow/id "wf-789"})
          (is (= 2 (count @captured-events))
              "count should remain at 2 after irrelevant event"))))))

(deftest create-event-bridge-all-event-types-test
  (let [captured (atom [])
        handler-ref (atom nil)]
    (with-redefs [es/subscribe!
                  (fn [_ _ handler] (reset! handler-ref handler))]
      (sut/create-event-bridge :s (fn [e] (swap! captured conj e)))
      (let [handler @handler-ref]
        (testing "every mapped event type produces an output event"
          (doseq [et [:workflow-started :phase-started :phase-completed
                      :agent-started :agent-completed :agent-failed
                      :workflow-completed :workflow-failed]]
            (handler {:event/type et :workflow/id (str et)}))
          (is (= 8 (count @captured))))))))
