;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-phase-step
  "Execute a phase and apply its persistence, retry, and backend-health policy."
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.dag-resilience :as resilience]
            [ai.miniforge.workflow.execution :as exec]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-environment :as env]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.stop-signal :as stop-signal]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private inline-rate-limit-pattern
  "Permissive noun-form fallback (`rate limit`, `429`, `hit your
   limit`, `quota exceeded`) the central error-patterns table doesn't
   carry — those patterns are vendor-scoped or anchored on verbs
   (`exceeded`, `reached`). Both matchers union so the workflow-phase
   path catches everything either side knows."
  #"(?i)rate.?limit|429|hit your limit|quota.?exceeded")

(defn- ^{:stratum 0} backoff-ms
  "Exponential backoff duration for retry iteration, capped."
  [retry-count]
  (let [raw (* (defaults/backoff-base-ms) (Math/pow 2 (dec retry-count)))]
    (long (min (defaults/max-backoff-ms) raw))))

(defn- ^{:stratum 0} apply-rate-limit-failure
  "Mark context as failed due to rate limiting."
  [phase-ctx error-msg]
  (-> phase-ctx
      (ctx/transition-to-failed)
      (update :execution/errors conj (outcome/make-execution-error :rate-limited error-msg))))

(defn- ^{:stratum 0} apply-health-switch
  "Check backend health and apply switch result if needed."
  [phase-ctx]
  (let [event-stream (get-in phase-ctx [:execution/opts :event-stream])
        switch-result (events/check-backend-health-at-boundary! event-stream phase-ctx)]
    (cond-> phase-ctx
      switch-result (assoc :self-healing/backend-switch switch-result))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} rate-limited?
  "True when a phase error message indicates API rate limiting.

   Union of two matchers:
   - `dag-resilience/rate-limit-in-text?` — loads per-provider patterns
     from `error-patterns/external.edn`, so the workflow-phase path
     catches the same shapes the DAG-batch resilience layer does
     (Anthropic 529, OpenAI 503 / `model overloaded`, free-text
     throttle prose, etc.).
   - `inline-rate-limit-pattern` — permissive noun-form fallback.

   Without this matcher unification a 529 in mid-implement would fall
   through to plain backoff instead of `apply-rate-limit-failure`
   (which writes a typed terminal so the workflow can be resumed
   cleanly via `mf resume`)."
  [error-msg]
  (let [s (str error-msg)]
    (boolean (or (re-find inline-rate-limit-pattern s)
                 (resilience/rate-limit-in-text? s)))))

(defn- ^{:stratum 1} apply-backoff-if-retrying!
  "Sleep with exponential backoff when a phase is being retried."
  [phase-ctx _context]
  (let [current-phase (:execution/current-phase phase-ctx)
        retrying? (phase/retrying?
                   (get-in phase-ctx [:execution/phase-results current-phase]))
        retry-count (get-in phase-ctx [:phase :iterations] 1)]
    (when (and retrying? (> retry-count 1))
      (Thread/sleep (backoff-ms retry-count)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-phase
  "Execute one phase; persist workspace before applying retry or health policy.

   A cancel (`control-state` stopped) while the phase step runs interrupts
   it and the agent CLI it waits on. An interruption that escapes the step
   (a blocking phase callback, outside the interceptor's error handler)
   leaves the context as it came in, for the runner to record the stop.
   Workspace persistence and the retry and health policy after it run
   uninterrupted, and a stopped run skips the retry backoff."
  ([pipeline context callbacks]
   (execute-phase pipeline context callbacks nil))
  ([pipeline context callbacks control-state]
   (let [phase-context (-> (try+
                             (stop-signal/interrupting-on-stop
                              control-state
                              #(exec/execute-phase-step pipeline context callbacks
                                                        ctx/merge-metrics
                                                        ctx/transition-to-completed
                                                        ctx/transition-to-failed))
                             (catch Throwable t
                               (if (stop-signal/stop-caused? control-state t) context (throw t))))
                           (monitoring/clear-transient-state))
         _ (env/persist-workspace-at-phase-boundary! context phase-context)
         error-message (get-in phase-context
                               [:execution/phase-results
                                (:execution/current-phase phase-context)
                                :error :message] "")]
     (if (rate-limited? error-message)
       (apply-rate-limit-failure phase-context error-message)
       (do (when-not (some-> control-state deref :stopped)
             (apply-backoff-if-retrying! phase-context context))
           (apply-health-switch phase-context))))))

(comment ::phase-boundary-policy)
