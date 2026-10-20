;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-start
  "Admit workflow execution through its lifecycle publication boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused [ctx failure]
  (let [message (:message (response/anomaly->event-data failure))
        error (outcome/make-execution-error :workflow-start-publication-failed message
                                            {:event/type :workflow/started :anomaly failure})]
    (-> ctx (update :execution/errors conj error) context/transition-to-failed)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admit! [stream ctx skip-lifecycle?]
  (when stream (supervisory/ensure-attached! stream))
  (checkpoints/persist-execution-state! ctx)
  (let [receipt (when-not skip-lifecycle? (events/publish-workflow-started! stream ctx))]
    (if (anomaly/any-anomaly? receipt) (refused ctx receipt) ctx)))

(comment ::workflow-start-admission)
