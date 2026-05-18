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

(ns ai.miniforge.automation-edge-correlator.emitter
  "Pure envelope construction for `:supervisory/automation-edge-upserted`
   events (N5-delta-4 §4.1).

   Layer 0: no I/O, no clock. Takes an edge map produced by the state
   machine plus a sequence number allocated by the lifecycle layer and
   returns the wire envelope per N3 §2.1.

   The lifecycle layer (`core.clj`) is responsible for sequence-number
   allocation — the brick does not reach into the event-stream's internal
   counter from here. This keeps the emitter trivially testable: given an
   edge and a number, it produces a deterministic map."
  (:require
   [ai.miniforge.automation-edge-correlator.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Message construction

(defn- short-id
  "First eight chars of a UUID string — keeps the human-readable
   `:message` field bounded without being so terse as to lose the
   ability to grep for a specific edge."
  [u]
  (subs (str u) 0 8))

(defn- summary-message
  "Build the human-readable `:message` field for an upsert event.

   Mirrors the supervisory-state precedent (e.g. workflow-upserted uses
   `\"Workflow <key> upserted\"`): kind, short id, status. Operators
   reading raw event logs get enough to identify the edge without
   reaching for `:supervisory/entity`."
  [{:edge/keys [id trigger-kind status]}]
  (str "AutomationEdge " (short-id id)
       " (" (name (or trigger-kind :unknown))
       ") → " (name (or status :unknown))))

;------------------------------------------------------------------------------ Layer 0
;; Envelope construction

(defn upsert-event
  "Build a `:supervisory/automation-edge-upserted` event envelope from an
   `edge` map produced by the state machine. Pure.

   The envelope follows N3 §2.1:

   - `:event/id`              — freshly-generated `random-uuid`. Replay
                                regenerates this; consumers dedup on
                                `:edge/id` per N5-delta-4 §2.3.
   - `:event/type`            — `:supervisory/automation-edge-upserted`
   - `:event/timestamp`       — the edge's `:edge/updated-at`. Mirrors
                                the source-of-truth discipline from the
                                state machine: the envelope clock is the
                                most-recent transition time, not wall
                                clock at emission. Replay therefore
                                reconstructs byte-identical timestamps.
   - `:event/version`         — `\"1.0.0\"` per N5-delta-4 §4.1.
   - `:event/sequence-number` — supplied by the lifecycle layer; the
                                pure emitter does not allocate.
   - `:supervisory/entity`    — the full edge map.
   - `:message`               — short human-readable summary.

   `:workflow/id` is left absent: the schema marks it `:optional` /
   `:maybe`, and the edge does not always belong to a single workflow
   (a `:pr/merged` trigger opens an edge before any handler workflow
   exists). The downstream filter scopes on `:event/type` instead."
  [edge sequence-number]
  {:event/type            :supervisory/automation-edge-upserted
   :event/id              (random-uuid)
   :event/timestamp       (:edge/updated-at edge)
   :event/version         "1.0.0"
   :event/sequence-number sequence-number
   :message               (summary-message edge)
   :supervisory/entity    edge})

;; The schema namespace is required so `clj-kondo` / cljc compilation
;; finds the dependency even though we currently reach into it only via
;; the validation hook in core.clj. Touch a value here so the require is
;; not flagged as unused — `schema/automation-edge-statuses` is a stable,
;; pure constant suitable for the compile-time anchor.
(def ^:private referenced-statuses
  "Compile-time anchor that keeps the `schema` require live. The vector
   is also useful in the rich comment below for REPL exploration."
  schema/automation-edge-statuses)

(comment
  ;; REPL — synthesize an edge, wrap it, inspect.
  (let [edge {:edge/id                          (random-uuid)
              :edge/trigger-event-id            (random-uuid)
              :edge/trigger-kind                :pr-merged
              :edge/status                      :observed
              :edge/idempotency-key             "00000000-0000-0000-0000-000000000000"
              :edge/occurred-at                 (java.util.Date.)
              :edge/updated-at                  (java.util.Date.)
              :edge/affected-pr-ids             [["miniforge-ai/miniforge" 999]]
              :edge/affected-agent-session-ids  []
              :edge/operator-action-required    false}]
    (upsert-event edge 42))

  referenced-statuses

  :leave-this-here)
