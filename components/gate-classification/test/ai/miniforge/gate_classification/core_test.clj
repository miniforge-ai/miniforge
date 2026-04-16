(ns ai.miniforge.gate-classification.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.gate-classification.interface :as iface]
            [ai.miniforge.loop.interface.protocols.gate :as gate-protocol]))

(defn- make-violation
  [id rule-id category confidence & {:keys [severity doc-status]
                                      :or {severity :warning doc-status :documented}}]
  {:violation/id id
   :violation/rule-id rule-id
   :violation/message (str "Test: " rule-id)
   :violation/severity severity
   :violation/location {:file "test.cpp" :line 1}
   :violation/source-tool "test"
   :violation/raw {}
   :classification/category category
   :classification/confidence confidence
   :classification/doc-status doc-status})

(defn- artifact-with-violations
  [& violations]
  {:classified-violations (vec violations)})

(deftest test-gate-creation
  (testing "Create gate with defaults"
    (let [gate (iface/create-classification-gate)]
      (is (= :classification-gate (gate-protocol/gate-id gate)))
      (is (= :classification (gate-protocol/gate-type gate))))))

(deftest test-gate-passes-no-violations
  (testing "Gate passes with empty violations"
    (let [gate   (iface/create-classification-gate)
          result (gate-protocol/check gate {:classified-violations []} {})]
      (is (true? (:gate/passed? result)))
      (is (empty? (:gate/errors result))))))

(deftest test-gate-passes-only-false-positives
  (testing "Gate passes when all violations are false-positive"
    (let [gate   (iface/create-classification-gate)
          artifact (artifact-with-violations
                    (make-violation "v1" "API-002" :false-positive 0.9)
                    (make-violation "v2" "API-002" :false-positive 0.8))
          result (gate-protocol/check gate artifact {})]
      (is (true? (:gate/passed? result))))))

(deftest test-gate-fails-true-positive-error
  (testing "Gate fails with true-positive error severity"
    (let [gate   (iface/create-classification-gate)
          artifact (artifact-with-violations
                    (make-violation "v1" "API-001" :true-positive 0.9 :severity :error))
          result (gate-protocol/check gate artifact {})]
      (is (false? (:gate/passed? result)))
      (is (pos? (count (:gate/errors result)))))))

(deftest test-gate-warns-on-needs-investigation
  (testing "Gate emits warnings for needs-investigation"
    (let [gate   (iface/create-classification-gate)
          artifact (artifact-with-violations
                    (make-violation "v1" "API-003" :needs-investigation 0.4))
          result (gate-protocol/check gate artifact {})]
      (is (true? (:gate/passed? result)))
      (is (pos? (count (:gate/warnings result)))))))

(deftest test-low-confidence-reclassification
  (testing "Low confidence violations get reclassified to needs-investigation"
    (let [gate   (iface/create-classification-gate {:confidence-threshold 0.6})
          artifact (artifact-with-violations
                    (make-violation "v1" "API-001" :true-positive 0.3 :severity :error))
          result (gate-protocol/check gate artifact {})]
      ;; Should pass because low-confidence TP gets reclassified
      (is (true? (:gate/passed? result))))))

(deftest test-repair
  (testing "Repair reclassifies low-confidence violations"
    (let [gate    (iface/create-classification-gate {:confidence-threshold 0.6})
          artifact (artifact-with-violations
                    (make-violation "v1" "API-001" :true-positive 0.3 :severity :error)
                    (make-violation "v2" "API-002" :false-positive 0.9))
          result  (gate-protocol/repair gate artifact [] {})]
      (is (:repaired? result))
      (is (pos? (count (:changes result)))))))
