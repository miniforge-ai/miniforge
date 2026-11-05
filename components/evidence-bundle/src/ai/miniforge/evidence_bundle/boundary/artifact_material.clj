;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.boundary.artifact-material
  "Bound both incoming material and the complete protected envelope."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.artifact-material :as material]
            [ai.miniforge.evidence-bundle.opsv-diagnostics :as diagnostics]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} admitted [content]
  (if (redaction/supported? content) {:material/content content} (diagnostics/unsupported-material)))

(defn- ^{:stratum 0} prepared [admitted]
  (let [value (material/prepare (:material/content admitted))]
    (if (redaction/supported? value) value (diagnostics/unsupported-material))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [content]
  ;; Wrap admission so an anomaly-shaped artifact payload remains data.
  (anomaly/let-ok [admitted (admitted content)] (prepared admitted)))
