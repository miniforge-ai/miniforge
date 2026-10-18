;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.server.boundary.cleanup
  "Preserve all library failures at the throwing HTTP lifecycle boundary.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attempt! [failure release!]
  (try
    (release!)
    failure
    (catch Throwable error
      (when (and failure (not (identical? failure error)))
        (.addSuppressed failure error))
      (or failure error))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} complete! [failure releases]
  (when-let [error (reduce attempt! failure releases)]
    (throw error)))
