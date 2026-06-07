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

(ns ai.miniforge.agent.interface.runtime
  "Agent creation, execution, and lifecycle operations."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]
   [ai.miniforge.agent.supervisory-bridge :as supervisory-bridge]))

;------------------------------------------------------------------------------ Layer 0
;; Agent creation and execution

(def create-agent
  "Create an agent by role with optional config overrides. Args: role
   (keyword e.g. :planner :implementer), optional opts map (:model
   :temperature :max-tokens :budget :phase :privacy-required etc.).
   Returns a BaseAgent record satisfying the Agent protocol."
  core/create-agent)

(def create-agent-map
  "Create an agent and return it as a plain map conforming to the Agent
   schema (useful for serialization/storage). Args: role, optional opts.
   Returns a map with :agent/* keys."
  core/create-agent-map)

(def create-executor
  "Create an agent executor. Arg (optional): config map (e.g.
   {:memory-store ...}). Returns a value satisfying the AgentExecutor
   protocol."
  core/create-executor)

(defn execute
  "Run an agent on a task through the full lifecycle. Args: executor, agent,
   task, context. Returns {:success bool :outputs [...] :metrics {...}}
   and :error on failure."
  [executor agent task context]
  (agent-proto/execute executor agent task context))

(defn invoke
  "Invoke an agent's main logic on a task, with supervisory projection
   wrapping. Args: agent, task, context. Returns
   {:success bool :outputs [...] :decisions [...] :signals [...] :metrics {...}}."
  [agent task context]
  (supervisory-bridge/invoke-with-projection
   agent
   task
   context
   #(agent-proto/invoke agent task context)))

(defn validate
  "Check whether agent output meets requirements. Args: agent, output,
   context. Returns {:valid? bool :errors [...] :warnings [...]}."
  [agent output context]
  (agent-proto/validate agent output context))

(defn repair
  "Attempt to fix validation failures. Args: agent, output, errors, context.
   Returns {:repaired output :changes [...] :success bool}."
  [agent output errors context]
  (agent-proto/repair agent output errors context))

(defn init
  "Initialize an agent with configuration. Args: agent, config. Returns the
   initialized agent instance."
  [agent config]
  (agent-proto/init agent config))

(defn agent-status
  "Return an agent's current status. Arg: agent. Returns
   {:state keyword :metrics {...} :last-activity inst}."
  [agent]
  (agent-proto/status agent))

(defn shutdown
  "Clean up agent resources. Arg: agent. Returns {:success bool}."
  [agent]
  (agent-proto/shutdown agent))
