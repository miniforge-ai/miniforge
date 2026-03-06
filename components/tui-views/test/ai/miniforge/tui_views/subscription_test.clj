(ns ai.miniforge.tui-views.subscription-test
  "Tests for event stream -> TUI message translation."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.subscription :as sub]))

;; ---------------------------------------------------------------------------- Layer 0: translate-event tests

(deftest translate-workflow-started-test
  (testing "translates workflow/started with spec name"
    (let [[t p] (sub/translate-event {:event/type :workflow/started
                                      :workflow/id :wf-1
                                      :workflow/spec {:name "My WF"}})]
      (is (= :msg/workflow-added t))
      (is (= :wf-1 (:workflow-id p)))
      (is (= "My WF" (:name p))))))

(deftest translate-workflow-started-alt-keys-test
  (testing "translates workflow/started with alternative key names"
    (let [[t p] (sub/translate-event {:event/type :workflow/started
                                      :workflow-id :wf-2
                                      :workflow-spec {:name "Alt WF"}})]
      (is (= :msg/workflow-added t))
      (is (= :wf-2 (:workflow-id p)))
      (is (= "Alt WF" (:name p))))))

(deftest translate-phase-started-test
  (testing "translates workflow/phase-started"
    (let [[t p] (sub/translate-event {:event/type :workflow/phase-started
                                      :workflow/id :wf-1
                                      :workflow/phase :plan})]
      (is (= :msg/phase-changed t))
      (is (= :plan (:phase p))))))

(deftest translate-phase-completed-test
  (testing "translates workflow/phase-completed with outcome"
    (let [[t p] (sub/translate-event {:event/type :workflow/phase-completed
                                      :workflow/id :wf-1
                                      :workflow/phase :implement
                                      :phase/outcome :success})]
      (is (= :msg/phase-done t))
      (is (= :implement (:phase p)))
      (is (= :success (:outcome p))))))

(deftest translate-phase-completed-alt-outcome-test
  (testing "translates phase-completed with alternative outcome path"
    (let [[t p] (sub/translate-event {:event/type :workflow/phase-completed
                                      :workflow/id :wf-1
                                      :workflow/phase :verify
                                      :result {:outcome :failure}})]
      (is (= :msg/phase-done t))
      (is (= :failure (:outcome p))))))

(deftest translate-agent-started-test
  (testing "translates agent/started"
    (let [[t p] (sub/translate-event {:event/type :agent/started
                                      :workflow/id :wf-1
                                      :agent/id :planner
                                      :agent/context {:phase :plan}})]
      (is (= :msg/agent-started t))
      (is (= :planner (:agent p)))
      (is (= {:phase :plan} (:context p))))))

(deftest translate-agent-completed-test
  (let [[t p] (sub/translate-event {:event/type :agent/completed
                                    :workflow/id :wf-1
                                    :agent/id :implementer
                                    :agent/result {:outcome :success}})]
    (is (= :msg/agent-completed t))
    (is (= :implementer (:agent p)))
    (is (= {:outcome :success} (:result p)))))

(deftest translate-agent-failed-test
  (let [[t p] (sub/translate-event {:event/type :agent/failed
                                    :workflow/id :wf-1
                                    :agent/id :reviewer
                                    :agent/error {:message "timeout"}})]
    (is (= :msg/agent-failed t))
    (is (= :reviewer (:agent p)))
    (is (= {:message "timeout"} (:error p)))))

(deftest translate-agent-status-test
  (let [[t p] (sub/translate-event {:event/type :agent/status
                                    :workflow/id :wf-1
                                    :agent/id :planner
                                    :status/type :thinking
                                    :message "Analyzing"})]
    (is (= :msg/agent-status t))
    (is (= :thinking (:status p)))
    (is (= "Analyzing" (:message p)))))

