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

   Layer 0: no I/O, no clock. Takes a base envelope (already carrying the
   stream's allocated `:event/id` / `:event/version` /
   `:event/sequence-number` / identity-propagation fields per the
   event-stream `create-envelope` contract) plus an edge map from the
   state machine and layers on the edge-specific fields.

   The lifecycle layer (`core.clj`) is responsible for calling
   `create-envelope`; the emitter only assoc's on top. Two reasons:

   - **Identity propagation.** `create-envelope` ships `:event/id` as a
     Snowflake-encoded UUID when the stream carries a generator (BD-2b),
     and threads `:org/id` / `:workspace/id` / `:repo/id` / `:auth/context`
     identity fields per Phase E Decision 14. Discarding those by
     re-generating a `random-uuid` here would regress ordering (Snowflake
     vs random) and lose tenancy context.
   - **Sequence numbering.** Sequence allocation is racey-without-care;
     `create-envelope` already wraps the swap-vals! pattern that fixed an
     earlier race (PR #814). The emitter has no business duplicating that
     code path.

   The emitter overrides exactly three envelope fields, and only those:

   - `:event/timestamp`     — replaced with the edge's `:edge/updated-at`.
                              The state machine's transition time is the
                              source of truth; the wall-clock value
                              `create-envelope` stamped is discarded so
                              replay reconstructs byte-identical envelopes.
   - `:message`             — short human-readable summary.
   - `:supervisory/entity`  — the full edge map.")

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
  "Layer the edge-specific fields onto a base envelope from
   `event-stream/create-envelope`. Pure.

   `base-envelope` MUST already carry the stream's allocated `:event/id`
   (Snowflake-encoded when the stream is so configured), `:event/version`,
   `:event/sequence-number`, and any identity-propagation fields the
   caller supplied. `:event/type` is asserted to
   `:supervisory/automation-edge-upserted` defensively — the lifecycle
   layer passes that type to `create-envelope` already, but assoc'ing
   here catches accidental future regressions.

   Three fields are overridden / set:

   - `:event/timestamp`    ← `:edge/updated-at` (replay-stable transition
                             time, not wall clock).
   - `:message`            ← short human summary.
   - `:supervisory/entity` ← the full edge map."
  [base-envelope edge]
  (assoc base-envelope
         :event/type         :supervisory/automation-edge-upserted
         :event/timestamp    (:edge/updated-at edge)
         :message            (summary-message edge)
         :supervisory/entity edge))

(comment
  ;; REPL — synthesize a base envelope (what create-envelope would
  ;; produce) and an edge, wrap them, inspect.
  (let [base {:event/type            :supervisory/automation-edge-upserted
              :event/id              (random-uuid)
              :event/timestamp       (java.util.Date.)
              :event/version         "1.0.0"
              :event/sequence-number 42
              :workflow/id           nil
              :message               ""}
        edge {:edge/id                          (random-uuid)
              :edge/trigger-event-id            (random-uuid)
              :edge/trigger-kind                :pr-merged
              :edge/status                      :observed
              :edge/idempotency-key             "00000000-0000-0000-0000-000000000000"
              :edge/occurred-at                 (java.util.Date.)
              :edge/updated-at                  (java.util.Date.)
              :edge/affected-pr-ids             [["miniforge-ai/miniforge" 999]]
              :edge/affected-agent-session-ids  []
              :edge/operator-action-required    false}]
    (upsert-event base edge))

  :leave-this-here)
