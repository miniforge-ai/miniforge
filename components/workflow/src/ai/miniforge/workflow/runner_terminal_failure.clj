;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-terminal-failure
  "Retain terminal refusal and use the failure journal path once when available."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.state-publication :as publication]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} publication-error [error-type failure]
  (let [message (:message (response/anomaly->event-data failure))]
    (outcome/make-execution-error error-type message {:anomaly failure})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed-context [ctx failure]
  (-> ctx
      (update :execution/errors conj
              (publication-error :workflow-terminal-publication-failed failure))
      (context/transition-execution publication/refusal-event)
      outcome/extract-output))

(defn- ^{:stratum 1} retain-failure-refusal [ctx receipt]
  (if (anomaly/any-anomaly? receipt)
    (update ctx :execution/errors conj
            (publication-error :workflow-failure-publication-failed receipt))
    ctx))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} recover! [stream ctx refusal]
  (let [failed (failed-context ctx refusal)
        receipt (when-not (phase/failed? ctx)
                  (events/publish-workflow-completed! stream failed))]
    (retain-failure-refusal failed receipt)))

(comment ::bounded-failure-publication)
