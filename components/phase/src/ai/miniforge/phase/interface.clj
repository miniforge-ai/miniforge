;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.phase.interface
  "Phase interceptor registry interface.

   Phases are interceptors with :enter, :leave, and :error functions.
   Each phase type registers via defmethod, providing defaults and behavior.

   Usage:
     (get-phase-interceptor {:phase :implement})
     (get-phase-interceptor {:phase :implement :budget {:tokens 50000}})

   Interceptor structure (Pedestal-style):
     {:name    keyword?
      :enter   (fn [ctx] -> ctx)    ; Execute phase
      :leave   (fn [ctx] -> ctx)    ; Post-processing
      :error   (fn [ctx ex] -> ctx) ; Error handling/repair}"
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.agent-behavior :as agent-behavior]
   ;; Load core terminal phase so `{:phase :done}` works in every runtime
   ;; that depends on the phase registry. The require is for side effects
   ;; (defmethod + register-phase-defaults!) — no symbols are referenced
   ;; from this namespace.
   [ai.miniforge.phase.done]
   [ai.miniforge.phase.file-context :as file-context]
   [ai.miniforge.phase.graph :as graph]
   [ai.miniforge.phase.graph-validator :as graph-validator]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase.messages :as messages]
   [ai.miniforge.phase.phase-result :as phase-result]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.phase.telemetry :as telemetry]))

;------------------------------------------------------------------------------ Layer 0
;; Phase-result pass-throughs

(def enter-context
  "Build canonical phase result context."
  phase-result/enter-context)

(def exception-error
  "Build error map from exception."
  phase-result/exception-error)

;------------------------------------------------------------------------------ Layer 0
;; Re-export registry functions

(defn ensure-phase-implementations-loaded!
  "Ensure configured phase implementations have been required."
  []
  (loader/ensure-phase-implementations-loaded!))

(def configured-phase-namespaces
  "Return the configured phase implementation namespaces discovered from resources."
  loader/configured-phase-namespaces)

(def reset-phase-loader!
  "Reset phase implementation loader state for tests."
  loader/reset-loader!)

(defn get-phase-interceptor
  "Get interceptor for a phase configuration.

   Arguments:
     config - Map with :phase keyword and optional overrides
              {:phase :implement :budget {:tokens 50000}}

   Returns:
     Interceptor map with :name, :enter, :leave, :error"
  [config]
  (ensure-phase-implementations-loaded!)
  (registry/get-phase-interceptor config))

(defn list-phases
  "List all registered phase types.

   Returns:
     Set of phase keywords"
  []
  (ensure-phase-implementations-loaded!)
  (registry/list-phases))

(defn phase-defaults
  "Get default configuration for a phase type.

   Arguments:
     phase-kw - Phase keyword like :plan, :implement

   Returns:
     Default config map or nil if unknown"
  [phase-kw]
  (ensure-phase-implementations-loaded!)
  (registry/phase-defaults phase-kw))

(def merge-with-defaults
  "Merge user config with phase defaults."
  registry/merge-with-defaults)

(def register-phase-defaults!
  "Register default configuration for a phase type."
  registry/register-phase-defaults!)

(def get-phase-interceptor-method
  "The raw multimethod for registering phase interceptor implementations.
   Use defmethod on this for new phase types."
  registry/get-phase-interceptor)

(def extract-status
  "Extract a status keyword from a map by trying known status keys."
  registry/extract-status)

(def succeeded?
  "Check if a result map indicates success."
  registry/succeeded?)

(def failed?
  "Check if a result map indicates failure."
  registry/failed?)

(def already-done?
  "Check if a result map indicates work was already complete (neutral outcome)."
  registry/already-done?)

(def succeeded-or-done?
  "Check if a result map indicates success or already-done (neutral)."
  registry/succeeded-or-done?)

(def retrying?
  "Check if a result map indicates retry."
  registry/retrying?)

(def transition-request
  "Return the transition request attached to a phase result, if any."
  phase-result/transition-request)

(def transition-target
  "Return the requested transition target phase, if any."
  phase-result/transition-target)

(def redirect-requested?
  "True when a phase result requests a redirect transition."
  phase-result/redirect-requested?)

