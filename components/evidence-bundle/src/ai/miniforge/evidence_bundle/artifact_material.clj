;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.artifact-material
  "Protect material before its producer assigns an immutable artifact identity."
  (:require [ai.miniforge.evidence-bundle.publication-compliance :as compliance]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare [content]
  (let [protected (compliance/prepare {:artifact/content content})]
    {:artifact/content (:artifact/content protected)
     :artifact/metadata (assoc (dissoc protected :artifact/content)
                               :evidence/content-protection :evidence/scanned-redacted-v1)}))
