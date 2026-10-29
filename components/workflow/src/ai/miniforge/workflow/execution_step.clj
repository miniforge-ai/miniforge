;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.execution-step
  "Advance a selected phase only after its boundary callbacks accept the operation
   and its gate decision, when it has one, was acknowledged."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-dag :as dag]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.execution-transition :as transition]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invoke [callback & args]
  (when callback (apply callback args)))

(defn- ^{:stratum 0} active-context [context interceptor]
  (-> context
      (dissoc :execution/report-progress!)
      (assoc :execution/current-phase (get-in interceptor [:config :phase]))))

(defn- ^{:stratum 0} phase-context [callbacks context interceptor]
  (if-let [prepare (:phase-context callbacks)]
    (prepare context interceptor)
    context))

(defn- ^{:stratum 0} recorded-result
  "A phase whose gate decision was not acknowledged is recorded as failed, so a
   resume re-runs it. Its output and decision envelope are kept."
  [result]
  (if (:phase/decision-unacknowledged result)
    (-> result
        (assoc :status :failed)
        (update :phase/gate-errors (fnil conj [])
                {:code :gate/decision-unacknowledged
                 :message (messages/t :gate/decision-unacknowledged)}))
    result))

(defn- ^{:stratum 0} refused [context boundary failure fail]
  (let [message (:message (response/anomaly->event-data failure))
        data {:phase (:execution/current-phase context)
              :boundary boundary :anomaly failure}
        error (outcome/make-execution-error :phase-callback-failed message data)]
    (-> context (update :execution/errors conj error) fail)))

(defn- ^{:stratum 0} advance [pipeline context interceptor result ports]
  (let [phase (:execution/current-phase context)
        complete (:complete ports)
        fail (:fail ports)]
    (or (dag/try-dag-execution context phase result pipeline complete fail)
        (transition/apply-phase-transition
          context (transition/determine-phase-event (:config interceptor) result)
          pipeline complete fail))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} execute-entered
  "An unacknowledged gate decision refuses the boundary before the DAG handoff
   and the machine transition; the completion callback still observes the phase."
  [pipeline context callbacks interceptor ports]
  (let [entered (phase-context callbacks context interceptor)
        [updated lifecycle-result] (lifecycle/execute-phase-lifecycle interceptor entered)
        unacknowledged (:phase/decision-unacknowledged lifecycle-result)
        result (recorded-result lifecycle-result)
        phase (:execution/current-phase context)
        processed (lifecycle/process-phase-result updated phase result (:merge-metrics ports))
        receipt (invoke (:on-phase-complete callbacks) processed interceptor result)]
    (cond
      unacknowledged (refused processed :gate-decision unacknowledged (:fail ports))
      (anomaly/any-anomaly? receipt) (refused processed :on-phase-complete receipt (:fail ports))
      :else (advance pipeline processed interceptor result ports))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-selected
  "Nil/non-anomaly callback results retain legacy observer semantics.
   An anomaly refuses the boundary; no subsequent phase effect or transition runs."
  [pipeline context callbacks interceptor ports]
  (let [active (active-context context interceptor)
        receipt (invoke (:on-phase-start callbacks) active interceptor)]
    (if (anomaly/any-anomaly? receipt)
      (refused active :on-phase-start receipt (:fail ports))
      (execute-entered pipeline active callbacks interceptor ports))))

(comment ::acknowledged-phase-boundaries)