(deftest translate-agent-chunk-test
  (let [[t p] (sub/translate-event {:event/type :agent/chunk
                                    :workflow/id :wf-1
                                    :agent/id :planner
                                    :chunk/delta "Hello"
                                    :chunk/done? false})]
    (is (= :msg/agent-output t))
    (is (= "Hello" (:delta p)))
    (is (false? (:done? p)))))

(deftest translate-agent-chunk-alt-keys-test
  (let [[t p] (sub/translate-event {:event/type :agent/chunk
                                    :workflow-id :wf-1
                                    :agent :planner
                                    :delta "text"
                                    :done? true})]
    (is (= :msg/agent-output t))
    (is (= "text" (:delta p)))
    (is (true? (:done? p)))))

(deftest translate-workflow-completed-test
  (let [[t p] (sub/translate-event {:event/type :workflow/completed
                                    :workflow/id :wf-1
                                    :workflow/status :success})]
    (is (= :msg/workflow-done t))
    (is (= :success (:status p)))))

(deftest translate-workflow-failed-test
  (let [[t p] (sub/translate-event {:event/type :workflow/failed
                                    :workflow/id :wf-1
                                    :workflow/failure-reason "LLM error"})]
    (is (= :msg/workflow-failed t))
    (is (= "LLM error" (:error p)))))

(deftest translate-gate-started-test
  (let [[t p] (sub/translate-event {:event/type :gate/started
                                    :workflow/id :wf-1
                                    :gate/id :lint})]
    (is (= :msg/gate-started t))
    (is (= :lint (:gate p)))))

(deftest translate-gate-passed-test
  (let [[t p] (sub/translate-event {:event/type :gate/passed
                                    :workflow/id :wf-1
                                    :gate/id :test})]
    (is (= :msg/gate-result t))
    (is (= :test (:gate p)))
    (is (true? (:passed? p)))))

(deftest translate-gate-failed-test
  (let [[t p] (sub/translate-event {:event/type :gate/failed
                                    :workflow/id :wf-1
                                    :gate/id :security})]
    (is (= :msg/gate-result t))
    (is (= :security (:gate p)))
    (is (false? (:passed? p)))))

(deftest translate-tool-invoked-test
  (let [[t p] (sub/translate-event {:event/type :tool/invoked
                                    :workflow/id :wf-1
                                    :agent/id :implementer
                                    :tool/id :tools/read-file})]
    (is (= :msg/tool-invoked t))
    (is (= :tools/read-file (:tool p)))))

(deftest translate-tool-completed-test
  (let [[t p] (sub/translate-event {:event/type :tool/completed
                                    :workflow/id :wf-1
                                    :agent/id :implementer
                                    :tool/id :tools/write-file})]
    (is (= :msg/tool-completed t))
    (is (= :tools/write-file (:tool p)))))

(deftest translate-unknown-event-returns-nil-test
  (is (nil? (sub/translate-event {:event/type :custom/unknown-event}))))

;; ---------------------------------------------------------------------------- Layer 0: Chain event translation
;; (complementing chain_events_test.clj with edge cases)

(deftest translate-chain-events-complete-test
  (testing "all chain event types translate correctly"
    (let [events [{:event/type :chain/started :chain/id :c1 :chain/step-count 2}
                  {:event/type :chain/step-started :chain/id :c1 :step/id :plan :step/index 0 :step/workflow-id :wf1}
                  {:event/type :chain/step-completed :chain/id :c1 :step/id :plan :step/index 0}
                  {:event/type :chain/step-failed :chain/id :c1 :step/id :impl :step/index 1 :chain/error "boom"}
                  {:event/type :chain/completed :chain/id :c1 :chain/duration-ms 5000 :chain/step-count 2}
                  {:event/type :chain/failed :chain/id :c1 :chain/failed-step :impl :chain/error "boom"}]
          results (mapv sub/translate-event events)
          types (mapv first results)]
      (is (= [:msg/chain-started :msg/chain-step-started :msg/chain-step-completed
              :msg/chain-step-failed :msg/chain-completed :msg/chain-failed]
             types))
      ;; None should be nil
      (is (every? some? results)))))
