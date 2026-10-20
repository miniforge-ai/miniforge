;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-terminal
  "Admit terminal publication before returning and checkpointing its outcome."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-terminal-failure :as failure]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} admit! [stream ctx skip-lifecycle?]
  (let [receipt (when-not skip-lifecycle? (events/publish-workflow-completed! stream ctx))]
    (if (anomaly/any-anomaly? receipt) (failure/recover! stream ctx receipt) ctx)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} finalize! [stream ctx skip-lifecycle? output-ctx-vol]
  ;; Fatal publication errors still leave cleanup the completed execution facts.
  (vreset! output-ctx-vol ctx)
  (let [admitted (admit! stream ctx skip-lifecycle?)]
    (vreset! output-ctx-vol admitted)
    (checkpoints/persist-execution-state! admitted)
    admitted))

(comment ::terminal-admission)
