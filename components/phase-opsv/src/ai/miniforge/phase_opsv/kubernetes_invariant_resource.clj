;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-invariant-resource
  (:require [ai.miniforge.phase-opsv.kubernetes-metadata-projection :as metadata]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} profile [value]
  (let [ownership (metadata/project (get value "metadata"))]
    {:resource/id (resource/identity-key value)
     :resource/configuration (resource/configuration value)
     :resource/ownership-and-lifecycle ownership
     :resource/runtime (resource/observed-runtime value)}))
