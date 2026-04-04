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

(ns ai.miniforge.orchestrator.protocol
  "Orchestrator protocols for pipeline execution.")

;------------------------------------------------------------------------------ Layer 0
;; Core orchestrator protocol

(defprotocol Orchestrator
  "Protocol for workflow orchestration.
   Coordinates agents, tasks, and knowledge through the pipeline."

  (execute-workflow [this spec context]
    "Execute a complete workflow from specification to results.
     Returns channel or promise of workflow result.")

  (get-workflow-status [this workflow-id]
    "Get the current status of a workflow execution.
     Returns {:status keyword :phase keyword :progress map :errors []}")

  (cancel-workflow [this workflow-id]
    "Cancel a running workflow. Returns true if cancelled.")

  (get-workflow-results [this workflow-id]
    "Get the results of a completed workflow.
     Returns {:artifacts [] :learnings [] :metrics {}}")

  (pause-workflow [this workflow-id]
    "Pause a running workflow for review. Returns true if paused.")

  (resume-workflow [this workflow-id]
    "Resume a paused workflow. Returns true if resumed."))

;------------------------------------------------------------------------------ Layer 1
;; Task router protocol

(defprotocol TaskRouter
  "Routes tasks to appropriate agents based on type and context."

  (route-task [this task context]
    "Determine which agent should handle a task.
     Returns {:agent agent-instance :reason string}")

  (can-handle? [this task agent-role]
    "Check if an agent role can handle a task type."))

;------------------------------------------------------------------------------ Layer 2
;; Budget manager protocol

(defprotocol BudgetManager
  "Tracks and enforces resource budgets."

  (track-usage [this workflow-id usage]
    "Record resource usage. usage: {:tokens int :cost-usd double :duration-ms int}")

  (check-budget [this workflow-id]
    "Check if workflow is within budget.
     Returns {:within-budget? bool :remaining {:tokens int :cost-usd double :time-ms int}}")

  (set-budget [this workflow-id budget]
    "Set budget limits for a workflow.
     budget: {:max-tokens int :max-cost-usd double :timeout-ms int}"))

;------------------------------------------------------------------------------ Layer 3
;; Knowledge coordinator protocol

(defprotocol KnowledgeCoordinator
  "Coordinates knowledge injection and learning capture."

  (inject-for-agent [this agent-role task context]
    "Get knowledge to inject for an agent working on a task.
     Returns vector of zettel summaries formatted for agent context.")

  (capture-execution-learning [this execution-result]
    "Capture learnings from an agent execution.
     Returns created learning zettel if applicable.")

  (should-promote-learning? [this learning]
    "Check if a learning should be promoted to rule.
     Returns {:promote? bool :reason string :confidence float}"))
