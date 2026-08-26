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
(ns ai.miniforge.evidence-bundle.phases
  "Per-phase evidence: one phase's output, one phase's evidence entry,
   and the map of both across a workflow's phases.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} build-phase-output
  "Extract the output map for phase evidence.

   Handles both:
   - Legacy shape: {:output {...}} — extracts :output directly
   - New environment model: {:environment-id ... :summary ... :metrics ...}
     — synthesizes an output map from the provenance metadata

   In the new model, :evidence/implement captures summary + metrics (not code).
   :evidence/verify captures test output. :evidence/release captures PR metadata."
  [phase-result]
  (or
   ;; Legacy: explicit :output key
   (get phase-result :output)
   ;; New environment model: synthesize output from provenance metadata
   (when (or (:summary phase-result) (:metrics phase-result)
             (:environment-id phase-result))
     (cond-> {}
       (:summary phase-result)        (assoc :summary        (:summary phase-result))
       (:metrics phase-result)        (assoc :metrics        (:metrics phase-result))
       (:environment-id phase-result) (assoc :environment-id (:environment-id phase-result))))
   {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-phase-evidence
  "Build phase evidence from phase execution context.
   Returns phase evidence map per N6 spec.

   Handles both legacy (:output map) and new-model (:environment-id, :summary,
   :metrics) phase result shapes. In the new model, code is NOT captured here;
   it is derived from the PR diff at release time."
  [phase-name agent-id phase-result]
  (let [started-at   (get phase-result :started-at (java.time.Instant/now))
        completed-at (get phase-result :completed-at (java.time.Instant/now))]
    {:phase/name                phase-name
     :phase/agent               agent-id
     :phase/agent-instance-id   (get phase-result :agent-instance-id (random-uuid))
     :phase/started-at          started-at
     :phase/completed-at        completed-at
     :phase/duration-ms         (get phase-result :duration-ms
                                     (- (.toEpochMilli completed-at)
                                        (.toEpochMilli started-at)))
     :phase/output              (build-phase-output phase-result)
     :phase/artifacts           (vec (get phase-result :artifacts []))
     :phase/inner-loop-iterations (get phase-result :inner-loop-iterations 0)
     :phase/event-stream-range  (get phase-result :event-stream-range
                                     {:start-seq 0 :end-seq 0})}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} collect-phase-evidence
  "Collect evidence for a single phase from workflow state.
   Returns phase evidence map or nil if phase not executed."
  [workflow-state phase-name]
  (when-let [phase-data (get-in workflow-state [:workflow/phases phase-name])]
    (build-phase-evidence
     phase-name
     (get phase-data :agent :unknown)
     phase-data)))
