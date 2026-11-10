;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-forward-operation
  (:require [ai.miniforge.phase-opsv.kubernetes-forward-runtime-schema :as runtime]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} definition
  [input-schema & {:keys [effect-id readiness prepare native material observation-key material-observation-key]
                   :or {material-observation-key :load/observation}}]
  (let [arguments [:tuple runtime/HostRuntime input-schema]
        validator (m/validator arguments)]
    {:valid-arguments? validator
     :effect-id effect-id
     :readiness readiness
     :prepare prepare
     :native native
     :material material
     :observation-key observation-key
     :material-observation-key material-observation-key}))

(comment ::code-owned-operation-vocabulary-not-serialized-execution-authority)
