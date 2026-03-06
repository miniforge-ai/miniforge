(ns ai.miniforge.tui-views.msg-test
  "Tests for TUI message constructors.
   Verifies that all constructors produce [msg-type payload] vectors
   with the expected structure."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.msg :as msg]))

;; ---------------------------------------------------------------------------- Helpers

(defn msg-type [msg] (first msg))
(defn msg-payload [msg] (second msg))

;; ---------------------------------------------------------------------------- Layer 0: Side-effect result messages

(deftest prs-synced-test
  (testing "prs-synced produces correct message"
    (let [items [{:pr/id 1} {:pr/id 2}]
          m (msg/prs-synced items)]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= items (:pr-items (msg-payload m)))))))

(deftest repos-discovered-test
  (testing "repos-discovered wraps result"
    (let [result {:success? true :repos ["r1"]}
          m (msg/repos-discovered result)]
      (is (= :msg/repos-discovered (msg-type m)))
      (is (= result (msg-payload m))))))

(deftest repos-browsed-test
  (testing "repos-browsed wraps result"
    (let [result {:success? true :repos ["r1" "r2"]}
          m (msg/repos-browsed result)]
      (is (= :msg/repos-browsed (msg-type m)))
      (is (= result (msg-payload m))))))

(deftest policy-evaluated-test
  (testing "policy-evaluated includes pr-id and result"
    (let [m (msg/policy-evaluated ["repo" 42] {:passed? true})]
      (is (= :msg/policy-evaluated (msg-type m)))
      (is (= ["repo" 42] (:pr-id (msg-payload m))))
      (is (= {:passed? true} (:result (msg-payload m)))))))

(deftest train-created-test
  (testing "train-created includes id and name"
    (let [m (msg/train-created :t1 "My Train")]
      (is (= :msg/train-created (msg-type m)))
      (is (= :t1 (:train-id (msg-payload m))))
      (is (= "My Train" (:train-name (msg-payload m)))))))

(deftest prs-added-to-train-test
  (let [m (msg/prs-added-to-train {:train/id :t1} 3)]
    (is (= :msg/prs-added-to-train (msg-type m)))
    (is (= 3 (:added (msg-payload m))))))

(deftest merge-started-test
  (let [m (msg/merge-started 42 {:train/id :t1})]
    (is (= :msg/merge-started (msg-type m)))
    (is (= 42 (:pr-number (msg-payload m))))))

(deftest review-completed-test
  (let [m (msg/review-completed [{:pr-id 1 :result :ok}])]
    (is (= :msg/review-completed (msg-type m)))
    (is (= 1 (count (:results (msg-payload m)))))))

(deftest remediation-completed-test
  (let [m (msg/remediation-completed 3 1 "Done")]
    (is (= :msg/remediation-completed (msg-type m)))
    (is (= 3 (:fixed (msg-payload m))))
    (is (= 1 (:failed (msg-payload m))))
    (is (= "Done" (:message (msg-payload m))))))

(deftest decomposition-started-test
  (let [m (msg/decomposition-started :pr-1 {:sub-prs [1 2]})]
    (is (= :msg/decomposition-started (msg-type m)))
    (is (= :pr-1 (:pr-id (msg-payload m))))))

(deftest chat-response-test
  (let [m (msg/chat-response "Hello" [{:action :create-pr}])]
    (is (= :msg/chat-response (msg-type m)))
    (is (= "Hello" (:content (msg-payload m))))
    (is (= 1 (count (:actions (msg-payload m)))))))

(deftest chat-action-result-test
  (let [m (msg/chat-action-result {:success? true})]
    (is (= :msg/chat-action-result (msg-type m)))
    (is (= {:success? true} (msg-payload m)))))

(deftest side-effect-error-test
  (let [m (msg/side-effect-error {:type :sync-prs :error "timeout"})]
    (is (= :msg/side-effect-error (msg-type m)))
    (is (= :sync-prs (:type (msg-payload m))))))

;; ---------------------------------------------------------------------------- Layer 0b: Event stream translation messages

(deftest workflow-added-msg-test
  (let [m (msg/workflow-added :wf1 "Test WF" {:phases [:plan]})]
    (is (= :msg/workflow-added (msg-type m)))
    (is (= :wf1 (:workflow-id (msg-payload m))))
    (is (= "Test WF" (:name (msg-payload m))))
    (is (= {:phases [:plan]} (:spec (msg-payload m))))))

(deftest phase-changed-msg-test
  (let [m (msg/phase-changed :wf1 :implement)]
    (is (= :msg/phase-changed (msg-type m)))
    (is (= :wf1 (:workflow-id (msg-payload m))))
    (is (= :implement (:phase (msg-payload m))))))

