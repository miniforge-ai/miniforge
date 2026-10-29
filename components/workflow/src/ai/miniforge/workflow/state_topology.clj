;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-topology
  "Compose unresolved execution states before guard/action resolution."
  (:require [ai.miniforge.workflow.definition :as definition]
            [ai.miniforge.workflow.state-identity :as identity]
            [ai.miniforge.workflow.state-publication :as publication]
            [ai.miniforge.workflow.state-transitions :as transitions]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} lifecycle-states [entries]
  (let [first-state (some-> entries first :state-id)
        start-target (if (empty? entries) :running first-state)
        pending {:on {:fail :failed :cancel :cancelled :start start-target}}
        terminal {:type :final}]
    (cond-> {:pending pending
             :completed terminal
             :failed terminal
             :cancelled terminal}
      (empty? entries)
      (assoc :running {:on {:complete :completed
                           :fail :failed
                           :pause :paused
                           :cancel :cancelled}}
             :paused {:on {:resume :running
                          :cancel :cancelled}}))))

(defn- ^{:stratum 0} phase-states [entries]
  (into {} (map (partial transitions/phase-state entries)) entries))

(defn- ^{:stratum 0} paused-states [entries]
  (into {} (map transitions/paused-state) entries))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-raw-states [workflow]
  (let [pipeline (definition/execution-pipeline workflow)
        entries (mapv identity/phase-entry (range) pipeline)
        states (merge (lifecycle-states entries) (phase-states entries) (paused-states entries))
        publishable-states (publication/with-refusal-transitions states)]
    {:states publishable-states
     :phase-entries entries}))

(comment ::unresolved-execution-topology)
