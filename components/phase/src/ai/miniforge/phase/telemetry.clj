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

(ns ai.miniforge.phase.telemetry
  "Soft-dependency telemetry helpers for phase implementations.

   Provides five capabilities for phase interceptors:
   1. Streaming callbacks — relay agent LLM chunks to event-stream subscribers
   2. Phase lifecycle events — emit phase-started / phase-completed events
   3. Agent lifecycle events — emit agent-started / agent-completed events
   4. Milestone transitions — emit milestone-reached events on successful phases
   5. Event-stream resolution — locate the stream in varied execution contexts

   All functions are safe no-ops when the event-stream component is absent
   (soft dependency via requiring-resolve)."
)

;------------------------------------------------------------------------------ Layer 0
;; Event-stream resolution

(defn resolve-event-stream
  "Locate the event-stream atom from an execution context.

   Checks multiple keys used by different callers:
   - :event-stream           — dashboard, workflow runner
   - :execution/event-stream — execution engine namespaced context

   Returns the stream atom or nil."
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)))

(defn- resolve-workflow-id
  "Extract workflow-id from execution context, checking multiple keys.

   Different callers place the workflow id under different context keys.
   Returns UUID or nil."
  [ctx]
  (or (:execution/id ctx)
      (:workflow/id ctx)
      (:workflow-id ctx)))

;------------------------------------------------------------------------------ Layer 0
;; Internal helpers

(defn- safe-publish!
  "Publish an event to the stream, swallowing errors to avoid breaking phases."
  [stream event]
  (try
    (let [publish-fn (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
      (publish-fn stream event))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 0
;; Streaming callback construction

(defn create-streaming-callback
  "Create an event-stream backed streaming callback for a phase agent.

   Returns nil when the execution context has no event stream or the
   event-stream component is not available."
  [ctx agent-id]
  (when-let [stream (resolve-event-stream ctx)]
    (when-let [create-cb (try
                           (requiring-resolve
                            'ai.miniforge.event-stream.interface/create-streaming-callback)
                           (catch Exception _ nil))]
      (create-cb stream
                 (resolve-workflow-id ctx)
                 agent-id
                 {:print? (not (:quiet ctx))
                  :quiet? (boolean (:quiet ctx))}))))

(defn create-streaming-callback-or-noop
  "Create a streaming callback, or a no-op function if event stream is unavailable.

   Unlike create-streaming-callback, this never returns nil — always returns a
   callable function. Use when passing the callback to code that does not
   nil-check."
  [ctx agent-id]
  (or (create-streaming-callback ctx agent-id)
      (fn [_chunk] nil)))

;------------------------------------------------------------------------------ Layer 1
;; Phase lifecycle event emission

(declare emit-milestone-reached!
         emit-milestone-started!
         emit-milestone-completed!
         emit-milestone-failed!)

(defn emit-phase-started!
  "Publish a :workflow/phase-started event for the given phase.
   Also emits a :phase/milestone-started event marking the phase as an
   in-progress milestone checkpoint.

   Safe no-op when no event stream is available or the event-stream
   component cannot be resolved. Called from phase :enter functions.

   Arguments:
   - ctx      — execution context map
   - phase-kw — phase keyword (e.g. :verify, :review, :release)"
  [ctx phase-kw]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [phase-started-fn (requiring-resolve
                                 'ai.miniforge.event-stream.interface/phase-started)
              event            (phase-started-fn stream workflow-id phase-kw)]
          (safe-publish! stream event))
        (catch Exception _ nil)))
    ;; Milestone checkpoint: phase start = milestone start
    (emit-milestone-started! ctx phase-kw)))

(defn emit-phase-completed!
  "Publish a :workflow/phase-completed event for the given phase.

   Milestone semantics:
   - On :success — emits :phase/milestone-completed and :workflow/milestone-reached
   - On any other outcome — emits :phase/milestone-failed

   Safe no-op when no event stream is available or the event-stream
   component cannot be resolved. Called from phase :leave functions.

   Arguments:
   - ctx      — execution context map
   - phase-kw — phase keyword (e.g. :verify, :review, :release)
   - result   — optional map with :outcome, :duration-ms, :tokens,
                 :cost-usd, :error, :redirect-to, :artifacts"
  [ctx phase-kw result]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [phase-completed-fn (requiring-resolve
                                   'ai.miniforge.event-stream.interface/phase-completed)
              event              (phase-completed-fn stream workflow-id phase-kw result)]
          (safe-publish! stream event))
        (catch Exception _ nil))
      ;; Milestone transitions: success = completed, anything else = failed
      (if (= :success (:outcome result))
        (do
          (emit-milestone-completed! ctx phase-kw result)
          (emit-milestone-reached! ctx phase-kw result))
        (emit-milestone-failed! ctx phase-kw result)))))

;------------------------------------------------------------------------------ Layer 2
;; Agent lifecycle event emission

(defn emit-agent-started!
  "Publish an :agent/started event.
   Called when a phase begins executing an agent invocation."
  [ctx phase-kw agent-id]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [agent-started-fn (requiring-resolve
                                 'ai.miniforge.event-stream.interface/agent-started)
              event            (agent-started-fn stream workflow-id agent-id)]
          (safe-publish! stream event))
        (catch Exception _ nil)))))

