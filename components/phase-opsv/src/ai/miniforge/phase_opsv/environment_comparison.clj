;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison
  (:require [ai.miniforge.content-hash.interface :as hash]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} pin [plan]
  (let [digest (hash/content-hash plan)]
    {:comparison/contract plan
     :comparison/hash digest}))

(comment ::pure-environment-comparison)