(def create-streaming-callback
  "Create a phase-scoped streaming callback when an event stream is available."
  telemetry/create-streaming-callback)

(def resolve-event-stream
  "Locate the event-stream atom from an execution context.
   Checks :event-stream and :execution/event-stream keys."
  telemetry/resolve-event-stream)

(def emit-phase-started!
  "Emit a :workflow/phase-started event. Safe no-op when event-stream is absent."
  telemetry/emit-phase-started!)

(def emit-phase-completed!
  "Emit a :workflow/phase-completed event. Safe no-op when event-stream is absent."
  telemetry/emit-phase-completed!)

(def create-streaming-callback-or-noop
  "Create a phase-scoped streaming callback, or no-op if unavailable.
   Never returns nil — always returns a callable function."
  telemetry/create-streaming-callback-or-noop)

(def emit-agent-started!
  "Emit an :agent/started event when a phase begins agent execution."
  telemetry/emit-agent-started!)

(def emit-agent-completed!
  "Emit an :agent/completed event when a phase finishes agent execution."
  telemetry/emit-agent-completed!)

(def emit-milestone-started!
  "Emit a :phase/milestone-started event when a milestone checkpoint begins.
   Called automatically from emit-phase-started!; also available for direct use."
  telemetry/emit-milestone-started!)

(def emit-milestone-completed!
  "Emit a :phase/milestone-completed event when a milestone checkpoint succeeds.
   Called automatically from emit-phase-completed! on :success outcome."
  telemetry/emit-milestone-completed!)

(def emit-milestone-failed!
  "Emit a :phase/milestone-failed event when a milestone checkpoint fails.
   Called automatically from emit-phase-completed! on non-:success outcome."
  telemetry/emit-milestone-failed!)

(def determine-phase-status
  "Resolve the outcome status for a phase from its result and gate outputs."
  registry/determine-phase-status)

(def load-files-in-scope
  "Load file contents for a phase's configured in-scope paths."
  file-context/load-files-in-scope)

(def load-and-filter-behaviors
  "Load and filter rule-pack agent-behavior entries applicable to a phase."
  agent-behavior/load-and-filter-behaviors)

(def success
  "Build a successful environment-model phase result."
  phase-result/success)

(def error
  "Build an error environment-model phase result."
  phase-result/error)

(def skipped
  "Build a skipped-phase result for phases that short-circuit."
  phase-result/skipped)

(def fail-phase
  "Mark a phase state as failed and attach the error map."
  phase-result/fail-phase)

(def request-redirect
  "Attach a redirect transition request to a phase result."
  phase-result/request-redirect)

(def fail-and-request-redirect
  "Mark a phase state as failed and request a redirect transition."
  phase-result/fail-and-request-redirect)

(def test-metrics
  "Build a standard test-phase metrics map."
  phase-result/test-metrics)