(defn emit-agent-completed!
  "Publish an :agent/completed event.
   Called when a phase's agent invocation finishes."
  [ctx phase-kw agent-id result]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [agent-completed-fn (requiring-resolve
                                   'ai.miniforge.event-stream.interface/agent-completed)
              event              (agent-completed-fn stream workflow-id agent-id)]
          (safe-publish! stream event))
        (catch Exception _ nil)))))

;------------------------------------------------------------------------------ Layer 3
;; Milestone transition event emission

(defn emit-milestone-reached!
  "Publish a :workflow/milestone-reached event when a phase successfully completes.

   Called automatically from emit-phase-completed! when :outcome is :success.
   Can also be called directly to mark an explicit milestone transition.

   Safe no-op when no event stream is available or the event-stream
   component cannot be resolved.

   Arguments:
   - ctx      — execution context map
   - phase-kw — phase keyword that completed (used as the milestone name)
   - result   — result map from the phase (may include :artifacts, :tokens, :cost-usd)"
  [ctx phase-kw result]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [milestone-reached-fn (requiring-resolve
                                     'ai.miniforge.event-stream.interface/milestone-reached)
              event                (milestone-reached-fn stream workflow-id phase-kw)]
          (safe-publish! stream event))
        (catch Exception _ nil)))))

(defn emit-milestone-started!
  "Publish a :phase/milestone-started event when a phase begins execution.

   Called automatically from emit-phase-started! to signal that a named
   milestone checkpoint has entered an in-progress state.

   Safe no-op when no event stream is available.

   Arguments:
   - ctx      — execution context map
   - phase-kw — phase keyword used as the milestone identifier"
  [ctx phase-kw]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [constructor (requiring-resolve
                            'ai.miniforge.event-stream.interface/milestone-started)
              event       (constructor stream workflow-id phase-kw)]
          (safe-publish! stream event))
        (catch Exception _ nil)))))

(defn emit-milestone-completed!
  "Publish a :phase/milestone-completed event when a phase succeeds.

   Called automatically from emit-phase-completed! when :outcome is :success.

   Safe no-op when no event stream is available.

   Arguments:
   - ctx      — execution context map
   - phase-kw — phase keyword used as the milestone identifier
   - result   — result map from the phase (may include :artifacts, :tokens, :cost-usd)"
  [ctx phase-kw result]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)]
      (try
        (let [constructor (requiring-resolve
                            'ai.miniforge.event-stream.interface/milestone-completed)
              event       (constructor stream workflow-id phase-kw
                                       (str (name phase-kw) " milestone completed"))]
          (safe-publish! stream event))
        (catch Exception _ nil)))))

(defn emit-milestone-failed!
  "Publish a :phase/milestone-failed event when a phase fails.

   Called automatically from emit-phase-completed! when :outcome is not :success.

   Safe no-op when no event stream is available.

   Arguments:
   - ctx      — execution context map
   - phase-kw — phase keyword used as the milestone identifier
   - result   — result map from the phase (may include :error, :outcome)"
  [ctx phase-kw result]
  (when-let [stream (resolve-event-stream ctx)]
    (let [workflow-id (resolve-workflow-id ctx)
          reason      (or (get-in result [:error :message])
                          (str (name phase-kw) " milestone failed"))]
      (try
        (let [constructor (requiring-resolve
                            'ai.miniforge.event-stream.interface/milestone-failed)
              event       (constructor stream workflow-id phase-kw reason)]
          (safe-publish! stream event))
        (catch Exception _ nil)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-streaming-callback {:execution/id (random-uuid)} :plan)
  (create-streaming-callback-or-noop {:execution/id (random-uuid)} :plan)

  ;; Phase lifecycle — each call now also emits the milestone transition event
  (emit-phase-started! {:execution/id (random-uuid) :event-stream (atom {})} :verify)
  ;; => emits :workflow/phase-started AND :phase/milestone-started

  (emit-phase-completed! {:execution/id (random-uuid) :event-stream (atom {})} :verify
                         {:outcome :success :duration-ms 1234 :tokens 500})
  ;; => emits :workflow/phase-completed, :phase/milestone-completed, :workflow/milestone-reached

  (emit-phase-completed! {:execution/id (random-uuid) :event-stream (atom {})} :verify
                         {:outcome :failure :error {:message "tests failed"}})
  ;; => emits :workflow/phase-completed, :phase/milestone-failed

  ;; Agent lifecycle
  (emit-agent-started! {:execution/id (random-uuid) :event-stream (atom {})} :verify :tester)
  (emit-agent-completed! {:execution/id (random-uuid) :event-stream (atom {})} :verify :tester
                         {:status :success})

  ;; Milestone transitions (direct use)
  (emit-milestone-started! {:execution/id (random-uuid) :event-stream (atom {})} :verify)
  (emit-milestone-completed! {:execution/id (random-uuid) :event-stream (atom {})} :verify
                              {:artifacts ["test-report.edn"] :tokens 500})
  (emit-milestone-failed! {:execution/id (random-uuid) :event-stream (atom {})} :verify
                           {:error {:message "gate failed"} :outcome :failure})
  (emit-milestone-reached! {:execution/id (random-uuid) :event-stream (atom {})} :verify
                            {:artifacts ["test-report.edn"] :tokens 500})
  :leave-this-here)
