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
   actuation ran: the phase and the failure. Kept separate so the snapshot is
   never re-encoded (and never replaced by an encoding failure) to carry it.
   Only the OPSV lifecycle writes it; phase entry removes any caller value."
  :opsv/post-actuation-gate-failure)

(def ^{:stratum 0} GateFailureRecord
  [:map {:closed true}
   [:phase [:= :opsv/actuate]]
   [:failure [:map {:closed true}
              [:anomaly/type keyword?]
              [:anomaly/message string?]]]])

(defn- ^{:stratum 0} recoverable? [ctx phase-key output]
  (and (= :opsv/actuate phase-key)
       (contains? (:execution/opts ctx) :opsv/evidence-base)
       (not (anomaly/any-anomaly? output))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pending? [ctx]
  (contains? (:execution/input ctx) snapshot-key))

(defn ^{:stratum 1} gate-failure
  "The recorded gate failure, or nil unless the record is a valid failure."
  [ctx]
  (let [record (get-in ctx [:execution/input gate-failure-key])]
    (when (m/validate GateFailureRecord record)
      (:failure record))))

(defn ^{:stratum 1} without-gate-failure
  "Remove any gate-failure record a caller put in workflow input."
  [ctx]
  (if (map? (:execution/input ctx))
    (update ctx :execution/input dissoc gate-failure-key)
    ctx))

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
  (assoc-in ctx [:execution/input gate-failure-key] {:phase :opsv/actuate :failure failure}))

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
    (boolean (and (gate-failure ctx)
                  (some? encoded)
                  (not (anomaly/any-anomaly? encoded))))))

(defn ^{:stratum 2} restore
  "Restore the retained actuation output. A recorded gate failure becomes the
   output's :opsv/phase-failure, so recovery finalizes a failed actuation."
  [ctx]
  (let [restored (snapshot/restore-context ctx (get-in ctx [:execution/input snapshot-key]))
        failure (gate-failure ctx)]
    (if (and failure (not (anomaly/any-anomaly? restored)))
      (assoc-in restored [:opsv/recovered-actuation-output :opsv/phase-failure] failure)
      restored)))

(comment
  (pending? {}))
