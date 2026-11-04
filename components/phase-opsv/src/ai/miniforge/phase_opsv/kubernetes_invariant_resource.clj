;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-invariant-resource
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private ownership-and-lifecycle
  "Native metadata outside desired labels/annotations that must remain drift-sensitive."
  ["ownerReferences" "finalizers" "deletionTimestamp" "deletionGracePeriodSeconds"])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} profile [value]
  {:resource/id (resource/identity-key value)
   :resource/configuration (resource/configuration value)
   :resource/ownership-and-lifecycle (select-keys (get value "metadata") ownership-and-lifecycle)
   :resource/runtime (resource/observed-runtime value)})
