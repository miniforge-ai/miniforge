(ns ai.miniforge.tui-views.msg-contract-test
  "Contract tests for msg.clj — verifies structural invariants
   that all message constructors must satisfy.

   These tests complement the existing msg_test.clj and msg_extended_test.clj
   by testing cross-cutting invariants rather than individual constructors."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.msg :as msg]))

;; ---------------------------------------------------------------------------- Helpers

(defn msg-type [m] (first m))
(defn msg-payload [m] (second m))

;; ---------------------------------------------------------------------------- pr-diff-fetched contract

(deftest pr-diff-fetched-contract-test
  (testing "pr-diff-fetched always has :pr-id :diff :detail in payload"
    (doseq [[label args] [["all present" [["r" 1] "diff" {:title "T"} nil]]
                           ["all nil"     [["r" 1] nil nil nil]]
                           ["error"       [["r" 1] nil nil "err"]]
                           ["diff only"   [["r" 1] "d" nil nil]]
                           ["detail only" [["r" 1] nil {:t 1} nil]]]]
      (testing label
        (let [m (apply msg/pr-diff-fetched args)
              p (msg-payload m)]
          (is (= :msg/pr-diff-fetched (msg-type m)))
          (is (contains? p :pr-id))
          (is (contains? p :diff))
          (is (contains? p :detail)))))))

(deftest pr-diff-fetched-error-omission-test
  (testing ":error key is absent when error arg is nil"
    (let [p (msg-payload (msg/pr-diff-fetched ["r" 1] "d" {:t 1} nil))]
      (is (not (contains? p :error)))))

  (testing ":error key is present when error arg is non-nil"
    (let [p (msg-payload (msg/pr-diff-fetched ["r" 1] nil nil "boom"))]
      (is (contains? p :error))
      (is (= "boom" (:error p))))))

;; ---------------------------------------------------------------------------- prs-synced arity contract

(deftest prs-synced-arity-contract-test
  (testing "1-arity always has :pr-items, never :error"
    (let [p (msg-payload (msg/prs-synced [{:id 1}]))]
      (is (contains? p :pr-items))
      (is (not (contains? p :error)))))

  (testing "2-arity with error has both :pr-items and :error"
    (let [p (msg-payload (msg/prs-synced [{:id 1}] "timeout"))]
      (is (contains? p :pr-items))
      (is (= "timeout" (:error p)))))

  (testing "2-arity with nil error omits :error (same as 1-arity)"
    (let [p (msg-payload (msg/prs-synced [{:id 1}] nil))]
      (is (not (contains? p :error))))))

;; ---------------------------------------------------------------------------- prs-synced-with-cache contract

(deftest prs-synced-with-cache-contract-test
  (testing "always includes :pr-items and :cached-risk"
    (let [p (msg-payload (msg/prs-synced-with-cache [] {} nil))]
      (is (contains? p :pr-items))
      (is (contains? p :cached-risk))))

  (testing "omits :error when nil, includes when present"
    (is (not (contains? (msg-payload (msg/prs-synced-with-cache [] {} nil)) :error)))
    (is (= "e" (:error (msg-payload (msg/prs-synced-with-cache [] {} "e")))))))

;; ---------------------------------------------------------------------------- Message type namespace

(deftest all-msg-types-use-msg-namespace-test
  (testing "all message types use :msg/ namespace prefix"
    (let [messages [(msg/prs-synced [])
                    (msg/repos-discovered {})
                    (msg/repos-browsed {})
                    (msg/policy-evaluated :id {})
                    (msg/pr-diff-fetched :id nil nil nil)
                    (msg/train-created :id "n")
                    (msg/prs-added-to-train {} 0)
                    (msg/merge-started 1 {})
                    (msg/review-completed [])
                    (msg/remediation-completed 0 0 "m")
                    (msg/decomposition-started :id {})
                    (msg/chat-response "" [])
                    (msg/chat-action-result {})
                    (msg/fleet-risk-triaged [])
                    (msg/fleet-risk-triaged-error "e")
                    (msg/side-effect-error {})
                    (msg/workflows-archived {})
                    (msg/workflow-detail-loaded :id {})
                    (msg/workflow-added :w "n" {})
                    (msg/phase-changed :w :p)
                    (msg/phase-done :w :p :o)
                    (msg/agent-status :w :a :s "m")
                    (msg/agent-output :w :a "d" false)
                    (msg/agent-started :w :a {})
                    (msg/agent-completed :w :a {})
                    (msg/agent-failed :w :a {})
                    (msg/workflow-done :w :s)
                    (msg/workflow-failed :w "e")
                    (msg/gate-result :w :g true)
                    (msg/gate-started :w :g)
                    (msg/tool-invoked :w :a :t)
                    (msg/tool-completed :w :a :t)
                    (msg/chain-started :c 3)
                    (msg/chain-step-started :c :s 0 :w)
                    (msg/chain-step-completed :c :s 0)
                    (msg/chain-step-failed :c :s 0 "e")
                    (msg/chain-completed :c 100 3)
                    (msg/chain-failed :c :s "e")]]
      (doseq [m messages]
        (is (= "msg" (namespace (msg-type m)))
            (str "Expected :msg/ namespace for " (msg-type m)))))))

;; ---------------------------------------------------------------------------- Chain messages contract

(deftest chain-messages-always-include-chain-id-test
  (testing "all chain messages include :chain-id in payload"
    (let [chain-msgs [(msg/chain-started :c 3)
                      (msg/chain-step-started :c :s 0 :w)
                      (msg/chain-step-completed :c :s 0)
                      (msg/chain-step-failed :c :s 0 "e")
                      (msg/chain-completed :c 100 3)
                      (msg/chain-failed :c :s "e")]]
      (doseq [m chain-msgs]
        (is (= :c (:chain-id (msg-payload m)))
            (str "Expected :chain-id = :c for " (msg-type m)))))))

;; ---------------------------------------------------------------------------- Workflow messages contract

(deftest workflow-event-messages-always-include-workflow-id-test
  (testing "all workflow/agent/gate/tool messages include :workflow-id"
    (let [wf-msgs [(msg/workflow-added :wf "n" {})
                   (msg/phase-changed :wf :p)
                   (msg/phase-done :wf :p :o)
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
                   (msg/tool-completed :wf :a :t)]]
      (doseq [m wf-msgs]
        (is (= :wf (:workflow-id (msg-payload m)))
            (str "Expected :workflow-id = :wf for " (msg-type m)))))))