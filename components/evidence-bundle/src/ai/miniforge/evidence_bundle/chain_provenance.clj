;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.chain-provenance
  "Reapply binding naming context when projecting retained or legacy execution results."
  (:require [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bindings [records]
  (mapv workflow/redact-chain-binding records))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} edge [record]
  (cond-> record
    (contains? record :edge/bindings) (update :edge/bindings bindings)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} edges [records] (mapv edge records))

(comment
  (bindings []))
