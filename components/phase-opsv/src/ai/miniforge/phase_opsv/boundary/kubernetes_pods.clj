;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-pods
  "Admit one complete explicit Pod selection before deriving any further reads."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-list-reads :as lists]
            [ai.miniforge.phase-opsv.kubernetes-dependency-schema :as dependencies]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as requests]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read! [settings until]
  (lists/read! settings until [(requests/pods-path (:kubernetes/namespace settings)) dependencies/PodList]))

(comment ::complete-target-pod-selection)
