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

(ns ai.miniforge.agent.protocol
  "Agent protocol definitions.
   Layer 0: Core protocols for agent execution and lifecycle management.

   Note: The protocols have been moved to:
   - ai.miniforge.agent.interface.protocols.agent (Agent, AgentLifecycle, AgentExecutor, LLMBackend)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.agent.interface.protocols.agent :as p]))

;; Re-export protocols for backward compatibility
(def Agent p/Agent)
(def AgentLifecycle p/AgentLifecycle)
(def AgentExecutor p/AgentExecutor)
(def LLMBackend p/LLMBackend)

;; Re-export protocol methods
(def invoke p/invoke)
(def validate p/validate)
(def repair p/repair)
(def init p/init)
(def status p/status)
(def shutdown p/shutdown)
(def abort p/abort)
(def execute p/execute)
(def complete p/complete)
