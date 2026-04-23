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

(ns ai.miniforge.agent.interface.meta
  "Learning-loop API."
  (:require
   [ai.miniforge.agent.meta.loop :as meta-loop]))

;------------------------------------------------------------------------------ Layer 1
;; Learning layer — learning loop (N1 §3.3)

(def run-meta-loop-cycle!
  "Execute one learning-loop cycle.

   Orchestrates: reliability compute → degradation eval → diagnosis → improvement proposals.

   Arguments:
     ctx - {:event-stream :reliability-engine :degradation-manager
            :improvement-pipeline :metrics :training-examples}

   Returns: {:cycle/sli-count :cycle/breach-count :cycle/signal-count
             :cycle/diagnosis-count :cycle/proposal-count :cycle/degradation-mode ...}"
  meta-loop/run-meta-loop-cycle!)

(def create-meta-loop-context
  "Create a learning-loop context bundling reliability engine, degradation manager,
   improvement pipeline, and metrics store.

   Arguments:
     event-stream - event stream atom
     config       - optional {:reliability {} :degradation {}}"
  meta-loop/create-meta-loop-context)

(def record-workflow-outcome!
  "Record a workflow outcome for later learning-loop analysis."
  meta-loop/record-workflow-outcome!)

(def run-cycle-from-context!
  "Run one learning-loop cycle from metrics accumulated in the context."
  meta-loop/run-cycle-from-context!)

(comment
  ;; This namespace intentionally exposes only the learning loop. Live runtime
  ;; supervisors are exported from ai.miniforge.agent.interface.supervision.
  )
