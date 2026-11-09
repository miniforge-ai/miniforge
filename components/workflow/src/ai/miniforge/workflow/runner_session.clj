;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-session
  "Prepare and execute a session inside an already-owned resource lifetime."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-execution-boundary :as execution]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.runner-preparation :as preparation]
            [ai.miniforge.workflow.runner-start :as start]
            [ai.miniforge.workflow.runner-terminal :as terminal]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create [callbacks build-pipeline extract-output]
  {:session/callbacks callbacks
   :session/build-pipeline build-pipeline
   :session/extract-output extract-output
   :session/output (volatile! nil)
   :session/exception (volatile! nil)})

(defn- ^{:stratum 0} initial-context [workflow input opts recurse]
  (let [result (preparation/build-initial-context workflow input opts recurse)]
    (if (anomaly/anomaly? result)
      (outcome/initial-context-anomaly->failed-context workflow input opts result)
      result)))

(defn- ^{:stratum 0} control-state [opts]
  (get opts :control-state (atom {:paused false :stopped false :adjustments {}})))

(defn- ^{:stratum 0} complete-output [session ctx]
  ((:session/extract-output session) (outcome/validate-completion ctx)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute! [workflow input opts session recurse]
  (let [pipeline ((:session/build-pipeline session) workflow)
        max-phases (get opts :max-phases (defaults/max-phases))
        control (control-state opts)
        ctx (initial-context workflow (preparation/admit-input pipeline input opts) opts recurse)
        stream (:event-stream opts)
        skip? (:skip-lifecycle-events opts)
        started (start/admit! stream ctx skip?)
        final (execution/execute-with-exception-handling
                pipeline started (:session/callbacks session) control
                max-phases (:session/exception session))
        output (complete-output session final)]
    (terminal/finalize! stream output skip? (:session/output session))))

(comment ::owned-session-execution)
