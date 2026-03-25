(ns ai.miniforge.loop.escalation-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.loop.escalation :as escalation]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def mock-loop-state
  {:loop/id (random-uuid)
   :loop/state :escalated
   :loop/iteration 5
   :loop/errors [{:code :syntax-error
                  :message "Parse error on line 10"}
                 {:code :lint-error
                  :message "Unused variable 'x'"}]
   :loop/artifact {:artifact/id (random-uuid)
                   :artifact/type :code
                   :artifact/content "(defn broken [\n  incomplete"}
   :loop/termination {:reason :max-iterations}})

;------------------------------------------------------------------------------ Layer 1
;; Prompt formatting tests

(deftest format-error-context-test
  (testing "formats errors and iteration count"
    (let [errors [{:code :syntax :message "Missing closing paren"}
                  {:code :lint :message "Unused var"}]
          result (escalation/format-error-context errors 3 {})]
      (is (string? result))
      (is (re-find #"After 3 attempts" result))
      (is (re-find #"Missing closing paren" result))
      (is (re-find #"Unused var" result))))

  (testing "includes artifact preview"
    (let [artifact {:artifact/content "some code here"}
          result (escalation/format-error-context [] 1 artifact)]
      (is (re-find #"some code here" result))))

  (testing "truncates long content"
    (let [long-content (apply str (repeat 500 "x"))
          artifact {:artifact/content long-content}
          result (escalation/format-error-context [] 1 artifact)]
      (is (< (count result) (+ 300 (count "After 1 attempts"))))
      (is (re-find #"\.\.\.$" result)))))

(deftest format-escalation-prompt-test
  (testing "includes all necessary information"
    (let [prompt (escalation/format-escalation-prompt mock-loop-state)]
      (is (string? prompt))
      (is (re-find #"AGENT ESCALATION" prompt))
      (is (re-find #"After 5 attempts" prompt))
      (is (re-find #"Parse error" prompt))
      (is (re-find #"Options:" prompt))
      (is (re-find #"Provide hints" prompt))
      (is (re-find #"Abort" prompt)))))

;------------------------------------------------------------------------------ Layer 2
;; User interaction tests

(deftest prompt-user-test
  (testing "abort input returns abort action"
    (with-redefs [escalation/read-user-input (fn [] "abort")]
      (let [result (escalation/prompt-user "test")]
        (is (= :abort (:type result))))))

  (testing "ABORT uppercase returns abort action"
    (with-redefs [escalation/read-user-input (fn [] "ABORT")]
      (let [result (escalation/prompt-user "test")]
        (is (= :abort (:type result))))))

  (testing "text input returns hints"
    (with-redefs [escalation/read-user-input (fn [] "Fix the bracket")]
      (let [result (escalation/prompt-user "test")]
        (is (= :hints (:type result)))
        (is (= "Fix the bracket" (:content result))))))

  (testing "whitespace is trimmed"
    (with-redefs [escalation/read-user-input (fn [] "  hint text  ")]
      (let [result (escalation/prompt-user "test")]
        (is (= :hints (:type result)))
        (is (= "hint text" (:content result)))))))

(deftest escalate-to-user-test
  (testing "returns continue action with hints"
    (let [mock-prompt (fn [_] {:type :hints :content "Try this fix"})
          result (escalation/escalate-to-user mock-loop-state :prompt-fn mock-prompt)]
      (is (= :continue (:action result)))
      (is (= "Try this fix" (:hints result)))))

  (testing "returns abort action when user aborts"
    (let [mock-prompt (fn [_] {:type :abort})
          result (escalation/escalate-to-user mock-loop-state :prompt-fn mock-prompt)]
      (is (= :abort (:action result)))))

  (testing "uses default prompt-fn if not provided"
    ;; Should not throw, uses internal prompt-user
    (let [mock-prompt (fn [_] {:type :abort})]
      (with-redefs [escalation/prompt-user mock-prompt]
        (let [result (escalation/escalate-to-user mock-loop-state)]
          (is (map? result))
          (is (contains? result :action)))))))

;------------------------------------------------------------------------------ Layer 2
;; Inner loop integration tests

(deftest handle-escalation-test
  (testing "calls escalation function when provided"
    (let [mock-escalation (fn [_state & _opts] {:action :continue :hints "hint"})
          result (escalation/handle-escalation mock-loop-state
                                               {:escalation-fn mock-escalation})]
      (is (:escalated result))
      (is (= :continue (:action result)))
      (is (= "hint" (:hints result)))
      (is (= :resolved
             (get-in result [:decision/checkpoint :checkpoint/status])))
      (is (= :approve-with-constraints
             (get-in result [:decision/episode :supervision :type])))))

  (testing "defaults to abort when no escalation function"
    (let [result (escalation/handle-escalation mock-loop-state {})]
      (is (:escalated result))
      (is (= :abort (:action result)))
      (is (= :system
             (get-in result [:decision/episode :supervision :authority-role])))))

  (testing "passes custom prompt-fn through"
    (let [custom-prompt (fn [_] {:type :hints :content "custom"})
          mock-escalation (fn [_state & opts]
                            (let [result (apply escalation/escalate-to-user _state opts)]
                              result))
          result (escalation/handle-escalation mock-loop-state
                                               {:escalation-fn mock-escalation
                                                :prompt-fn custom-prompt})]
      (is (= :continue (:action result)))
      (is (= "custom" (:hints result))))))

(deftest create-escalation-checkpoint-test
  (testing "creates canonical checkpoint data from loop state"
    (let [checkpoint (escalation/create-escalation-checkpoint mock-loop-state)]
      (is (= :loop-escalation (get-in checkpoint [:source :kind])))
      (is (= :repair-escalation
             (get-in checkpoint [:proposal :decision-class])))
      (is (= 5 (get-in checkpoint [:context :loop/iteration]))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.loop.escalation-test)

  :leave-this-here)
