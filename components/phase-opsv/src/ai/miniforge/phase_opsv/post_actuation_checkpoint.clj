;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.post-actuation-checkpoint
  "Retain successful actuation output before attempting evidence publication."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.actuation-snapshot :as snapshot]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} snapshot-key :opsv/post-actuation-snapshot)

(def ^{:stratum 0} gate-failure-key
  "A small record, beside the snapshot, of a gate failure after the actuation
   ran. Kept separate so the snapshot is never re-encoded (and never replaced
   by an encoding failure) to carry it."
  :opsv/post-actuation-gate-failure)

(defn- ^{:stratum 0} recoverable? [ctx phase-key output]
  (and (= :opsv/actuate phase-key)
       (contains? (:execution/opts ctx) :opsv/evidence-base)
       (not (anomaly/any-anomaly? output))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pending? [ctx]
  (contains? (:execution/input ctx) snapshot-key))

(defn ^{:stratum 1} gate-failure-recoverable?
  "True when a gate failure is recorded beside a usable (encoded, not failed)
   post-actuation snapshot: recovery can then finalize the failed actuation
   from the snapshot and the gate record alone."
  [ctx]
  (let [input (:execution/input ctx)
        encoded (get input snapshot-key)]
    (and (contains? input gate-failure-key)
         (some? encoded)
         (not (anomaly/any-anomaly? encoded)))))

(defn ^{:stratum 1} capture [ctx phase-key output]
  (if (recoverable? ctx phase-key output)
    (assoc-in ctx [:execution/input snapshot-key] (snapshot/encode ctx output))
    ctx))

(defn ^{:stratum 1} retain-events [ctx phase-key output]
  (if (recoverable? ctx phase-key output)
    (events/retain-actuation-events ctx output)
    output))

(defn ^{:stratum 1} record-gate-failure
  "Record that the actuation's gate failed, for recovery to read."
  [ctx failure]
  (assoc-in ctx [:execution/input gate-failure-key] failure))

(defn ^{:stratum 1} restore
  "Restore the retained actuation output. A recorded gate failure becomes the
   output's :opsv/phase-failure, so recovery finalizes a failed actuation."
  [ctx]
  (let [restored (snapshot/restore-context ctx (get-in ctx [:execution/input snapshot-key]))
        gate-failure (get-in ctx [:execution/input gate-failure-key])]
    (if (and gate-failure (not (anomaly/any-anomaly? restored)))
      (assoc-in restored [:opsv/recovered-actuation-output :opsv/phase-failure] gate-failure)
      restored)))

(defn ^{:stratum 1} encoding-failure [ctx output]
  (let [encoded (get-in ctx [:execution/input snapshot-key])]
    (when (anomaly/anomaly? encoded)
      (assoc-in encoded [:anomaly/data :opsv/phase-output] output))))

(comment
  (pending? {}))
