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

(ns ai.miniforge.agent.interface.protocols.memory
  "Public protocols for agent memory storage and retrieval.

   These are extensibility points - users can implement custom memory
   stores by implementing these protocols.")

(defprotocol Memory
  "Protocol for agent memory storage and retrieval."

  (add-message [this role content opts]
    "Add a message to memory. opts may include :tokens, :metadata.
     Returns updated memory.")

  (get-messages [this]
    "Get all messages in chronological order.")

  (get-window [this token-limit]
    "Get messages that fit within token limit, prioritizing recent.
     Returns {:messages [...] :total-tokens int :trimmed-count int}")

  (clear-messages [this]
    "Clear all messages. Returns empty memory.")

  (get-metadata [this]
    "Get memory metadata (scope, created-at, etc.)"))

(defprotocol MemoryStore
  "Protocol for managing multiple agent memories."

  (get-memory [this memory-id]
    "Retrieve a memory by ID.")

  (save-memory [this memory]
    "Save/update a memory. Returns the memory.")

  (delete-memory [this memory-id]
    "Delete a memory by ID.")

  (list-memories [this scope scope-id]
    "List all memories for a given scope."))
