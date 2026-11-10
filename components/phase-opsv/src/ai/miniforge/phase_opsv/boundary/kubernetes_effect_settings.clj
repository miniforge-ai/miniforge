;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-effect-settings
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} capture-only-settings
  "Inventory limits do not configure a single-resource effect request."
  [:kubernetes/node-pool-label :kubernetes/max-resources])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} load! [provider]
  (settings/load-settings (apply dissoc provider capture-only-settings) native/Settings capture-only-settings))

(comment ::verification-provider-to-single-resource-effect-settings)
