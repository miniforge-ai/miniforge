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
  (testing "prs-synced produces correct message with items only"
    (let [items [{:pr/id 1} {:pr/id 2}]
          m (msg/prs-synced items)]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= items (:pr-items (msg-payload m))))))

  (testing "prs-synced with error includes error key"
    (let [m (msg/prs-synced [] "timeout")]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= "timeout" (:error (msg-payload m))))))

  (testing "prs-synced with nil error omits error key"
    (let [[_ payload] (msg/prs-synced [] nil)]
      (is (not (contains? payload :error))))))

(deftest prs-synced-with-cache-test
  (testing "prs-synced-with-cache includes cached-risk and error"
    (let [m (msg/prs-synced-with-cache [{:pr/id 1}] {:risk :high} nil)]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= [{:pr/id 1}] (:pr-items (msg-payload m))))
      (is (= {:risk :high} (:cached-risk (msg-payload m)))))
    (let [m (msg/prs-synced-with-cache [] {} "err")]
      (is (= "err" (:error (msg-payload m)))))))

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
  (testing "decomposition-started merges pr-id into plan payload"
    (let [m (msg/decomposition-started :pr-1 {:sub-prs [1 2]})]
      (is (= :msg/decomposition-started (msg-type m)))
      (is (= :pr-1 (:pr-id (msg-payload m))))
      (is (= [1 2] (:sub-prs (msg-payload m))))))

  (testing "decomposition-started with error message"
    (let [m (msg/decomposition-started ["r" 1] {:sub-prs [] :message "Failed"})]
      (is (= :msg/decomposition-started (msg-type m)))
      (is (= ["r" 1] (:pr-id (msg-payload m))))
      (is (= [] (:sub-prs (msg-payload m))))
      (is (= "Failed" (:message (msg-payload m)))))))

(deftest pr-diff-fetched-test
  (testing "pr-diff-fetched with all fields"
    (let [m (msg/pr-diff-fetched ["r" 42] "diff" {:title "T"} nil)]
      (is (= :msg/pr-diff-fetched (msg-type m)))
      (is (= ["r" 42] (:pr-id (msg-payload m))))
      (is (= "diff" (:diff (msg-payload m))))
      (is (= {:title "T"} (:detail (msg-payload m))))
      (is (not (contains? (msg-payload m) :error)))))

  (testing "pr-diff-fetched with error"
    (let [m (msg/pr-diff-fetched ["r" 1] nil nil "boom")]
      (is (= "boom" (:error (msg-payload m)))))))

(deftest chat-response-test
  (let [m (msg/chat-response "Hello" [{:action :create-pr}])]
    (is (= :msg/chat-response (msg-type m)))
    (is (= "Hello" (:content (msg-payload m))))
    (is (= 1 (count (:actions (msg-payload m)))))))

(deftest chat-action-result-test
  (let [m (msg/chat-action-result {:success? true})]
    (is (= :msg/chat-action-result (msg-type m)))
    (is (= {:success? true} (msg-payload m)))))

(deftest fleet-risk-triaged-test
  (testing "fleet-risk-triaged wraps assessments"
    (let [m (msg/fleet-risk-triaged [{:id ["r" 1] :level "high"}])]
      (is (= :msg/fleet-risk-triaged (msg-type m)))
      (is (= [{:id ["r" 1] :level "high"}] (:assessments (msg-payload m))))))

  (testing "fleet-risk-triaged-error wraps error string"
    (let [m (msg/fleet-risk-triaged-error "LLM failed")]
      (is (= :msg/fleet-risk-triaged (msg-type m)))
      (is (= "LLM failed" (:error (msg-payload m)))))))

(deftest side-effect-error-test
  (let [m (msg/side-effect-error {:type :sync-prs :error "timeout"})]
    (is (= :msg/side-effect-error (msg-type m)))
    (is (= :sync-prs (:type (msg-payload m))))))

(deftest workflows-archived-test
  (let [m (msg/workflows-archived {:archived 3})]
    (is (= :msg/workflows-archived (msg-type m)))
    (is (= {:archived 3} (msg-payload m)))))

(deftest workflow-detail-loaded-test
  (let [m (msg/workflow-detail-loaded :wf-1 {:phases [:plan]})]
    (is (= :msg/workflow-detail-loaded (msg-type m)))
    (is (= :wf-1 (:workflow-id (msg-payload m))))
    (is (= {:phases [:plan]} (:detail (msg-payload m))))))

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
  (testing "without extras"
    (let [m (msg/phase-done :wf1 :plan :success)]
      (is (= :msg/phase-done (msg-type m)))
      (is (= :success (:outcome (msg-payload m))))))

  (testing "with extras merged in"
    (let [m (msg/phase-done :wf1 :plan :success {:duration-ms 500})]
      (is (= 500 (:duration-ms (msg-payload m)))))))

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

(deftest workflow-done-msg-test
  (testing "without extras"
    (let [m (msg/workflow-done :wf1 :success)]
      (is (= :msg/workflow-done (msg-type m)))
      (is (= :success (:status (msg-payload m))))))

  (testing "with extras merged in"
    (let [m (msg/workflow-done :wf1 :success {:duration-ms 1000})]
      (is (= 1000 (:duration-ms (msg-payload m)))))))

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

;; ---------------------------------------------------------------------------- Invariant: all messages are [keyword map] vectors

(deftest all-messages-are-keyword-map-vectors-test
  (testing "every constructor returns [keyword map-or-value]"
    (let [msgs [(msg/prs-synced [])
                (msg/prs-synced [] "err")
                (msg/prs-synced-with-cache [] {} nil)
                (msg/repos-discovered {:ok true})
                (msg/repos-browsed {:ok true})
                (msg/policy-evaluated :id {})
                (msg/train-created :id "name")
                (msg/prs-added-to-train {} 1)
                (msg/merge-started 1 {})
                (msg/review-completed [])
                (msg/remediation-completed 0 0 "")
                (msg/decomposition-started :id {})
                (msg/pr-diff-fetched :id nil nil nil)
                (msg/chat-response "" [])
                (msg/chat-action-result {})
                (msg/fleet-risk-triaged [])
                (msg/fleet-risk-triaged-error "e")
                (msg/side-effect-error {})
                (msg/workflows-archived {})
                (msg/workflow-detail-loaded :id {})
                (msg/workflow-added :id "n" {})
                (msg/phase-changed :id :p)
                (msg/phase-done :id :p :o)
                (msg/agent-status :wf :a :s "m")
                (msg/agent-output :wf :a "d" false)
                (msg/agent-started :wf :a {})
                (msg/agent-completed :wf :a {})
                (msg/agent-failed :wf :a {})
                (msg/workflow-done :wf :s)
                (msg/workflow-failed :wf "e")
                (msg/gate-result :wf :g true)
                (msg/gate-started :wf :g)
                (msg/tool-invoked :wf :a :t)
                (msg/tool-completed :wf :a :t)
                (msg/chain-started :c 1)
                (msg/chain-step-started :c :s 0 :wf)
                (msg/chain-step-completed :c :s 0)
                (msg/chain-step-failed :c :s 0 "e")
                (msg/chain-completed :c 100 1)
                (msg/chain-failed :c :s "e")]]
      (doseq [m msgs]
        (is (vector? m) (str "Expected vector for " (first m)))
        (is (= 2 (count m)) (str "Expected 2-element vector for " (first m)))
        (is (keyword? (first m)) (str "Expected keyword type for " m))))))