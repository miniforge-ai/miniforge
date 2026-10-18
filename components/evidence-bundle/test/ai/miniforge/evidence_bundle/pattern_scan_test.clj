;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.pattern-scan-test
  (:require [ai.miniforge.evidence-bundle.scanner :as scanner]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} beyond-print-depth 30)

(def ^{:stratum 0} beyond-print-width 1100)

(defn- ^{:stratum 0} nest [value _]
  [value])

(deftest ^{:stratum 0} separate-values-do-not-form-a-sensitive-string
  (doseq [value [["000" "00" "0000"] ["alice" "@" "example.test"]
                 "1000-00-0000" "000-00-00000"]]
    (is (empty? (:scan/findings (scanner/scan-artifact value))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} placements [text]
  [text
   (reduce nest text (range beyond-print-depth))
   (conj (vec (repeat beyond-print-width :safe)) text)
   {text :value}
   {(keyword text) :value}
   {(with-meta 'key {:note text}) :value}
   (with-meta [] {:note text})
   (with-meta [] (with-meta {} {:note text}))])

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} scans-original-values-beyond-print-bounds
  (doseq [[text type] [["000-00-0000" :ssn] ["employee_ssn_000-00-0000_suffix" :ssn]
                     ["alice@example.test" :email]]
          value (placements text)]
    (let [result (binding [*print-level* 1 *print-length* 1] (scanner/scan-artifact value))
          metadata (scanner/compliance-metadata result)]
      (is (= [{:finding/type type}] (:scan/findings result)))
      (is (true? (:evidence/contains-pii? metadata)))
      (is (not (str/includes? (pr-str result) text))))))

(deftest ^{:stratum 2} marker-survives-embedded-keys-and-metadata
  (doseq [text [(redaction/marker) (str "before_" (redaction/marker) "_after")]
          value (placements text)]
    (let [result (scanner/scan-artifact value)]
      (is (= [{:finding/type :redaction-marker}] (:scan/findings result)))
      (is (scanner/redaction-recorded? result))
      (is (scanner/protection-required? result))
      (is (nil? (:evidence/contains-pii? (scanner/compliance-metadata result)))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.pattern-scan-test))
