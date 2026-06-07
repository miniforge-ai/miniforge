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

(ns ai.miniforge.event-stream.interface.stream
  "Event-stream lifecycle and query API."
  (:require
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Event stream lifecycle and queries

(def create-event-stream
  "Create an event stream. Returns an atom holding the stream state
   (events vector, subscribers, filters, sinks, sequence numbers,
   quiesce fence, in-flight counter, optional snowflake generator).
   Optional opts map: :logger, :sinks (vector of sink fns), :config
   (builds sinks from config), :snowflake-generator (BD-2b event-id
   generator for lexically-sortable ids)."
  core/create-event-stream)

(def create-envelope
  "Build an event envelope map with an atomically-assigned per-workflow
   sequence number. Returns a map carrying :event/type, :event/id (a
   uuid? — snowflake-encoded when the stream has a generator, else
   random), :event/timestamp, :event/version, :event/sequence-number,
   :workflow/id, :message, plus any identity fields from the opts arity
   (:org/id, :workspace/id, :repo/id, :auth/context, :event/parent-id,
   :agent/id, :agent/instance-id)."
  core/create-envelope)

(def publish!
  "Publish an event to the stream: fan out to sinks, append to the
   in-memory log, fan out to matching subscribers, log. Returns the
   published event map. If the event's workflow has been quiesced
   (BD-2a), short-circuits and returns a {:rejected? true :reason
   :workflow-quiesced :workflow-id ... :event-type ...} map without
   running sinks or subscribers."
  core/publish!)

(def subscribe!
  "Register a callback for events. 3-arg form subscribes to all events;
   4-arg form takes a filter-fn (fn [event] -> bool) so only matching
   events are delivered. Returns the subscriber-id passed in."
  core/subscribe!)

(def unsubscribe!
  "Remove a subscriber (and its filter) by id. Returns nil."
  core/unsubscribe!)

(def get-events
  "Query the in-memory event log. Returns a vector of event maps.
   Optional opts map filters/pages: :workflow-id, :event-type, :offset,
   :limit."
  core/get-events)

(def get-latest-status
  "Return the most recent :agent/status event map for a workflow (and
   optionally a specific agent-id), or nil when none exist."
  core/get-latest-status)

;; BD-2a: shutdown ordering primitives.
(def quiesce!
  "Fence future publishes for a workflow (when :workflow-id is given)
   and wait for in-flight publishes to settle. After return, publish!
   for that workflow returns a rejection map. Without :workflow-id, adds
   no fence and only waits for in-flight publishes. Returns a map:
   {:ok? true :pending-publishers 0} or {:ok? false :reason :timeout
   :pending-publishers N}. Opts: :workflow-id, :timeout-ms (default
   5000)."
  core/quiesce!)

(def drain!
  "Wait for every event published before this call to reach all sinks,
   including each sink's optional drain hook. Returns a map: {:ok? true
   :drained-count N}, {:ok? false :reason :timeout :pending-count N}, or
   {:ok? false :reason :sink-error :failed-sinks [...]}. Opts:
   :timeout-ms (default 5000) is the total budget across in-flight
   settle plus sink drain."
  core/drain!)
