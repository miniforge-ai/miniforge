;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-journal
  "Single-owner publication primitive. The adapter owns durable recovery and locking."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-boundary :as boundary]
            [ai.miniforge.event-stream.commit-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create
  "Use validated recovered state and one storage port (scope, candidate -> exact receipt).
   Never reopen uncertain storage with empty state; the adapter must recover it first."
  [state persist!]
  {:state (atom state)
   :persist! persist!})

(defn ^{:stratum 0} commit!
  "Serialize selection, durable acknowledgment and counter advancement.
   Callers must deliver only the returned committed value, after releasing this lock."
  [journal scope draft]
  (let [state (:state journal)]
    (locking state
      (let [snapshot @state
            candidate (model/candidate snapshot scope draft)]
        (cond
          (.isInterrupted (Thread/currentThread)) (model/failure :unavailable :commit/interrupted draft)
          (:failure snapshot) (model/failure :unavailable :commit/recovery-required draft)
          (:writing? snapshot) (model/failure :conflict :commit/reentrant draft)
          (anomaly/anomaly? candidate) candidate
          (model/recorded snapshot (:event/id draft)) candidate
          :else (boundary/persist-with-exception-handling! journal scope candidate))))))

(comment
  ::commit-time-allocation)
