;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-inventory-observation)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} roots-unchanged? [before after]
  (every? (fn [key] (= (get before key) (get after key)))
          [:kubernetes/namespace :kubernetes/deployment]))

(defn ^{:stratum 0} latest [captured]
  (merge (:inventory/observed captured) (:inventory/confirmed-roots captured)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} original [captured]
  (when (roots-unchanged? (:inventory/observed captured) (:inventory/confirmed-roots captured))
    (:inventory/observed captured)))