(defn handle-error
  "Standard interceptor `:error` handler. Replaces the
   `(defn error-<phase> [ctx ex] …)` boilerplate that every
   phase-software-factory interceptor used to repeat verbatim:

       within iteration budget                         → retry
       past budget AND `:on-fail` set AND redirect?    → fail with verdict
       otherwise                                       → fail and propagate

   Phase 4b: when the phase-level retry budget is exhausted AND
   `redirect?` is true AND `:on-fail` is set, the handler attaches
   `:phase/verdict :repair-requested` to the phase result instead of
   calling `phase/fail-and-request-redirect`. The FSM's verdict-driven
   guarded `:phase/fail` array (Phase 2b/3) handles routing from there
   — single accounting site, same as the normal-flow path.

   Iteration count is read from `[:phase :iterations]`, the budget
   ceiling from `[:phase :budget :iterations]` (falling back to
   `default-budget` when not specified — that's the per-phase legacy
   default each interceptor used to hard-code), and the redirect target
   from `[:phase-config :on-fail]`. The error payload is constructed via
   `exception-error` (re-exported just above this defn).

   - `default-budget` defaults to 1; pass 0 to opt out of retries (the
     Observe phase fails on first error).
   - `redirect?` defaults to true (the original verify/implement/review
     behavior). Pass `false` to ignore `:on-fail` and always fail in
     place when the budget is exhausted — that's the historical behavior
     for the explore, plan, and observe interceptors, none of which ever
     read `:phase-config :on-fail`."
  ([ctx ex] (handle-error ctx ex 1 true))
  ([ctx ex default-budget] (handle-error ctx ex default-budget true))
  ([ctx ex default-budget redirect?]
   (let [iterations     (get-in ctx [:phase :iterations] 0)
         max-iterations (get-in ctx [:phase :budget :iterations] default-budget)
         on-fail        (when redirect? (get-in ctx [:phase-config :on-fail]))
         error-map      (exception-error ex)]
     (cond
       (< iterations max-iterations)
       (-> ctx
           (update-in [:phase :iterations] (fnil inc 0))
           (assoc-in  [:phase :last-error] (ex-message ex))
           (assoc-in  [:phase :status]     :retrying))

       on-fail
       ;; Phase 4b: emit verdict instead of request-redirect. The FSM's
       ;; guarded `:phase/fail` array dispatches via the
       ;; `:verdict/terminal?` guard (false for :repair-requested) and
       ;; takes the on-fail branch with the `:redirect/inc-count`
       ;; action — the SINGLE accounting site.
       (-> ctx
           (assoc :phase (fail-phase (:phase ctx) error-map))
           (assoc-in [:phase :verdict] :repair-requested)
           (assoc-in [:phase :result :output :phase/verdict] :repair-requested))

       :else
       (assoc ctx :phase (fail-phase (:phase ctx) error-map))))))

(def result-succeeded?
  "True when a phase result map has :status :success.
   Distinct from `succeeded?` which inspects multiple status keys across
   execution/step/chain shapes."
  phase-result/succeeded?)

;------------------------------------------------------------------------------ Layer 1
;; Graph derivation pass-throughs

(defn build-transition-graph
  "Derive a TransitionGraph from a workflow execution pipeline.

   Returns either a TransitionGraph map or an anomaly map satisfying
   `ai.miniforge.anomaly.interface/anomaly?`. Callers must check the return
   value before consuming graph keys.

   See `graph/build-transition-graph` for full contract, including the optional
   `opts` argument for overriding `:budget-guarded-phases`."
  ([pipeline]
   (graph/build-transition-graph pipeline))
  ([pipeline opts]
   (graph/build-transition-graph pipeline opts)))

(defn validate-graph
  "Run structural property checks on a TransitionGraph.

   opts keys (all optional):
     :check-interceptors?       — boolean, default false; enables Property 7
                                  (JVM-only; skip in babashka)
     :interceptor-registered-fn — (fn [phase-kw] → truthy/falsy); required
                                  when :check-interceptors? is true

   Returns {:valid? bool :errors [anomaly...] :warnings [anomaly...]}.

   See `graph-validator/validate-graph` for full contract."
  ([graph]
   (graph-validator/validate-graph graph))
  ([graph opts]
   (graph-validator/validate-graph graph opts)))

(def graph-valid?
  "Convenience: returns true iff the TransitionGraph passes all structural checks.
   Renamed from graph-validator/valid? to avoid collision with other interface predicates."
  graph-validator/valid?)

;------------------------------------------------------------------------------ Layer 1
;; Pipeline construction and validation

(defn- registered-phase?
  "True when phase-kw has a registered interceptor in the phase registry.
   Extracted to a named fn so it is independently testable and referenceable
   as a configuration callback (Rule 002)."
  [phase-kw]
  (contains? (list-phases) phase-kw))

(defn- invalid-pipeline-result
  "Construct a failed validate-pipeline-graph result map.
   Canonical factory for the {:valid? false ...} shape (Rule 003).
   Mirrors the shape produced by graph-validator/validate-graph."
  [errors]
  {:valid?   false
   :errors   (vec errors)
   :warnings []})

(defn pipeline-validation-valid?
  "True when a validate-pipeline-graph result has no errors.
   Prefer this predicate over direct :valid? key access so callers
   remain decoupled from the internal result shape (Rule 003)."
  [result]
  (true? (:valid? result)))