(deftest phase-done-msg-test
  (let [m (msg/phase-done :wf1 :plan :success)]
    (is (= :msg/phase-done (msg-type m)))
    (is (= :success (:outcome (msg-payload m))))))

(deftest agent-status-msg-test
  (let [m (msg/agent-status :wf1 :planner :thinking "Analyzing")]
    (is (= :msg/agent-status (msg-type m)))
    (is (= :planner (:agent (msg-payload m))))
    (is (= :thinking (:status (msg-payload m))))
    (is (= "Analyzing" (:message (msg-payload m))))))

(deftest agent-output-msg-test
  (let [m (msg/agent-output :wf1 :planner "chunk text" false)]
    (is (= :msg/agent-output (msg-type m)))
    (is (= "chunk text" (:delta (msg-payload m))))
    (is (false? (:done? (msg-payload m))))))

(deftest workflow-done-msg-test
  (let [m (msg/workflow-done :wf1 :success)]
    (is (= :msg/workflow-done (msg-type m)))
    (is (= :success (:status (msg-payload m))))))

(deftest workflow-failed-msg-test
  (let [m (msg/workflow-failed :wf1 "error details")]
    (is (= :msg/workflow-failed (msg-type m)))
    (is (= "error details" (:error (msg-payload m))))))

(deftest gate-result-msg-test
  (let [m (msg/gate-result :wf1 :lint true)]
    (is (= :msg/gate-result (msg-type m)))
    (is (= :lint (:gate (msg-payload m))))
    (is (true? (:passed? (msg-payload m))))))

(deftest gate-started-msg-test
  (let [m (msg/gate-started :wf1 :lint)]
    (is (= :msg/gate-started (msg-type m)))
    (is (= :lint (:gate (msg-payload m))))))

(deftest tool-invoked-msg-test
  (let [m (msg/tool-invoked :wf1 :planner :tools/read-file)]
    (is (= :msg/tool-invoked (msg-type m)))
    (is (= :tools/read-file (:tool (msg-payload m))))))

(deftest tool-completed-msg-test
  (let [m (msg/tool-completed :wf1 :planner :tools/write-file)]
    (is (= :msg/tool-completed (msg-type m)))
    (is (= :tools/write-file (:tool (msg-payload m))))))

;; ---------------------------------------------------------------------------- Layer 0b2: Agent lifecycle messages

(deftest agent-started-msg-test
  (let [m (msg/agent-started :wf1 :planner {:phase :plan})]
    (is (= :msg/agent-started (msg-type m)))
    (is (= :planner (:agent (msg-payload m))))
    (is (= {:phase :plan} (:context (msg-payload m))))))

(deftest agent-completed-msg-test
  (let [m (msg/agent-completed :wf1 :planner {:outcome :success})]
    (is (= :msg/agent-completed (msg-type m)))
    (is (= {:outcome :success} (:result (msg-payload m))))))

(deftest agent-failed-msg-test
  (let [m (msg/agent-failed :wf1 :planner {:message "timeout"})]
    (is (= :msg/agent-failed (msg-type m)))
    (is (= {:message "timeout"} (:error (msg-payload m))))))

;; ---------------------------------------------------------------------------- Layer 0c: Chain event messages

(deftest chain-started-msg-test
  (let [m (msg/chain-started :my-chain 3)]
    (is (= :msg/chain-started (msg-type m)))
    (is (= :my-chain (:chain-id (msg-payload m))))
    (is (= 3 (:step-count (msg-payload m))))))

(deftest chain-step-started-msg-test
  (let [m (msg/chain-step-started :c1 :plan 0 :wf-1)]
    (is (= :msg/chain-step-started (msg-type m)))
    (is (= :plan (:step-id (msg-payload m))))
    (is (= 0 (:step-index (msg-payload m))))
    (is (= :wf-1 (:workflow-id (msg-payload m))))))

(deftest chain-step-completed-msg-test
  (let [m (msg/chain-step-completed :c1 :plan 0)]
    (is (= :msg/chain-step-completed (msg-type m)))
    (is (= :plan (:step-id (msg-payload m))))))

(deftest chain-step-failed-msg-test
  (let [m (msg/chain-step-failed :c1 :plan 0 "boom")]
    (is (= :msg/chain-step-failed (msg-type m)))
    (is (= "boom" (:error (msg-payload m))))))

(deftest chain-completed-msg-test
  (let [m (msg/chain-completed :c1 5000 3)]
    (is (= :msg/chain-completed (msg-type m)))
    (is (= 5000 (:duration-ms (msg-payload m))))
    (is (= 3 (:step-count (msg-payload m))))))

(deftest chain-failed-msg-test
  (let [m (msg/chain-failed :c1 :plan "error")]
    (is (= :msg/chain-failed (msg-type m)))
    (is (= :plan (:failed-step (msg-payload m))))
    (is (= "error" (:error (msg-payload m))))))
