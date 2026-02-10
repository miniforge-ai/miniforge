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

(ns ai.miniforge.event-stream.interface
  "Public API for workflow event streaming and observability.

   This component provides real-time visibility into workflow execution:
   - Pub/sub event bus for workflow lifecycle events
   - N3-compliant event schemas (see specs/normative/N3-event-stream.md)
   - Streaming callbacks for agent output
   - Per-workflow event sequencing

   Key use cases:
   - CLI: Real-time console output during workflow execution
   - Web: Server-Sent Events (SSE) for live dashboard updates
   - Debugging: Complete event replay for workflow analysis
   - Analytics: Token usage, timing, and cost tracking

   Example:
     ;; Create event stream
     (def stream (create-event-stream))

     ;; Subscribe to all events
     (subscribe! stream :my-listener
       (fn [event]
         (println (:event/type event) \"-\" (:message event))))

     ;; Publish workflow events
     (publish! stream (workflow-started stream workflow-id spec))
     (publish! stream (phase-started stream workflow-id :plan))
     (publish! stream (agent-chunk stream workflow-id :planner \"Output...\"))
     (publish! stream (phase-completed stream workflow-id :plan {:outcome :success}))
     (publish! stream (workflow-completed stream workflow-id :success))"
  (:require
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Event stream lifecycle

(defn create-event-stream
  "Create an event stream for publishing and subscribing to workflow events.

   Options:
   - :logger - Optional logger instance for debugging
   - :sinks - Vector of sink functions (default: file sink)
   - :config - User config map to create sinks from

   Returns: Event stream atom that manages subscribers and event log.

   Sinks control where events are written (files, stdout, fleet, etc).
   See ai.miniforge.event-stream.sinks for sink options.

   Examples:
     ;; Default file sink
     (create-event-stream)

     ;; With custom sinks
     (require '[ai.miniforge.event-stream.sinks :as sinks])
     (create-event-stream {:sinks [(sinks/file-sink) (sinks/stdout-sink)]})

     ;; From user config
     (create-event-stream {:config user-config})"
  [& [opts]]
  (core/create-event-stream opts))

(defn publish!
  "Publish an event to the event stream.

   Arguments:
   - stream: Event stream atom from create-event-stream
   - event: Event map created by one of the event constructors

   Notifies all subscribers whose filters match the event.
   Events are appended to the stream's event log (append-only).

   Returns: The event that was published.

   Example:
     (publish! stream (workflow-started stream wf-id spec))"
  [stream event]
  (core/publish! stream event))

(defn subscribe!
  "Subscribe to events on the event stream.

   Arguments:
   - stream: Event stream atom
   - subscriber-id: Unique identifier for this subscriber (keyword or string)
   - callback: Function (fn [event] ...) called for matching events
   - filter-fn: Optional predicate (fn [event] bool) to filter events

   Returns: subscriber-id (for use with unsubscribe!)

   Example:
     ;; Subscribe to all events
     (subscribe! stream :my-sub (fn [e] (println e)))

     ;; Subscribe to phase events only
     (subscribe! stream :phase-watcher
       (fn [e] (println (:workflow/phase e)))
       (fn [e] (#{:workflow/phase-started :workflow/phase-completed}
                (:event/type e))))"
  ([stream subscriber-id callback]
   (core/subscribe! stream subscriber-id callback))
  ([stream subscriber-id callback filter-fn]
   (core/subscribe! stream subscriber-id callback filter-fn)))

(defn unsubscribe!
  "Unsubscribe from events on the event stream.

   Arguments:
   - stream: Event stream atom
   - subscriber-id: The subscriber ID returned from subscribe!

   Example:
     (unsubscribe! stream :my-sub)"
  [stream subscriber-id]
  (core/unsubscribe! stream subscriber-id))

;------------------------------------------------------------------------------ Layer 1
;; Query API

(defn get-events
  "Get events from the stream, optionally filtered.

   Arguments:
   - stream: Event stream atom
   - opts: Options map with:
     - :workflow-id - Filter by workflow UUID
     - :event-type - Filter by event type keyword
     - :offset - Skip first N events
     - :limit - Return at most N events

   Returns: Vector of events.

   Example:
     (get-events stream)
     (get-events stream {:workflow-id wf-id})
     (get-events stream {:event-type :workflow/phase-started :limit 10})"
  [stream & [opts]]
  (core/get-events stream opts))

(defn get-latest-status
  "Get the most recent agent/status event for a workflow.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - agent-id: Optional keyword agent ID to filter by

   Returns: Most recent status event or nil.

   Example:
     (get-latest-status stream wf-id)
     (get-latest-status stream wf-id :planner)"
  [stream workflow-id & [agent-id]]
  (core/get-latest-status stream workflow-id agent-id))

;------------------------------------------------------------------------------ Layer 2
;; Event constructors (N3 compliant)

(defn workflow-started
  "Create a workflow/started event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - spec: Optional workflow specification map

   Example:
     (workflow-started stream wf-id)
     (workflow-started stream wf-id {:name \"quick-fix\" :version \"2.0.0\"})"
  [stream workflow-id & [spec]]
  (core/workflow-started stream workflow-id spec))

(defn phase-started
  "Create a workflow/phase-started event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - phase: Keyword phase name (:plan, :implement, :verify, :review, etc.)
   - context: Optional phase context map

   Example:
     (phase-started stream wf-id :plan)
     (phase-started stream wf-id :implement {:budget {:tokens 30000}})"
  [stream workflow-id phase & [context]]
  (core/phase-started stream workflow-id phase context))

(defn phase-completed
  "Create a workflow/phase-completed event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - phase: Keyword phase name
   - result: Optional result map with:
     - :outcome - :success, :failure, or :skipped
     - :duration-ms - Phase duration in milliseconds
     - :artifacts - Vector of artifact UUIDs

   Example:
     (phase-completed stream wf-id :plan)
     (phase-completed stream wf-id :implement {:outcome :success :duration-ms 45000})"
  [stream workflow-id phase & [result]]
  (core/phase-completed stream workflow-id phase result))

(defn agent-chunk
  "Create an agent/chunk event for streaming agent output.

   This is the key event for real-time observability - it streams
   LLM output tokens as they're generated.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - agent-id: Keyword agent ID (:planner, :implementer, etc.)
   - delta: String chunk of output text
   - done?: Optional boolean indicating stream completion

   Example:
     (agent-chunk stream wf-id :planner \"Analyzing the spec...\")
     (agent-chunk stream wf-id :planner \"\" true) ; Stream complete"
  [stream workflow-id agent-id delta & [done?]]
  (core/agent-chunk stream workflow-id agent-id delta done?))

(defn agent-status
  "Create an agent/status event for real-time progress updates.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - agent-id: Keyword agent ID
   - status-type: One of :reading, :thinking, :generating, :validating,
                  :repairing, :running, :waiting, :communicating
   - message: Human-readable status message

   Example:
     (agent-status stream wf-id :implementer :generating \"Writing Terraform config\")"
  [stream workflow-id agent-id status-type message]
  (core/agent-status stream workflow-id agent-id status-type message))

(defn workflow-completed
  "Create a workflow/completed event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - status: Keyword status (:success, :failure, :cancelled)
   - duration-ms: Optional total workflow duration in milliseconds

   Example:
     (workflow-completed stream wf-id :success 120000)"
  [stream workflow-id status & [duration-ms]]
  (core/workflow-completed stream workflow-id status duration-ms))

(defn workflow-failed
  "Create a workflow/failed event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - error: Error map with :message key, or Throwable

   Example:
     (workflow-failed stream wf-id {:message \"LLM timeout\" :type :timeout})
     (workflow-failed stream wf-id (ex-info \"Out of tokens\" {:tokens 0}))"
  [stream workflow-id error]
  (core/workflow-failed stream workflow-id error))

(defn llm-request
  "Create an llm/request event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - agent-id: Keyword agent ID
   - model: String model name (e.g., \"claude-sonnet-4\")
   - prompt-tokens: Optional input token count

   Example:
     (llm-request stream wf-id :planner \"claude-sonnet-4\" 2400)"
  [stream workflow-id agent-id model & [prompt-tokens]]
  (core/llm-request stream workflow-id agent-id model prompt-tokens))

(defn llm-response
  "Create an llm/response event.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - agent-id: Keyword agent ID
   - model: String model name
   - request-id: UUID of the original llm/request event
   - metrics: Optional map with :completion-tokens, :total-tokens,
              :duration-ms, :cost-usd

   Example:
     (llm-response stream wf-id :planner \"claude-sonnet-4\" req-id
                   {:completion-tokens 850 :duration-ms 3200})"
  [stream workflow-id agent-id model request-id & [metrics]]
  (core/llm-response stream workflow-id agent-id model request-id metrics))

;------------------------------------------------------------------------------ Layer 3
;; Convenience functions

(defn create-streaming-callback
  "Create an on-chunk callback that publishes to an event stream and optionally prints.

   This is the primary integration point for wiring streaming into workflows.
   The callback conforms to the signature expected by agents and LLM clients.

   Arguments:
   - stream: Event stream atom
   - workflow-id: UUID of the workflow
   - agent-id: Keyword agent ID for the current agent
   - opts: Options map with:
     - :print? - If true, print chunks to stdout (default: false)
     - :quiet? - If true, suppress all console output (default: false)

   Returns: Callback function (fn [{:keys [delta done? content]}])

   Example:
     (def on-chunk (create-streaming-callback stream wf-id :planner {:print? true}))

     ;; Use in agent context
     (agent/invoke planner task (assoc ctx :on-chunk on-chunk))"
  [stream workflow-id agent-id & [opts]]
  (let [{:keys [print? quiet?]} opts]
    (fn [{:keys [delta done?]}]
      ;; Print to console if requested
      (when (and print? (not quiet?) delta (not (empty? delta)))
        (print delta)
        (flush))
      ;; Always publish to event stream
      (when stream
        (publish! stream (agent-chunk stream workflow-id agent-id (or delta "") done?))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Full workflow example
  (def stream (create-event-stream))
  (def wf-id (random-uuid))

  ;; Subscribe to see events
  (subscribe! stream :console
              (fn [e] (println (:event/type e) "-" (:message e))))

  ;; Run through workflow
  (publish! stream (workflow-started stream wf-id {:name "test"}))
  (publish! stream (phase-started stream wf-id :plan))

  ;; Create streaming callback
  (def on-chunk (create-streaming-callback stream wf-id :planner {:print? true}))
  (on-chunk {:delta "Planning..." :done? false})
  (on-chunk {:delta " step 1..." :done? false})
  (on-chunk {:delta " done!" :done? true})

  (publish! stream (phase-completed stream wf-id :plan {:outcome :success}))
  (publish! stream (workflow-completed stream wf-id :success 10000))

  ;; Query events
  (count (get-events stream))
  (get-events stream {:event-type :agent/chunk})

  (unsubscribe! stream :console)

  :leave-this-here)
