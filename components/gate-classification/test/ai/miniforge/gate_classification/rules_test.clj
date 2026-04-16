(ns ai.miniforge.gate-classification.rules-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.gate-classification.interface :as iface]
            [ai.miniforge.gate-classification.rules :as rules]))

(def test-config
  (iface/default-config))

(defn- make-violation
  [category confidence & {:keys [severity doc-status rule-id]
                          :or {severity :warning doc-status :documented rule-id "API-001"}}]
  {:violation/id "test-v"
   :violation/rule-id rule-id
   :violation/message "Test violation"
   :violation/severity severity
   :violation/location {:file "test.cpp" :line 1}
   :classification/category category
   :classification/confidence confidence
   :classification/doc-status doc-status})

;; Predicates

(deftest test-predicates
  (testing "true-positive?"
    (is (true? (rules/true-positive? (make-violation :true-positive 0.9))))
    (is (false? (rules/true-positive? (make-violation :false-positive 0.9)))))
  (testing "false-positive?"
    (is (true? (rules/false-positive? (make-violation :false-positive 0.9))))
    (is (false? (rules/false-positive? (make-violation :true-positive 0.9)))))
  (testing "needs-investigation?"
    (is (true? (rules/needs-investigation? (make-violation :needs-investigation 0.5))))
    (is (false? (rules/needs-investigation? (make-violation :true-positive 0.9)))))
  (testing "documented?"
    (is (true? (rules/documented? (make-violation :false-positive 0.9 :doc-status :documented))))
    (is (false? (rules/documented? (make-violation :false-positive 0.9 :doc-status :undocumented))))))

;; Low confidence rule

(deftest test-low-confidence-rule
  (testing "Low confidence escalates to needs-investigation"
    (let [v (make-violation :true-positive 0.3)
          result (rules/apply-low-confidence-rule v test-config)]
      (is (= :needs-investigation (:classification/category result)))
      (is (= :low-confidence (:classification/rule-applied result)))))
  (testing "High confidence passes through"
    (let [v (make-violation :true-positive 0.9)
          result (rules/apply-low-confidence-rule v test-config)]
      (is (= :true-positive (:classification/category result)))
      (is (nil? (:classification/rule-applied result))))))

;; Documented FP rule

(deftest test-documented-fp-rule
  (testing "Documented FP with high confidence gets rule applied"
    (let [v (make-violation :false-positive 0.9 :doc-status :documented)
          result (rules/apply-documented-fp-rule v test-config)]
      (is (= :documented-false-positive (:classification/rule-applied result)))))
  (testing "Undocumented FP does not trigger"
    (let [v (make-violation :false-positive 0.9 :doc-status :undocumented)
          result (rules/apply-documented-fp-rule v test-config)]
      (is (nil? (:classification/rule-applied result))))))

;; True positive rule

(deftest test-true-positive-rule
  (testing "High confidence TP gets confirmed"
    (let [v (make-violation :true-positive 0.95)
          result (rules/apply-true-positive-rule v test-config)]
      (is (= :confirmed-true-positive (:classification/rule-applied result)))))
  (testing "Low confidence TP does not get confirmed"
    (let [v (make-violation :true-positive 0.6)
          result (rules/apply-true-positive-rule v test-config)]
      (is (nil? (:classification/rule-applied result))))))

;; Batch processing

(deftest test-apply-rules-to-all
  (testing "Batch rule application"
    (let [violations [(make-violation :true-positive 0.9 :severity :error)
                      (make-violation :false-positive 0.8 :doc-status :documented)
                      (make-violation :true-positive 0.3 :severity :warning)]
          {:keys [violations changes]} (rules/apply-rules-to-all violations test-config)]
      (is (= 3 (count violations)))
      (is (pos? (count changes))))))

;; Severity filtering

(deftest test-filter-unresolved-errors
  (testing "Filter true-positive errors"
    (let [violations [(make-violation :true-positive 0.9 :severity :error)
                      (make-violation :true-positive 0.9 :severity :warning)
                      (make-violation :false-positive 0.9 :severity :error)]
          result (rules/filter-unresolved-errors violations test-config)]
      (is (= 1 (count result)))
      (is (= :error (:violation/severity (first result)))))))
