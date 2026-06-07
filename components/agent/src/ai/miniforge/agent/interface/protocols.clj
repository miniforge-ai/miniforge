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

(ns ai.miniforge.agent.interface.protocols
  "Protocol and configuration re-exports for the agent public API."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]
   [ai.miniforge.agent.interface.protocols.memory :as mem-proto]
   [ai.miniforge.agent.interface.protocols.messaging :as msg-proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol and configuration re-exports

(def Agent
  "Protocol: agent behavior and lifecycle. Methods invoke/validate/repair.
   Implement to define a custom agent that processes tasks into
   artifacts/decisions/signals."
  agent-proto/Agent)

(def AgentLifecycle
  "Protocol: agent lifecycle management (init/status/shutdown/abort).
   Implement to control how an agent is initialized and torn down."
  agent-proto/AgentLifecycle)

(def AgentExecutor
  "Protocol: runs an agent on a task through the full
   init->invoke->validate->repair->complete lifecycle. Implement to
   customize execution orchestration."
  agent-proto/AgentExecutor)

(def LLMBackend
  "Protocol: LLM interaction (single method complete). Implement to swap
   or mock the language-model backend."
  agent-proto/LLMBackend)

(def Memory
  "Protocol: agent memory storage and retrieval (add-message, get-messages,
   get-window, clear-messages, get-metadata). Implement for a custom store."
  mem-proto/Memory)

(def MemoryStore
  "Protocol: managing multiple agent memories (get/save/delete/list).
   Implement to back memory persistence with a custom store."
  mem-proto/MemoryStore)

(def InterAgentMessaging
  "Protocol: send/receive/respond messages between agents during execution.
   Implement to customize how an agent exchanges messages."
  msg-proto/InterAgentMessaging)

(def MessageRouter
  "Protocol: routes and queues messages between agents
   (route-message, get-messages-for-agent, get-messages-by-workflow,
   clear-messages). Implement for a custom delivery mechanism."
  msg-proto/MessageRouter)

(def MessageValidator
  "Protocol: validates message structure against schema (validate-message).
   Implement to plug in custom message validation."
  msg-proto/MessageValidator)

(def default-role-configs
  "Map of agent role keyword -> default config map. Each config carries
   static fields (temperature, max-tokens, budget) from
   role-defaults.edn plus a dynamically selected :model."
  core/default-role-configs)

(def role-capabilities
  "Map of agent role keyword -> set of capability keywords
   (e.g. :planner -> #{:plan :decompose :estimate})."
  core/role-capabilities)

(def role-system-prompts
  "Map of agent role keyword -> system-prompt string for that role."
  core/role-system-prompts)
