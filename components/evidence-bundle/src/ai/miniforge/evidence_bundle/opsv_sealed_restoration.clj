;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-sealed-restoration
  "Restore an already-published immutable seal after validating retained references."
  (:require [ai.miniforge.evidence-bundle.opsv-assembly :as assembly]
            [ai.miniforge.evidence-bundle.opsv-diagnostics :as diagnostics]
            [ai.miniforge.evidence-bundle.opsv-finalization-publication :as publication]
            [ai.miniforge.evidence-bundle.opsv-sealed-validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attempt! [store bundle available-ids]
  (let [id (:evidence-bundle/id bundle)
        record (assembly/get-assembly store id)
        finalized (assoc record :opsv.assembly/status :finalized)]
    (cond
      (not (validation/valid-with-exception-handling? finalized bundle available-ids))
      (diagnostics/failure :anomalies/incorrect :finalization/invalid id [{:code :invalid-retained-seal}])
      (= bundle (:opsv.assembly/bundle record)) bundle
      (not= :assembling (:opsv.assembly/status record)) (diagnostics/immutable id)
      :else (publication/publish-sealed! store record bundle))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restore! [store bundle available-ids]
  (loop []
    (let [result (attempt! store bundle available-ids)]
      (if (= ::publication/retry result) (recur) result))))

(comment
  ::restore!)
