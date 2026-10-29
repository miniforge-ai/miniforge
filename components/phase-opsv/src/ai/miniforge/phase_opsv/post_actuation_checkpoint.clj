;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.post-actuation-checkpoint
  "Retain successful actuation output before attempting evidence publication."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.actuation-snapshot :as snapshot]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} snapshot-key :opsv/post-actuation-snapshot)

(def ^{:stratum 0} gate-failure-key
  "A small terminal record, beside the snapshot, of a gate failure after the
   actuation ran: the phase, the gate failure and, when the attempt's first
   publication had already failed, that error's type, message and phase. Kept
   separate so the snapshot is never re-encoded (and never replaced by an
   encoding failure) to carry it. Lifecycle-owned: only the attempt that ran
   the actuation writes it, a fresh run's admission drops a caller's value,
   and redirects, retries and checkpoint restores carry it unchanged."
  :opsv/post-actuation-gate-failure)

(def ^{:stratum 0} GateFailureRecord
  [:map {:closed true}
   [:phase [:= :opsv/actuate]]
   [:failure [:map {:closed true}
              [:anomaly/type keyword?]
              [:anomaly/message string?]]]
   [:publication-failure {:optional true}
    [:map {:closed true}
     [:anomaly/type keyword?]
     [:anomaly/message string?]
     [:phase keyword?]]]])

(defn ^{:stratum 0} phase-failure
  "The failure a recorded attempt is finalized with: its publication error
   when one came first (the live run's phase error), else the gate failure."
  [record]
  (if-let [publication (:publication-failure record)]
    (select-keys publication [:anomaly/type :anomaly/message])
    (:failure record)))

(defn- ^{:stratum 0} recoverable? [ctx phase-key output]
  (and (= :opsv/actuate phase-key)
       (contains? (:execution/opts ctx) :opsv/evidence-base)
       (not (anomaly/any-anomaly? output))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pending? [ctx]
  (contains? (:execution/input ctx) snapshot-key))

(defn ^{:stratum 1} gate-failure-record
  "The recorded gate failure, or nil unless the record is valid."
  [ctx]
  (let [record (get-in ctx [:execution/input gate-failure-key])]
    (when (m/validate GateFailureRecord record)
      record)))

(defn ^{:stratum 1} capture [ctx phase-key output]
  (if (recoverable? ctx phase-key output)
    (assoc-in ctx [:execution/input snapshot-key] (snapshot/encode ctx output))
    ctx))

(defn ^{:stratum 1} retain-events [ctx phase-key output]
  (if (recoverable? ctx phase-key output)
    (events/retain-actuation-events ctx output)
    output))

(defn ^{:stratum 1} record-gate-failure
  "Record that the actuation's gate failed, beside its publication error when
   the attempt's first publication had already failed, for recovery to read."
  [ctx failure publication-failure]
  (assoc-in ctx [:execution/input gate-failure-key]
            (cond-> {:phase :opsv/actuate :failure failure}
              publication-failure (assoc :publication-failure publication-failure))))

(defn ^{:stratum 1} encoding-failure [ctx output]
  (let [encoded (get-in ctx [:execution/input snapshot-key])]
    (when (anomaly/anomaly? encoded)
      (assoc-in encoded [:anomaly/data :opsv/phase-output] output))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} gate-failure-recoverable?
  "True when a valid gate failure is recorded beside a usable (encoded, not
   failed) post-actuation snapshot: recovery can then finalize the failed
   actuation from the snapshot and the gate record alone."
  [ctx]
  (let [encoded (get-in ctx [:execution/input snapshot-key])]
    (boolean (and (gate-failure-record ctx)
                  (some? encoded)
                  (not (anomaly/any-anomaly? encoded))))))

(defn ^{:stratum 2} restore
  "Restore the retained actuation output. A recorded gate failure makes the
   output's :opsv/phase-failure the recorded attempt's failure, so recovery
   finalizes a failed actuation with the live run's error."
  [ctx]
  (let [restored (snapshot/restore-context ctx (get-in ctx [:execution/input snapshot-key]))
        record (gate-failure-record ctx)]
    (if (and record (not (anomaly/any-anomaly? restored)))
      (assoc-in restored [:opsv/recovered-actuation-output :opsv/phase-failure]
                (phase-failure record))
      restored)))

(comment
  (pending? {}))
