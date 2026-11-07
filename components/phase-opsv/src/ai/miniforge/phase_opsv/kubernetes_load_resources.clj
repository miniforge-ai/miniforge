;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-resources
  (:require [ai.miniforge.phase-opsv.kubernetes-quantity :as quantity]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} quantity-paths
  "Only the fixed worker's CPU/memory resource bounds are quantities; argv and all other fields stay literal."
  (vec (for [bound ["requests" "limits"] resource ["cpu" "memory"]]
         ["spec" "containers" 0 "resources" bound resource])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} normalized [pod]
  (reduce (fn [result path] (update-in result path quantity/value)) pod quantity-paths))

(comment ::temporary-comparison-view-never-the-request-or-grant-payload)
