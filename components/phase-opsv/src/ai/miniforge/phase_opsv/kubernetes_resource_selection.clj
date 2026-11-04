;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-resource-selection
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} target-resource? [namespace value]
  (= namespace (resource/namespace-of value)))

(defn- ^{:stratum 0} complete? [value]
  (let [continuation (get-in value ["metadata" "continue"])
        remaining (get-in value ["metadata" "remainingItemCount"])]
    (and (or (nil? continuation) (= "" continuation))
         (or (nil? remaining) (= 0 remaining)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid? [settings list-schema value]
  (let [items (get value "items")]
    (and (m/validate list-schema value)
         (complete? value)
         (<= (count items) (:kubernetes/max-resources settings))
         (every? (partial target-resource? (:kubernetes/namespace settings)) items))))

(comment ::one-complete-bounded-target-selection-before-derived-reads)
