(ns ai.miniforge.gate.interface-test
  "Tests for the gate component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.response.interface :as response]
   ;; Require implementations to register methods
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]))

;; ============================================================================
;; Registry tests
;; ============================================================================

(deftest get-gate-syntax-test
  (testing "get-gate returns gate for :syntax"
    (let [g (gate/get-gate :syntax)]
      (is (map? g))
      (is (= :syntax (:name g)))
      (is (fn? (:check g))))))

(deftest get-gate-lint-test
  (testing "get-gate returns gate for :lint"
    (let [g (gate/get-gate :lint)]
      (is (map? g))
      (is (= :lint (:name g)))
      (is (fn? (:check g))))))

(deftest get-gate-tests-pass-test
  (testing "get-gate returns gate for :tests-pass"
    (let [g (gate/get-gate :tests-pass)]
      (is (map? g))
      (is (= :tests-pass (:name g)))
      (is (fn? (:check g))))))

(deftest get-gate-coverage-test
  (testing "get-gate returns gate for :coverage"
    (let [g (gate/get-gate :coverage)]
      (is (map? g))
      (is (= :coverage (:name g)))
      (is (fn? (:check g))))))

(deftest get-gate-no-secrets-test
  (testing "get-gate returns gate for :no-secrets"
    (let [g (gate/get-gate :no-secrets)]
      (is (map? g))
      (is (= :no-secrets (:name g)))
      (is (fn? (:check g))))))

(deftest get-gate-plan-complete-test
  (testing "get-gate returns gate for :plan-complete"
    (let [g (gate/get-gate :plan-complete)]
      (is (map? g))
      (is (= :plan-complete (:name g)))
      (is (fn? (:check g))))))

(deftest get-gate-unknown-test
  (testing "get-gate returns default for unknown gate"
    (let [g (gate/get-gate :unknown-gate)]
      (is (map? g))
      (is (= :unknown-gate (:name g)))
      (is (fn? (:check g)))
      ;; Default gate should pass
      (let [result ((:check g) {} {})]
        (is (:passed? result))))))

;; ============================================================================
;; Gate check tests
;; ============================================================================

(deftest check-gate-syntax-valid-test
  (testing "syntax gate passes for valid Clojure"
    (let [result (gate/check-gate :syntax {:content "(+ 1 2)"} {})]
      (is (:passed? result)))))

(deftest check-gate-syntax-invalid-test
  (testing "syntax gate fails for invalid Clojure"
    (let [result (gate/check-gate :syntax {:content "(+ 1 2"} {})]
      (is (not (:passed? result)))
      (is (seq (:errors result))))))

(deftest check-gate-no-secrets-clean-test
  (testing "no-secrets gate passes for clean content"
    (let [result (gate/check-gate :no-secrets {:content "(def x 42)"} {})]
      (is (:passed? result)))))

(deftest check-gate-no-secrets-detected-test
  (testing "no-secrets gate fails when secrets detected"
    ;; Pattern expects: password = "value" format
    (let [result (gate/check-gate :no-secrets
                                   {:content "password = \"mysecretpassword123\""}
                                   {})]
      (is (not (:passed? result))))))

(deftest check-gate-plan-complete-valid-test
  (testing "plan-complete gate passes for complete plan"
    (let [result (gate/check-gate :plan-complete
                                   {:plan {:steps [{:name "step1"}]}}
                                   {})]
      (is (:passed? result)))))

(deftest check-gate-plan-complete-empty-test
  (testing "plan-complete gate fails for empty plan"
    (let [result (gate/check-gate :plan-complete
                                   {:plan {:steps []}}
                                   {})]
      (is (not (:passed? result))))))

;; ============================================================================
;; Check gates (multiple) tests
;; ============================================================================

(deftest check-gates-all-pass-test
  (testing "check-gates returns passed when all gates pass"
    (let [result (gate/check-gates [:syntax]
                                    {:content "(+ 1 2)"}
                                    {})]
      (is (:all-passed? result))
      (is (empty? (:failed-gates result))))))

(deftest check-gates-one-fails-test
  (testing "check-gates returns failed when any gate fails"
    ;; Pattern expects: password = "value" format
    (let [result (gate/check-gates [:syntax :no-secrets]
                                    {:content "password = \"secretpassword123\""}
                                    {})]
      ;; Syntax passes but secrets fails
      (is (not (:all-passed? result)))
      (is (seq (:failed-gates result))))))

(deftest check-gates-empty-test
  (testing "check-gates passes for empty gate list"
    (let [result (gate/check-gates [] {} {})]
      (is (:all-passed? result)))))

;; ============================================================================
;; Repair gate tests
;; ============================================================================

(deftest repair-gate-no-repair-test
  (testing "repair-gate returns failure when gate has no repair function"
    (let [result (gate/repair-gate :no-secrets {:content "bad"} [] {})]
      (is (not (:success? result)))
      (is (= :no-secrets (:gate result))))))

;; ============================================================================
;; Response chain tests
;; ============================================================================

(deftest check-gates-chain-all-pass-test
  (testing "check-gates-chain succeeds when all gates pass"
    (let [chain (gate/check-gates-chain [:syntax]
                                         {:content "(+ 1 2)"}
                                         {})]
      (is (response/succeeded? chain))
      (is (= [:syntax] (response/operations chain))))))

(deftest check-gates-chain-one-fails-test
  (testing "check-gates-chain fails when any gate fails"
    (let [chain (gate/check-gates-chain [:syntax :no-secrets]
                                         {:content "password = \"secretpassword123\""}
                                         {})]
      ;; Syntax passes but secrets fails
      (is (response/failed? chain))
      (is (= :no-secrets (:operation (response/first-failure chain))))
      (is (= :anomalies.gate/validation-failed
             (:anomaly (response/first-failure chain)))))))

(deftest check-gates-chain-empty-test
  (testing "check-gates-chain succeeds for empty gate list"
    (let [chain (gate/check-gates-chain [] {} {})]
      (is (response/succeeded? chain))
      (is (empty? (response/operations chain))))))
