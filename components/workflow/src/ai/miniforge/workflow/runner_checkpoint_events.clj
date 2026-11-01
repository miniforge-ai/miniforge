;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-checkpoint-events
  "Project captured checkpoint receipts into N3 facts, without re-resolving aliases."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.workflow.fsm :as workflow-fsm]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure-fields [write-failure]
  (let [data (:anomaly/data write-failure)
        message (or (:anomaly/ex-message data) (:anomaly/message write-failure))
        class (failure/classify {:exception/class (:anomaly/ex-class data)
                                 :error/message message})]
    {:error/message message
     :failure/class class}))

(defn- ^{:stratum 0} machine-state [context]
  (let [state (workflow-fsm/current-state (:execution/fsm-state context))]
    (if (keyword? state) state (:execution/status context))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failed [stream context phase fields]
  (let [phase? (keyword? phase)
        label (if phase? phase (machine-state context))
        constructor (if phase? events/checkpoint-write-failed events/machine-snapshot-write-failed)]
    (constructor stream (:execution/id context) label fields)))

(defn ^{:stratum 1} written
  "Announce a phase file when present, otherwise the pre-phase machine snapshot.
   Paired storage identity is the store's captured value, not file ancestry."
  [stream context {:checkpoint/keys [location phase phase-path machine-snapshot-path]}]
  (let [path (str (fs/absolutize (or phase-path machine-snapshot-path)))
        label (if phase-path phase (machine-state context))
        constructor (if phase-path events/checkpoint-written events/machine-snapshot-written)]
    (constructor stream (:execution/id context) label path (fs/size path) location)))

(comment ::checkpoint-receipt-projection)
