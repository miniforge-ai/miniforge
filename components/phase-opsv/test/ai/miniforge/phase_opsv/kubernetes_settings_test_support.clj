;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-settings-test-support
  (:require [ai.miniforge.phase-opsv.kubernetes-test-support :as captured]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} settings
  ([] (settings {}))
  ([overrides]
   (merge {:kubernetes/base-url "https://cluster.invalid"
           :kubernetes/cluster-id "staging-cluster"
           :kubernetes/namespace "staging"
           :kubernetes/pod-selector "app=service"
           :kubernetes/node-pool-label captured/pool-label}
          overrides)))

(comment ::explicit-kubernetes-target-fixtures)
