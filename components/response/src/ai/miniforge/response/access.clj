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

(ns ai.miniforge.response.access
  "Canonical accessors for phase-result / agent-result shapes.

   ONE reader per logical value, so consumers never re-derive a key-path or
   stack a defensive `or` fallback when a value's location seems uncertain.
   Each accessor names the single canonical location; if the value isn't
   there, that's a loud nil (and a loud gate/consumer failure) — never a
   silent fall-through to a stale or wrong shape.

   Background: the 2026-06-12 shape-drift audit found the same logical value
   (review decision, phase artifact, status, …) read from 3–7 different
   key-paths across the gate / phase / resume layers, because each time a
   location moved a consumer added another `or` branch instead of fixing the
   contract. That hid contract breaks and caused the recurring
   review-approved-gate-rejects-an-approved-verdict bug. This namespace is the
   single source of truth those consumers route through.

   Canonical contract:
   - A phase's gate-relevant payload is the agent response `:output`, reached
     from a phase-result at `[:result :output]`. `phase-output` is THE reader.
   - A review verdict's decision is `:review/decision` on that output.")

(defn phase-output
  "THE phase artifact that gates validate: the agent response `:output`,
   canonical location `[:result :output]` on a phase-result.

   Every phase publishes its gate-relevant payload here — the curated code
   artifact (implement), the verdict (review), the plan (plan), etc. Gates
   read the field they care about off this one map. Returns nil when absent;
   a gate seeing nil should fail loudly rather than the caller guessing
   another location."
  [phase-result]
  (get-in phase-result [:result :output]))

(defn review-decision
  "THE review verdict decision keyword (`:approved`, `:rejected`,
   `:conditionally-approved`, `:changes-requested`), read from a phase
   `:output` map (the reviewer artifact — i.e. the value `phase-output`
   returns for the review phase). Canonical key `:review/decision`, no fossil
   fallbacks. Returns nil when absent."
  [output]
  (get output :review/decision))
