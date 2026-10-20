;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-terminal
  "Admit terminal publication before returning and checkpointing its outcome."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.state-publication :as publication]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused [ctx failure]
  (let [message (:message (response/anomaly->event-data failure))
        error (outcome/make-execution-error :workflow-terminal-publication-failed
                                            message {:anomaly failure})]
    (-> ctx
        (update :execution/errors conj error)
        (context/transition-execution publication/refusal-event)
        outcome/extract-output)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit! [stream ctx skip-lifecycle?]
  (let [receipt (when-not skip-lifecycle? (events/publish-workflow-completed! stream ctx))]
    (if (anomaly/any-anomaly? receipt) (refused ctx receipt) ctx)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalize! [stream ctx skip-lifecycle? output-ctx-vol]
  ;; Fatal publication errors still leave cleanup the completed execution facts.
  (vreset! output-ctx-vol ctx)
  (let [admitted (admit! stream ctx skip-lifecycle?)]
    (vreset! output-ctx-vol admitted)
    (checkpoints/persist-execution-state! admitted)
    admitted))

(comment ::terminal-admission)
