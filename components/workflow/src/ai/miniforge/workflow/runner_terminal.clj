;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-terminal
  "Admit terminal publication before returning and checkpointing its outcome."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.boundary.terminal :as boundary]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-terminal-failure :as failure]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refuse! [stream ctx receipt output-ctx-vol]
  (let [failed (failure/failed-context ctx receipt)]
    (vreset! output-ctx-vol failed)
    (checkpoints/persist-execution-state! failed)
    (failure/publish! stream ctx failed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit! [stream ctx skip-lifecycle? output-ctx-vol]
  (let [receipt (when-not skip-lifecycle? (events/publish-workflow-completed! stream ctx))]
    (if (anomaly/any-anomaly? receipt) (refuse! stream ctx receipt output-ctx-vol) ctx)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalize! [stream ctx skip-lifecycle? output-ctx-vol]
  ;; Keep cleanup aligned with known execution facts before publication effects.
  (vreset! output-ctx-vol ctx)
  (let [admitted (boundary/with-failed-cleanup!
                 output-ctx-vol (partial admit! stream ctx skip-lifecycle? output-ctx-vol))]
    (vreset! output-ctx-vol admitted)
    (checkpoints/persist-execution-state! admitted)
    admitted))

(comment ::terminal-admission)
