;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-validation
  "Require a complete verified seal before presenting or exporting evidence."
  (:require [ai.miniforge.evidence-bundle.canonical-validation :as canonical]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} validate [bundle]
  (let [report (canonical/validate-with-exception-handling bundle)]
    (if (and (map? bundle) (contains? bundle :evidence/content-hash))
      report
      (-> report
          (assoc :valid? false)
          (update :errors conj {:code :unsealed-evidence})))))

(comment
  (validate {}))
