;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-identity
  "Stable state identifiers and pipeline entries for execution topology.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-state-prefix "phase")

(def ^{:stratum 0} paused-phase-state-prefix "paused-phase")

(defn- ^{:stratum 0} matching-phase-index [phase [index config]]
  (when (= phase (:phase config)) index))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-state-id [index phase]
  (keyword (str phase-state-prefix "-" index "-" (name phase))))

(defn ^{:stratum 1} paused-phase-state-id [index phase]
  (keyword (str paused-phase-state-prefix "-" index "-" (name phase))))

(defn ^{:stratum 1} first-phase-index [pipeline phase]
  (some (partial matching-phase-index phase) (map-indexed vector pipeline)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} phase-entry [index config]
  (let [phase (:phase config)
        active (phase-state-id index phase)
        paused (paused-phase-state-id index phase)]
    {:index index
     :phase phase
     :config config
     :state-id active
     :paused-state-id paused}))

(comment ::stable-execution-state-identity)
