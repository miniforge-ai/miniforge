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
  "Meta-agent orchestration API."
  (:require
   [ai.miniforge.agent.meta-coordinator :as meta-coord]
   [ai.miniforge.agent.meta.loop :as meta-loop]
   [ai.miniforge.agent.meta.progress-monitor :as progress-monitor]))

;------------------------------------------------------------------------------ Layer 0
;; Meta-agent operations

(def create-progress-monitor-agent
  "Create a Progress Monitor meta-agent. Arg (optional): options map
   (:check-interval-ms :priority :stagnation-threshold-ms :max-total-ms).
   Returns a meta-agent suitable for a meta coordinator."
  progress-monitor/create-progress-monitor-agent)

(def create-meta-coordinator
  "Create a meta-agent coordinator over a set of meta-agents. Arg: vector of
   meta-agent instances. Returns a MetaCoordinator record."
  meta-coord/create-coordinator)

(def check-all-meta-agents
  "Run due health checks across all enabled meta-agents. Args: coordinator,
   workflow-state. Returns {:status :healthy|:warning|:halt :checks [...]}
   with :halt-reason/:halting-agent when status is :halt."
  meta-coord/check-all-agents)

(def reset-all-meta-agents!
  "Reset all meta-agent state (on workflow restart). Arg: coordinator.
   Returns the coordinator."
  meta-coord/reset-all-agents!)

(def get-meta-check-history
  "Return recent meta-agent health-check history. Args: coordinator, optional
   {:limit :agent-id :status}. Returns a sequence of check-record maps."
  meta-coord/get-check-history)

(def get-meta-agent-stats
  "Return per-meta-agent statistics. Arg: coordinator. Returns
   {:agents [{:id :name :checks-run :last-check :last-status
              :halt-count :warning-count ...}]}."
  meta-coord/get-agent-stats)

;------------------------------------------------------------------------------ Layer 1
;; Learning layer — meta-loop cycle (N1 §3.3)

(def run-meta-loop-cycle!
  "Execute one meta-loop learning cycle.

   Orchestrates: reliability compute → degradation eval → diagnosis → improvement proposals.

   Arguments:
     ctx - {:event-stream :reliability-engine :degradation-manager
            :improvement-pipeline :metrics :training-examples}

   Returns: {:cycle/sli-count :cycle/breach-count :cycle/signal-count
             :cycle/diagnosis-count :cycle/proposal-count :cycle/degradation-mode ...}"
  meta-loop/run-meta-loop-cycle!)

(def create-meta-loop-context
  "Create a MetaLoopContext bundling reliability engine, degradation manager,
   improvement pipeline, and metrics store.

   Arguments:
     event-stream - event stream atom
     config       - optional {:reliability {} :degradation {}}"
  meta-loop/create-meta-loop-context)

(def record-workflow-outcome!
  "Record a workflow completion for use in the next meta-loop cycle.

   Arguments:
     ctx           - MetaLoopContext
     workflow-id   - uuid
     status        - :completed | :failed | :escalated
     failure-class - optional :failure.class/* keyword"
  meta-loop/record-workflow-outcome!)

(def run-cycle-from-context!
  "Run one meta-loop cycle using the accumulated metrics in ctx.

   Arguments:
     ctx               - MetaLoopContext
     training-examples - optional vector of training records

   Returns: cycle result map."
  meta-loop/run-cycle-from-context!)
