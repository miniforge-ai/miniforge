;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.execution-error-handler
  "Retain caught exception provenance in summaries appended by its error handler.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retain-provenance [original first-new-index index record]
  (let [fields [:phase :message :data]
        same-exception? (= (select-keys original fields) (select-keys record fields))]
    (if (and (>= index first-new-index) same-exception? (not (:anomaly record)))
      (assoc record :anomaly (:anomaly original))
      record)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enrich-records [original first-new-index records]
  (vec (map-indexed (partial retain-provenance original first-new-index) records)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} invoke [handler ctx exception original]
  (let [first-new-index (count (:execution/errors ctx))
        handled (handler ctx exception)]
    (update handled :execution/errors (partial enrich-records original first-new-index))))

(comment ::same-invocation-error-provenance)
