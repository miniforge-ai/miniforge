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

(ns ai.miniforge.agent.memory
  "Agent memory and context management.

   Note: The protocol and implementation have been moved to:
   - ai.miniforge.agent.interface.protocols.memory (Memory, MemoryStore protocols)
   - ai.miniforge.agent.protocols.impl.memory (implementation functions)
   - ai.miniforge.agent.protocols.records.memory (AgentMemory, InMemoryStore records)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.agent.interface.protocols.memory :as p]
   [ai.miniforge.agent.protocols.impl.memory :as impl]
   [ai.miniforge.agent.protocols.records.memory :as records]))

;; Re-export protocols for backward compatibility
(def Memory p/Memory)
(def MemoryStore p/MemoryStore)

;; Re-export protocol methods
(def add-message p/add-message)
(def get-messages p/get-messages)
(def get-window p/get-window)
(def clear-messages p/clear-messages)
(def get-metadata p/get-metadata)
(def get-memory p/get-memory)
(def save-memory p/save-memory)
(def delete-memory p/delete-memory)
(def list-memories p/list-memories)

;; Re-export pure functions
(def make-message impl/make-message)
(def estimate-tokens impl/estimate-tokens)
(def total-tokens impl/total-tokens)
(def trim-to-token-limit impl/trim-to-token-limit)

;; Re-export factory functions (not records themselves - they're Java classes)
(def create-memory records/create-memory)
(def add-system-message records/add-system-message)
(def add-user-message records/add-user-message)
(def add-assistant-message records/add-assistant-message)
(def create-memory-store records/create-memory-store)

;; For code that needs the record types, import them from records namespace
;; e.g., (import ai.miniforge.agent.protocols.records.memory.AgentMemory)
