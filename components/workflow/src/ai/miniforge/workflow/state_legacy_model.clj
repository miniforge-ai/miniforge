;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-legacy-model
  "Single source of truth for the coarse lifecycle and its compatibility indexes."
  (:require [ai.miniforge.fsm.interface :as fsm]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-machine-config
  "Workflow execution state machine configuration."
  {:fsm/id :workflow-execution
   :fsm/initial :pending
   :fsm/context {}
   :fsm/states
   {:pending   {:on {:start :running}}
    :running   {:on {:complete :completed
                     :fail :failed
                     :pause :paused
                     :cancel :cancelled}}
    :paused    {:on {:resume :running
                     :cancel :cancelled}}
    :completed {:type :final}
    :failed    {:type :final}
    :cancelled {:type :final}}})

(defn- ^{:stratum 0} terminal-entry? [[_ config]]
  (= :final (:type config)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} workflow-machine (fsm/define-machine workflow-machine-config))

(def ^{:stratum 1} workflow-states (set (keys (:fsm/states workflow-machine-config))))

(def ^{:stratum 1} workflow-transitions
  (into {} (for [[from config] (:fsm/states workflow-machine-config)
                 [event to] (:on config)]
             [[from event] to])))

(def ^{:stratum 1} terminal-states
  (into #{} (comp (filter terminal-entry?) (map key)) (:fsm/states workflow-machine-config)))

(comment ::coarse-lifecycle-model)
