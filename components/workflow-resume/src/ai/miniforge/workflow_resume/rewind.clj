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
(ns ai.miniforge.workflow-resume.rewind
  "What a rewind of a reconstructed run to a phase keeps, and whether it
   can run: `mf resume --from-phase` and the operator's
   `:retry-from-phase` apply the same rule.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} kept-phases
  "The completed phases a rewind to `phase` keeps: those before it."
  [reconstructed phase]
  (vec (take-while (complement #{phase}) (:completed-phases reconstructed))))

(defn ^{:stratum 0} checkpointed-results
  "The run's phase results that a phase can build on, nil when it has
   none. `reconstruct-context` reads them from the run's checkpoint when
   there is one, which is exactly when there is an FSM snapshot. Without
   one it rebuilds only telemetry (outcome, duration) from events, and a
   phase reading its predecessor's output finds nothing in that."
  [reconstructed]
  (when (:machine-snapshot reconstructed)
    (:phase-results reconstructed)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} refusal
  "Why a rewind to `phase` cannot run, nil when it can. It is refused
   when it would keep a phase with no checkpointed result: the re-run
   would start without the output it builds on."
  [reconstructed phase]
  (let [recorded (set (keys (checkpointed-results reconstructed)))
        missing (vec (remove recorded (kept-phases reconstructed phase)))]
    (when (seq missing)
      {:resume/reason :phase-results-not-checkpointed
       :resume/phases missing})))
