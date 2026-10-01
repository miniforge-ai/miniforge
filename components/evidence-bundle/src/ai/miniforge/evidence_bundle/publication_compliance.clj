;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-compliance
  "Scan and redact before sealing; derive required metadata from observed content."
  (:require [ai.miniforge.evidence-bundle.scanner :as scanner]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} findings [bundle]
  (update (scanner/scan-artifact bundle) :scan/findings into (:compliance/sensitive-findings bundle)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} accurate-declarations? [bundle]
  (let [scan (findings bundle)
        metadata (scanner/compliance-metadata scan)]
    (and (or (empty? (:scan/findings scan)) (true? (:compliance/sensitive-data bundle)))
         (or (not (:evidence/contains-pii? metadata)) (true? (:evidence/contains-pii? bundle))))))

(defn ^{:stratum 1} prepare [bundle]
  (let [scan (findings bundle)
        redacted (redaction/redact bundle)
        changed? (not (redaction/clean? bundle))
        sensitive? (boolean (or changed? (seq (:scan/findings scan)) (:compliance/sensitive-data bundle)))
        handling (if changed? :redacted (get bundle :compliance/pii-handling :none))]
    (-> redacted
        (merge (scanner/compliance-metadata scan))
        (assoc :compliance/sensitive-data sensitive? :compliance/pii-handling handling))))

(comment
  (prepare {}))
