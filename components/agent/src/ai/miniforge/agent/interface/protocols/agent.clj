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

(ns ai.miniforge.agent.interface.protocols.agent
  "Public protocols for agent behavior, lifecycle, and execution.

   These are extensibility points - users can implement custom agents,
   agent executors, or LLM backends by implementing these protocols.

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)")

;------------------------------------------------------------------------------ Layer 0
;; Core Agent Protocol

(defprotocol Agent
  "Protocol for agent behavior and lifecycle operations.
   Agents process tasks and produce artifacts, decisions, and signals."

  (invoke [this task context]
    "Execute the agent's main logic on a task.
     Returns {:success bool :outputs [...] :decisions [...] :signals [...] :metrics {...}}")

  (validate [this output context]
    "Check if output meets requirements.
     Returns {:valid? bool :errors [...] :warnings [...]}")

  (repair [this output errors context]
    "Attempt to fix validation failures.
     Returns {:repaired output :changes [...] :success bool}"))

(defprotocol AgentLifecycle
  "Protocol for agent lifecycle management."

  (init [this config]
    "Initialize agent with configuration.
     Returns initialized agent instance.")

  (status [this]
    "Return current agent status.
     Returns {:state keyword :metrics {...} :last-activity inst}")

  (shutdown [this]
    "Clean up agent resources.
     Returns {:success bool}")

  (abort [this reason]
    "Abort agent execution with a specific reason.
     Sets agent status to :aborted and records the reason.
     Idempotent - safe to call multiple times.
     Returns {:aborted true :reason reason :aborted-at inst}"))

;------------------------------------------------------------------------------ Layer 1
;; Agent Executor Protocol

(defprotocol AgentExecutor
  "Protocol for executing agents on tasks."

  (execute [this agent task context]
    "Run agent on task with given context.
     Handles full lifecycle: init -> invoke -> validate -> (repair if needed) -> complete/fail
     Returns {:success bool :outputs [...] :metrics {...} :error (optional)}"))

;------------------------------------------------------------------------------ Layer 2
;; LLM Backend Protocol (for dependency injection)

(defprotocol LLMBackend
  "Protocol for LLM interaction.
   Allows mocking in tests and swapping implementations."

  (complete [this messages opts]
    "Send messages to LLM and get completion.
     Returns {:content string :usage {:input-tokens int :output-tokens int} :model string}"))
