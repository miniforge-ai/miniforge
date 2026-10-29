;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-progress
  "Activation-local progress values. Sources are explicit substantive-work ports,
   never heartbeat deliveries. All monotonic times use the same millisecond clock.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private identity-fields [:workflow/id :workflow/phase :heartbeat/activation-id])

(defn ^{:stratum 0} tick [state] (update state :sequence inc))

(defn- ^{:stratum 0} elapsed [now then] (max 0 (- now then)))

(defn- ^{:stratum 0} coverage [sources]
  (if (and (set? sources) (seq sources)) sources :unknown))

(defn- ^{:stratum 0} progress-status [state]
  (cond
    (= :unknown (:sources state)) :unknown
    (zero? (:count state)) :none
    :else :observed))

(defn- ^{:stratum 0} advance-progress [state observation]
  (-> state
      (update :count inc)
      (assoc-in [:positions (:progress/source observation)] (:progress/sequence observation))
      (assoc :last-progress-ms (:progress/monotonic-ms observation)
             :last-progress-at (:progress/observed-at observation))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start
  "Start fresh coverage: a nonempty set of instrumented sources, or :unknown.
   The owner supplies a fresh activation UUID on every entry, including resume."
  [identity started-at monotonic-ms sources]
  (let [identity (select-keys identity identity-fields)
        sources (coverage sources)]
    {:identity identity
     :started-at started-at
     :started-ms monotonic-ms
     :last-progress-ms monotonic-ms
     :sources sources
     :positions {}
     :count 0
     :sequence 0}))

(defn- ^{:stratum 1} eligible? [state observation]
  (and (set? (:sources state))
       (contains? (:sources state) (:progress/source observation))
       (= (:identity state) (select-keys observation identity-fields))
       (> (:progress/sequence observation) (get (:positions state) (:progress/source observation) 0))
       (>= (:progress/monotonic-ms observation) (:last-progress-ms state))))

(defn- ^{:stratum 1} progress-fields [state monotonic-ms]
  (let [status (progress-status state)
        gap (elapsed monotonic-ms (:last-progress-ms state))]
    (cond-> {:phase/progress-status status}
      (not= :unknown status) (assoc :phase/progress-count (:count state) :phase/progress-gap-ms gap)
      (= :observed status) (assoc :phase/last-progress-at (:last-progress-at state)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe
  "Accept a matching substantive source's strictly increasing positive position.
   Positions are local to this activation. Unknown coverage cannot become known
   merely because one observation arrives. Stale observations leave state intact."
  [state observation]
  (if (eligible? state observation) (advance-progress state observation) state))

(defn ^{:stratum 2} payload [state observed-at monotonic-ms interval-ms]
  (let [duration (elapsed monotonic-ms (:started-ms state))]
    (merge (:identity state)
           {:phase/active-since (:started-at state)
            :heartbeat/sequence (:sequence state)
            :heartbeat/observed-at observed-at
            :heartbeat/interval-ms interval-ms
            :heartbeat/elapsed-ms duration}
           (progress-fields state monotonic-ms))))

(comment ::activation-local-progress)
