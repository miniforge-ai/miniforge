;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.connector-retry.circuit-breaker
  (:require
   [ai.miniforge.connector-retry.circuit-breaker-config :as config]
   [ai.miniforge.fsm.interface :as fsm]))

(def default-config
  "Default circuit breaker configuration. Override via create-circuit-breaker opts."
  (config/default-config))

(def ^:private breaker-machine-config
  "Circuit breaker state machine configuration."
  (config/machine-config))

(def ^:private breaker-machine
  "Compiled circuit breaker machine."
  (fsm/define-machine breaker-machine-config))

(defn- transition-state
  "Apply a circuit breaker state transition."
  [breaker event]
  (let [current-state (:breaker/state breaker)
        state {:_state current-state}
        transitioned (fsm/transition breaker-machine state event)
        next-state (fsm/current-state transitioned)]
    (assoc breaker :breaker/state next-state)))

(defn- reset-timeout-elapsed?
  "Check whether an open breaker may move to half-open."
  [{:breaker/keys [last-failure-at reset-timeout-ms]} now-ms]
  (and last-failure-at
       (>= (- now-ms last-failure-at) reset-timeout-ms)))

(defn- normalize-breaker-state
  "Project timeout-driven transitions into persisted breaker state."
  [breaker now-ms]
  (if (and (= :open (:breaker/state breaker))
           (reset-timeout-elapsed? breaker now-ms))
    (transition-state breaker :reset-timeout-elapsed)
    breaker))

(defn- threshold-reached?
  "Check whether the failure threshold is reached."
  [failure-count failure-threshold]
  (>= failure-count failure-threshold))

(defn create-circuit-breaker
  "Create initial circuit breaker state.
   Merges provided opts over default-config."
  [opts]
  (let [{:breaker/keys [failure-threshold reset-timeout-ms]}
        (merge default-config opts)]
    {:breaker/state :closed
     :breaker/failure-count 0
     :breaker/failure-threshold failure-threshold
     :breaker/reset-timeout-ms reset-timeout-ms
     :breaker/last-failure-at nil
     :breaker/last-success-at nil}))

(defn breaker-state
  "Return effective breaker state, considering timeout for half-open transition."
  [{:breaker/keys [state last-failure-at reset-timeout-ms]} now-ms]
  (let [normalized (normalize-breaker-state {:breaker/state state
                                             :breaker/last-failure-at last-failure-at
                                             :breaker/reset-timeout-ms reset-timeout-ms}
                                            now-ms)]
    (:breaker/state normalized)))

(defn allow-request?
  "Returns true if the breaker allows a request."
  [breaker now-ms]
  (not= :open (breaker-state breaker now-ms)))

(defn record-success
  "Record a successful operation."
  [breaker]
  (let [now-ms (System/currentTimeMillis)
        normalized (normalize-breaker-state breaker now-ms)
        transitioned (transition-state normalized :request-succeeded)]
    (assoc transitioned
           :breaker/failure-count 0
           :breaker/last-success-at now-ms)))

(defn record-failure
  "Record a failed operation. Opens breaker if threshold exceeded."
  [{:breaker/keys [failure-count failure-threshold] :as breaker}]
  (let [now-ms (System/currentTimeMillis)
        normalized (normalize-breaker-state breaker now-ms)
        current-state (:breaker/state normalized)
        new-count (if (= :half-open current-state) failure-threshold (inc failure-count))
        event (if (and (= :closed current-state)
                       (threshold-reached? new-count failure-threshold))
                :failure-threshold-reached
                :request-failed)
        transitioned (transition-state normalized event)]
    (assoc transitioned
           :breaker/failure-count new-count
           :breaker/last-failure-at now-ms)))
