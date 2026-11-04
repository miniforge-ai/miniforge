;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.artifact-material-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} content-and-compliance-are-protected-together-test
  (doseq [content [{:password "fake-test-password"}
                   {:observation "DB_PASSWORD=fake-test-password"}
                   {:observation "123-45-6789"}
                   {:observation (redaction/marker)}]]
    (let [prepared (evidence/prepare-artifact-content content)]
      (is (redaction/clean? prepared))
      (is (= (redaction/redact content) (:artifact/content prepared)))
      (is (true? (get-in prepared [:artifact/metadata :compliance/sensitive-data])))
      (is (= :redacted (get-in prepared [:artifact/metadata :compliance/pii-handling])))
      (is (seq (get-in prepared [:artifact/metadata :compliance/sensitive-findings]))))))

(deftest ^{:stratum 0} retained-findings-are-verified-not-replaced-by-a-rescan-test
  (let [prepared (evidence/prepare-artifact-content {:password "fake-test-password"})
        rescanned (evidence/prepare-artifact-content (:artifact/content prepared))]
    (is (not= (:artifact/metadata prepared) (:artifact/metadata rescanned)))
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (evidence/valid-protected-artifact-content? rescanned))
    (doseq [changed [(assoc-in prepared [:artifact/content :password] "fake-new-password")
                     (assoc-in prepared [:artifact/metadata :compliance/sensitive-data] false)
                     (assoc-in prepared [:artifact/metadata :compliance/pii-handling] :none)
                     (assoc-in prepared [:artifact/metadata :evidence/content-protection] :unknown)
                     (assoc-in prepared [:artifact/metadata :unbound] true)]]
      (is (false? (evidence/valid-protected-artifact-content? changed))))))

(deftest ^{:stratum 0} clean-content-is-preserved-and-declared-test
  (let [content {:replicas 2 :token-name "reference-only"}
        prepared (evidence/prepare-artifact-content content)]
    (is (= content (:artifact/content prepared)))
    (is (false? (get-in prepared [:artifact/metadata :compliance/sensitive-data])))
    (is (= :none (get-in prepared [:artifact/metadata :compliance/pii-handling])))))

(deftest ^{:stratum 0} uninspectable-content-is-refused-without-echoing-it-test
  (doseq [content [(Object.) (lazy-seq (throw (ex-info "must not realize" {})))
                   (loop [depth 130 value nil]
                     (if (zero? depth) value (recur (dec depth) [value])))]]
    (let [result (evidence/prepare-artifact-content content)]
      (is (anomaly/any-anomaly? result))
      (is (redaction/clean? result))
      (is (= [{:code :unsupported-evidence-content}]
             (get-in result [:anomaly/data :opsv.validation/errors]))))))
