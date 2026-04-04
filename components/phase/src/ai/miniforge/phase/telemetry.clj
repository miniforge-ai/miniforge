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

   Provides four capabilities for phase interceptors:
   1. Streaming callbacks — relay agent LLM chunks to event-stream subscribers
   2. Phase lifecycle events — emit phase-started / phase-completed events
   3. Agent lifecycle events — emit agent-started / agent-completed events
   4. Event-stream resolution — locate the stream in varied execution contexts

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

(defn- make-fallback-event
  "Build a minimal event map when the event-stream constructor cannot be resolved.
   Ensures events are published even when the event-stream component version
   has incompatible constructor signatures."
  [event-type workflow-id phase-kw extra]
  (merge {:event/type      event-type
          :event/id        (random-uuid)
          :event/timestamp (java.util.Date.)
          :workflow/id     workflow-id
          :workflow/phase  phase-kw}
         extra))

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

(defn emit-phase-started!
  "Publish a :workflow/phase-started event for the given phase.

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
        (catch Exception _
          ;; Fallback: publish a minimal event when constructor resolution fails
          (safe-publish! stream
                         (make-fallback-event :workflow/phase-started workflow-id phase-kw
                                              {:message (str (name phase-kw) " phase started")})))))))

(defn emit-phase-completed!
  "Publish a :workflow/phase-completed event for the given phase.

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
        (catch Exception _
          ;; Fallback: publish a minimal event when constructor resolution fails
          (safe-publish! stream
                         (make-fallback-event :workflow/phase-completed workflow-id phase-kw
                                              (cond-> {:message (str (name phase-kw) " phase completed")}
                                                (:outcome result)     (assoc :phase/outcome (:outcome result))
                                                (:duration-ms result) (assoc :phase/duration-ms (:duration-ms result))
                                                (:tokens result)      (assoc :phase/tokens (:tokens result))
                                                (:cost-usd result)    (assoc :phase/cost-usd (:cost-usd result))
                                                (:error result)       (assoc :phase/error (:error result))))))))))

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
        (catch Exception _
          (safe-publish! stream
                         {:event/type      :agent/started
                          :event/id        (random-uuid)
                          :event/timestamp (java.util.Date.)
                          :workflow/id     workflow-id
                          :workflow/phase  phase-kw
                          :agent/id        agent-id
                          :message         (str (name agent-id) " agent started for " (name phase-kw))}))))))

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
        (catch Exception _
          (safe-publish! stream
                         (cond-> {:event/type      :agent/completed
                                  :event/id        (random-uuid)
                                  :event/timestamp (java.util.Date.)
                                  :workflow/id     workflow-id
                                  :workflow/phase  phase-kw
                                  :agent/id        agent-id
                                  :message         (str (name agent-id) " agent completed for " (name phase-kw))}
                           (:status result) (assoc :agent/status (:status result)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-streaming-callback {:execution/id (random-uuid)} :plan)
  (create-streaming-callback-or-noop {:execution/id (random-uuid)} :plan)
  (emit-phase-started! {:execution/id (random-uuid) :event-stream (atom {})} :verify)
  (emit-phase-completed! {:execution/id (random-uuid) :event-stream (atom {})} :verify
                         {:outcome :success :duration-ms 1234 :tokens 500})
  (emit-agent-started! {:execution/id (random-uuid) :event-stream (atom {})} :verify :tester)
  (emit-agent-completed! {:execution/id (random-uuid) :event-stream (atom {})} :verify :tester
                         {:status :success})
  :leave-this-here)
