;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.artifact-material
  "Protect material before its producer assigns an immutable artifact identity."
  (:require [ai.miniforge.evidence-bundle.publication-compliance :as compliance]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} protection-version :evidence/scanned-redacted-v1)

(def ^{:stratum 0} Metadata
  [:map {:closed true}
   [:compliance/sensitive-data :boolean]
   [:compliance/pii-handling [:enum :none :redacted :encrypted]]
   [:compliance/sensitive-findings {:optional true} [:vector [:map {:closed true} [:finding/type :keyword]]]]
   [:evidence/contains-pii? {:optional true} :boolean]
   [:evidence/content-protection :keyword]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Content
  [:map {:closed true} [:artifact/content :any] [:artifact/metadata Metadata]])

(defn ^{:stratum 1} protected? [value]
  (and (= protection-version (get-in value [:artifact/metadata :evidence/content-protection]))
       (redaction/clean? value)
       (compliance/accurate-declarations? (merge (:artifact/metadata value)
                                                (select-keys value [:artifact/content])))))

(defn ^{:stratum 1} prepare [content]
  (let [protected (compliance/prepare {:artifact/content content})]
    {:artifact/content (:artifact/content protected)
     :artifact/metadata (assoc (dissoc protected :artifact/content)
                               :evidence/content-protection protection-version)}))
