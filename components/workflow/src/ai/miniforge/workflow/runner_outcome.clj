;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-outcome
  "Project pipeline failures, completion warnings, and output evidence."
  (:require [ai.miniforge.dag-executor.interface :as dag-exec]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} make-execution-error
  "Build a canonical execution error map."
  [error-type msg & [extra]]
  (cond-> {:type error-type :message msg}
    extra (merge extra)))

(defn ^{:stratum 0} terminal-state?
  "Check if workflow is in terminal state."
  [context]
  (or (phase/succeeded? context) (phase/failed? context)))

(defn- ^{:stratum 0} resolve-runtime-class
  "Return a TaskExecutor runtime class; absent or unsupported values have no class."
  [executor]
  (when (satisfies? dag-exec/TaskExecutor executor)
    (dag-exec/executor-type executor)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} pipeline-failure
  [context error-type category message data]
  (-> context
      (update :execution/errors conj (make-execution-error error-type message))
      (update :execution/response-chain
              response/add-failure :pipeline category (assoc data :error message))
      (ctx/transition-to-failed)))

(defn ^{:stratum 1} extract-output
  "Synthesize :execution/output from completed pipeline context.
   Includes N11 §9.1 evidence fields."
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        current-phase (ctx/active-or-last-phase ctx)
        image-digest  (get-in ctx [:execution/environment-metadata :image-digest])]
    (assoc ctx :execution/output
           (cond-> {:artifacts              (:execution/artifacts ctx)
                    :phase-results          phase-results
                    :last-phase-result      (get phase-results current-phase)
                    :status                 (:execution/status ctx)
                    :evidence/execution-mode  (get ctx :execution/mode :local)
                    :evidence/runtime-class   (resolve-runtime-class (:execution/executor ctx))
                    :evidence/task-started-at (:execution/started-at ctx)
                    :evidence/task-finished-at (java.time.Instant/now)}
             image-digest (assoc :evidence/image-digest image-digest)))))

(defn ^{:stratum 1} initial-context-anomaly->failed-context
  [workflow input opts context-anomaly]
  (-> (ctx/create-context workflow input opts)
      (update :execution/errors conj
              (make-execution-error :initial-context-anomaly
                                    (:anomaly/message context-anomaly)
                                    {:data (:anomaly/data context-anomaly)
                                     :anomaly context-anomaly}))
      (ctx/transition-to-failed)))

(defn ^{:stratum 1} validate-completion
  "Warn when a succeeded workflow produced no results."
  [final-ctx]
  (if (and (phase/succeeded? final-ctx)
           (empty? (:execution/artifacts final-ctx))
           (empty? (:execution/phase-results final-ctx)))
    (let [ctx' (-> final-ctx
                   (update :execution/errors conj
                           (make-execution-error :empty-completion
                                                (messages/t :status/empty-completion))))]
      (if (:execution/fsm-machine ctx')
        (-> ctx'
            (assoc :execution/completed-with-warnings? true)
            (ctx/sync-machine-projections))
        (assoc ctx' :execution/status :completed-with-warnings)))
    final-ctx))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} handle-empty-pipeline
  "Handle error case of empty pipeline."
  [context]
  (pipeline-failure context :empty-pipeline :anomalies.workflow/empty-pipeline
                    (messages/t :status/no-phases) {}))

(defn ^{:stratum 2} handle-max-phases-exceeded
  "Handle error case of max phases exceeded."
  [context max-phases]
  (pipeline-failure context :max-phases-exceeded :anomalies.workflow/max-phases
                    (messages/t :status/max-phases {:max-phases max-phases})
                    {:max-phases max-phases}))

(defn ^{:stratum 2} handle-phase-retry-exhausted
  "Runaway guard: the same phase has run in too many consecutive pipeline
   iterations (in-place retries — e.g. implement re-entering on every agent
   stall). Fail the workflow loud with a typed anomaly so the loop stops now
   instead of grinding up to `max-phases` iterations (hours of tokens)."
  [context phase retries]
  (pipeline-failure context :phase-retries-exhausted :anomalies.workflow/phase-retries-exhausted
                    (messages/t :status/phase-retries-exhausted
                                {:phase (name phase) :retries retries})
                    {:phase phase :retries retries}))

(comment ::runner-outcome)
