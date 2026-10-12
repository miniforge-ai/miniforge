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
   [ai.miniforge.event-stream.boundary.publication-input :as publication-input]
   [ai.miniforge.event-stream.boundary.current-stream :as current-stream]
   [ai.miniforge.event-stream.boundary.legacy-control :as legacy-control]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.envelope-draft :as draft]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} prepare-current-publication
  "Validate and redact a sequence-free chain v2, intervention v2, or Spec snapshot
   draft. Returns [scope draft] or an anomaly, not an acknowledgment. Other families
   are unsupported here. Historical replay and legacy publish! remain separate."
  publication-input/prepare)

(def ^{:stratum 0} create-current-event-stream
  "Own an existing canonical :journal-directory and restore supported typed records.
   Supports chain v2, intervention v2 and Spec snapshots only. No volatile fallback.
   Optional :sinks are best-effort listeners after durable commit (default []).
   Also accepts :logger and :snowflake-generator. Returns a stream or anomaly."
  current-stream/create)

(def ^{:stratum 0} close-current-event-stream!
  "Release a current stream's journal ownership and reject further publication.
   This does not wait for in-flight listener callbacks; it is not a delivery drain.
   Returns an anomaly without mutation when given a legacy stream."
  current-stream/close!)

;; Event stream lifecycle and queries
(def ^{:stratum 0} create-event-stream
  "Create an event stream. Returns an atom holding the stream state
   (events vector, subscribers, filters, sinks, sequence numbers,
   quiesce fence, in-flight counter, optional snowflake generator).
   Optional opts map: :logger, :sinks (vector of sink fns), :config
   (builds sinks from config), :snowflake-generator (BD-2b event-id
   generator for lexically-sortable ids)."
  core/create-event-stream)

(def ^{:stratum 0} create-envelope
  "Build an event envelope map with an atomically-assigned per-workflow
   sequence number. Returns a map carrying :event/type, :event/id (a
   uuid? — snowflake-encoded when the stream has a generator, else
   random), :event/timestamp, :event/version, :event/sequence-number,
   :workflow/id, :message, plus any identity fields from the opts arity
   (:org/id, :workspace/id, :repo/id, :auth/context, :event/parent-id,
   :agent/id, :agent/instance-id)."
  core/create-envelope)

(def ^{:stratum 0} create-event-draft
  "Create event identity and optional metadata without reserving a sequence number.
   This is not publication or acknowledgment; retain the draft across retries.
   Do not pass an uncommitted draft to the legacy publish! API."
  draft/create)

(def ^{:stratum 0} publish!
  "Current streams validate/redact drafts, durably commit, then notify listeners.
   Returns the durable receipt or an anomaly; caller positions are rejected.
   Legacy streams retain their existing behavior: fan out to sinks, append to the
   in-memory log, fan out to matching subscribers, log. Returns the
   published event map. If the event's workflow has been quiesced
   (BD-2a), short-circuits and returns a {:rejected? true :reason
   :workflow-quiesced :workflow-id ... :event-type ...} map without
   running sinks or subscribers."
  core/publish!)

(def ^{:stratum 0} subscribe!
  "Register a callback for events. 3-arg form subscribes to all events;
   4-arg form takes a filter-fn (fn [event] -> bool) so only matching
   events are delivered. Returns the subscriber-id passed in."
  core/subscribe!)

(def ^{:stratum 0} unsubscribe!
  "Remove a subscriber (and its filter) by id. Returns nil."
  core/unsubscribe!)

(def ^{:stratum 0} get-events
  "Query the in-memory event log. Returns a vector of event maps.
   Optional opts map filters/pages: :workflow-id, :event-type, :offset,
   :limit. Current streams also accept :scope [scope-type UUID], validate
   options (returning anomalies for invalid options), and filter by authoritative
   scope: a chain event's workflow reference is not workflow membership."
  core/get-events)

(def ^{:stratum 0} get-latest-status
  "Return the most recent :agent/status event map for a workflow (and
   optionally a specific agent-id), or nil when none exist."
  core/get-latest-status)

;; BD-2a: shutdown ordering primitives.
(defn ^{:stratum 0} quiesce!
  "Fence future publishes for a workflow (when :workflow-id is given)
   and wait for in-flight publishes to settle. After return, publish!
   for that workflow returns a rejection map. Without :workflow-id, adds
   no fence and only waits for in-flight publishes. Returns a map:
   {:ok? true :pending-publishers 0} or {:ok? false :reason :timeout
   :pending-publishers N}. Opts: :workflow-id, :timeout-ms (default
   5000). Current streams return an unsupported anomaly; these legacy barriers
   do not govern current-profile publication."
  ([stream] (quiesce! stream {}))
  ([stream opts] (legacy-control/invoke! core/quiesce! stream opts)))

(defn ^{:stratum 0} drain!
  "Wait for every event published before this call to reach all sinks,
   including each sink's optional drain hook. Returns a map: {:ok? true
   :drained-count N}, {:ok? false :reason :timeout :pending-count N}, or
   {:ok? false :reason :sink-error :failed-sinks [...]}. Opts:
   :timeout-ms (default 5000) is the total budget across in-flight
   settle plus sink drain. Current streams return an unsupported anomaly."
  ([stream] (drain! stream {}))
  ([stream opts] (legacy-control/invoke! core/drain! stream opts)))
