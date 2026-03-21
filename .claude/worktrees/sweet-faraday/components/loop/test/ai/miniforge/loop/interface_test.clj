(ns ai.miniforge.loop.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.loop.interface :as loop]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-task
  {:task/id (random-uuid)
   :task/type :implement})

(defn valid-artifact []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(defn make-generate-fn [artifact]
  (fn [_task _ctx]
    {:artifact artifact
     :tokens 100}))

;------------------------------------------------------------------------------ Layer 1
;; Schema re-export tests

(deftest schema-exports-test
  (testing "inner-loop-states contains expected values"
    (is (some #{:pending} loop/inner-loop-states))
    (is (some #{:complete} loop/inner-loop-states))
    (is (some #{:escalated} loop/inner-loop-states)))

  (testing "gate-types contains expected values"
    (is (some #{:syntax} loop/gate-types))
    (is (some #{:lint} loop/gate-types))
    (is (some #{:policy} loop/gate-types)))

  (testing "outer-loop-phases contains expected phases"
    (is (some #{:plan} loop/outer-loop-phases))
    (is (some #{:implement} loop/outer-loop-phases))
    (is (some #{:observe} loop/outer-loop-phases))))

;------------------------------------------------------------------------------ Layer 1
;; Inner loop API tests

(deftest create-inner-loop-test
  (testing "creates loop with default options"
    (let [loop-state (loop/create-inner-loop test-task)]
      (is (uuid? (:loop/id loop-state)))
      (is (= :pending (:loop/state loop-state)))))

  (testing "creates loop with custom options"
    (let [loop-state (loop/create-inner-loop test-task {:max-iterations 10})]
      (is (= 10 (get-in loop-state [:loop/config :max-iterations]))))))

(deftest run-simple-test
  (testing "successful run"
    (let [result (loop/run-simple test-task
                                  (make-generate-fn (valid-artifact))
                                  {:max-iterations 3})]
      (is (:success result))
      (is (= 1 (:iterations result)))))

  (testing "run with logging context"
    (let [result (loop/run-simple test-task
                                  (make-generate-fn (valid-artifact))
                                  {:max-iterations 3
                                   :logger nil})]
      (is (:success result)))))

(deftest termination-checks-test
  (testing "terminal-state? detects terminal states"
    (is (loop/terminal-state? :complete))
    (is (loop/terminal-state? :failed))
    (is (not (loop/terminal-state? :pending)))))

;------------------------------------------------------------------------------ Layer 1
;; Gates API tests

(deftest gate-constructors-test
  (testing "syntax-gate creation"
    (let [gate (loop/syntax-gate)]
      (is (some? gate))))

  (testing "lint-gate creation"
    (let [gate (loop/lint-gate :my-lint {:fail-on-warning? true})]
      (is (some? gate))))

  (testing "policy-gate creation"
    (let [gate (loop/policy-gate :security {:policies [:no-secrets]})]
      (is (some? gate))))

  (testing "custom-gate creation"
    (let [gate (loop/custom-gate :custom-check
                                 (fn [_a _c] (loop/pass-result :custom-check :custom)))]
      (is (some? gate)))))

(deftest gate-sets-test
  (testing "default-gates returns gates"
    (let [gates (loop/default-gates)]
      (is (seq gates))
      (is (= 3 (count gates)))))

  (testing "minimal-gates returns single gate"
    (let [gates (loop/minimal-gates)]
      (is (= 1 (count gates)))))

  (testing "strict-gates returns gates"
    (let [gates (loop/strict-gates)]
      (is (seq gates)))))

(deftest run-gates-test
  (testing "run-gates returns result"
    (let [artifact (valid-artifact)
          result (loop/run-gates (loop/minimal-gates) artifact {})]
      (is (:passed? result))
      (is (seq (:results result))))))

(deftest result-helpers-test
  (testing "pass-result creates passing result"
    (let [result (loop/pass-result :test :syntax)]
      (is (:gate/passed? result))))

  (testing "fail-result creates failing result"
    (let [result (loop/fail-result :test :syntax
                                   [(loop/make-error :err "Error")])]
      (is (not (:gate/passed? result)))
      (is (seq (:gate/errors result)))))

  (testing "make-error creates error map"
    (let [error (loop/make-error :code "message")]
      (is (= :code (:code error)))
      (is (= "message" (:message error))))))

;------------------------------------------------------------------------------ Layer 1
;; Repair API tests

(deftest strategy-constructors-test
  (testing "llm-fix-strategy creation"
    (let [strategy (loop/llm-fix-strategy)]
      (is (some? strategy))))

  (testing "retry-strategy creation"
    (let [strategy (loop/retry-strategy {:delay-ms 500})]
      (is (some? strategy))))

  (testing "escalate-strategy creation"
    (let [strategy (loop/escalate-strategy)]
      (is (some? strategy)))))

(deftest default-strategies-test
  (testing "default-strategies returns ordered list"
    (let [strategies (loop/default-strategies)]
      (is (seq strategies))
      (is (= 3 (count strategies))))))

(deftest repair-result-helpers-test
  (testing "repair-success creates success result"
    (let [result (loop/repair-success :llm-fix (valid-artifact))]
      (is (:success? result))
      (is (some? (:artifact result)))))

  (testing "repair-failure creates failure result"
    (let [result (loop/repair-failure :llm-fix
                                      [{:code :err :message "Error"}])]
      (is (not (:success? result)))
      (is (seq (:errors result))))))

;------------------------------------------------------------------------------ Layer 1
;; Outer loop API tests (stubs)

(deftest outer-loop-api-test
  (let [spec {:spec/id (random-uuid)
              :description "Test spec"}]
    (testing "create-outer-loop"
      (let [outer (loop/create-outer-loop spec)]
        (is (uuid? (:loop/id outer)))
        (is (= :outer (:loop/type outer)))
        (is (= :spec (:loop/phase outer)))))

    (testing "get-current-phase"
      (let [outer (loop/create-outer-loop spec)]
        (is (= :spec (loop/get-current-phase outer)))))

    (testing "advance-phase"
      (let [outer (loop/create-outer-loop spec)
            advanced (loop/advance-phase outer {})]
        (is (= :plan (loop/get-current-phase advanced)))))

    (testing "rollback-phase"
      (let [outer (-> (loop/create-outer-loop spec)
                      (loop/advance-phase {})
                      (loop/advance-phase {}))
            rolled-back (loop/rollback-phase outer :plan {})]
        (is (= :plan (loop/get-current-phase rolled-back)))))

    (testing "phases constant"
      (is (= [:spec :plan :design :implement :verify :review :release :observe]
             loop/phases)))

    (testing "get-phase-definition"
      (let [def (loop/get-phase-definition :implement)]
        (is (= :implement (:phase/id def)))
        (is (= :implementer (:phase/agent def)))))))

;------------------------------------------------------------------------------ Layer 2
;; Integration tests

(deftest step-by-step-control-test
  (testing "manual step execution"
    (let [loop-state (loop/create-inner-loop test-task {})
          generate-fn (make-generate-fn (valid-artifact))
          gates (loop/minimal-gates)

          ;; Generate
          after-generate (loop/generate-step loop-state generate-fn {})
          _ (is (= :validating (:loop/state after-generate)))

          ;; Validate
          after-validate (loop/validate-step after-generate gates {})]
      (is (= :complete (:loop/state after-validate))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.loop.interface-test)

  :leave-this-here)
