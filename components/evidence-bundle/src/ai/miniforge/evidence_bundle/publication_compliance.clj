;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-compliance
  "Pure compliance policy for bounded, canonical publication candidates."
  (:require [ai.miniforge.evidence-bundle.scanner :as scanner]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} findings [bundle]
  (update (scanner/scan-artifact bundle) :scan/findings into (:compliance/sensitive-findings bundle)))

(defn- ^{:stratum 0} protected-treatment? [bundle]
  (contains? #{:redacted :encrypted} (:compliance/pii-handling bundle)))

(defn- ^{:stratum 0} treatment [bundle scan]
  (cond
    (not (redaction/clean? bundle)) :redacted
    (= :encrypted (:compliance/pii-handling bundle)) :encrypted
    (scanner/protection-required? scan) :redacted
    :else (get bundle :compliance/pii-handling :none)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} accurate-declarations? [bundle]
  (let [scan (findings bundle)
        metadata (scanner/compliance-metadata scan)]
    (and (or (empty? (:scan/findings scan)) (true? (:compliance/sensitive-data bundle)))
         (or (not (:evidence/contains-pii? metadata)) (true? (:evidence/contains-pii? bundle)))
         (or (not (scanner/protection-required? scan)) (protected-treatment? bundle)))))

(defn ^{:stratum 1} prepare [bundle]
  (let [scan (findings bundle)
        sensitive? (boolean (or (seq (:scan/findings scan)) (:compliance/sensitive-data bundle)))
        handling (treatment bundle scan)]
    (-> bundle
        (merge (scanner/compliance-metadata scan))
        (assoc :compliance/sensitive-data sensitive? :compliance/pii-handling handling)
        redaction/redact)))

(comment
  (prepare {}))