(defn validate-pipeline-graph
  "Validate a pipeline vector using full structural graph analysis.

   Chains build-transition-graph → validate-graph. Single-call entry
   point for load-time and bb-task callers.

   `pipeline` — vector of phase config maps (same shape as
                :workflow/pipeline in a workflow map).

   `opts` — passed to validate-graph; defaults to {}.
            Use {:check-interceptors? true :interceptor-registered-fn f}
            to also verify every phase has a registered interceptor
            (JVM-only — omit in babashka, where interceptors are not loaded).

   Returns {:valid? bool :errors [anomaly...] :warnings [...]}.
   When build-transition-graph itself fails (nil/empty/malformed pipeline),
   that anomaly is wrapped as the single :errors entry."
  ([pipeline]
   (validate-pipeline-graph pipeline {}))
  ([pipeline opts]
   (let [graph-or-anomaly (build-transition-graph pipeline)]
     (if (anomaly/anomaly? graph-or-anomaly)
       (invalid-pipeline-result [graph-or-anomaly])
       (validate-graph graph-or-anomaly opts)))))

(defn build-pipeline
  "Build interceptor pipeline from workflow config.

   Validates the pipeline graph before building interceptors. Returns an
   anomaly (rule 005) on structural failure — transparent to callers of
   valid pipelines (return contract unchanged: vector of interceptor maps).

   Arguments:
     workflow - Workflow map with :workflow/pipeline vector

   Returns:
     Vector of interceptor maps on success.
     An anomaly map (see `ai.miniforge.anomaly.interface/anomaly?`) on failure
     — :anomaly/data contains {:pipeline [...] :validation {:valid? false ...}}."
  [workflow]
  (ensure-phase-implementations-loaded!)
  (let [pipeline   (:workflow/pipeline workflow)
        validation (validate-pipeline-graph
                    pipeline
                    {:check-interceptors?       true
                     :interceptor-registered-fn registered-phase?})]
    (if (pipeline-validation-valid? validation)
      (mapv get-phase-interceptor pipeline)
      (anomaly/anomaly :invalid-input
                       (messages/ts :anomaly/pipeline-graph-invalid)
                       {:pipeline   pipeline
                        :validation validation}))))

(defn validate-pipeline
  "Validate a workflow pipeline configuration using full graph analysis.

   Delegates to validate-pipeline-graph with interceptor registration
   check enabled, superseding the prior basic phase-existence checks.

   Arguments:
     workflow - Workflow map with :workflow/pipeline vector

   Returns:
     {:valid? bool :errors [anomaly...] :warnings [anomaly...]}"
  [workflow]
  (ensure-phase-implementations-loaded!)
  (validate-pipeline-graph
   (:workflow/pipeline workflow)
   {:check-interceptors?       true
    :interceptor-registered-fn registered-phase?}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; List available phases
  (list-phases)
  ;; => #{:plan :implement :verify :review :release :done}

  ;; Get defaults for a phase
  (phase-defaults :implement)
  ;; => {:agent :implementer :gates [:syntax :lint] :budget {...}}

  ;; Get interceptor with defaults
  (get-phase-interceptor {:phase :plan})

  ;; Get interceptor with overrides
  (get-phase-interceptor {:phase :implement
                          :budget {:tokens 50000}
                          :gates [:syntax :lint :no-secrets]})

  ;; Build pipeline from workflow
  (def workflow {:workflow/id :test
                 :workflow/pipeline [{:phase :plan}
                                     {:phase :implement}
                                     {:phase :done}]})
  (build-pipeline workflow)

  ;; Validate pipeline (full graph analysis — supersedes basic checks)
  (validate-pipeline workflow)

  ;; Validate a raw pipeline vector (convenience — skips workflow wrapper)
  (validate-pipeline-graph (:workflow/pipeline workflow))

  ;; Validate graph directly after building it
  (let [g (build-transition-graph (:workflow/pipeline workflow))]
    (validate-graph g))

  ;; Convenience boolean check on a graph
  (let [g (build-transition-graph (:workflow/pipeline workflow))]
    (graph-valid? g))

  :leave-this-here)
