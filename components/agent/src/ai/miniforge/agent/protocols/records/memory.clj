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

(ns ai.miniforge.agent.protocols.records.memory
  "Records that implement Memory and MemoryStore protocols.

   AgentMemory - In-memory message storage with token windowing
   InMemoryStore - In-memory store for managing multiple memories"
  (:require
   [ai.miniforge.agent.interface.protocols.memory :as p]
   [ai.miniforge.agent.protocols.impl.memory :as impl]))

(defrecord AgentMemory [id scope messages metadata]
  p/Memory
  (add-message [this role content opts]
    (impl/add-message-impl this role content opts))

  (get-messages [this]
    (impl/get-messages-impl this))

  (get-window [this token-limit]
    (impl/get-window-impl this token-limit))

  (clear-messages [this]
    (impl/clear-messages-impl this))

  (get-metadata [this]
    (impl/get-metadata-impl this)))

(defrecord InMemoryStore [memories]
  p/MemoryStore
  (get-memory [this memory-id]
    (impl/get-memory-impl this memory-id))

  (save-memory [this memory]
    (impl/save-memory-impl this memory))

  (delete-memory [this memory-id]
    (impl/delete-memory-impl this memory-id))

  (list-memories [this scope scope-id]
    (impl/list-memories-impl this scope scope-id)))

;------------------------------------------------------------------------------ Layer 1
;; Factory functions

(defn create-memory
  "Create a new memory instance.

   Options:
   - :scope     - Memory scope (:agent, :task, :workflow)
   - :scope-id  - ID of the scope (agent-id, task-id, or workflow-id)
   - :metadata  - Additional metadata map

   Example:
     (create-memory {:scope :task :scope-id task-id})"
  ([] (create-memory {}))
  ([{:keys [scope scope-id metadata] :or {scope :task}}]
   (->AgentMemory
    (random-uuid)
    scope
    []
    (merge {:created-at (java.util.Date.)
            :scope-id scope-id}
           metadata))))

(defn add-system-message
  "Add a system message to memory. Convenience function."
  [memory content]
  (p/add-message memory :system content {}))

(defn add-user-message
  "Add a user message to memory. Convenience function."
  [memory content]
  (p/add-message memory :user content {}))

(defn add-assistant-message
  "Add an assistant message to memory. Convenience function."
  [memory content & {:keys [tokens]}]
  (p/add-message memory :assistant content {:tokens tokens}))

(defn create-memory-store
  "Create an in-memory store for agent memories."
  []
  (->InMemoryStore (atom {})))
