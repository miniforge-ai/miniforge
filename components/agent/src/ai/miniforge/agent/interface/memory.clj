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

(ns ai.miniforge.agent.interface.memory
  "Agent memory and memory-store operations."
  (:require
   [ai.miniforge.agent.interface.protocols.memory :as mem-proto]
   [ai.miniforge.agent.protocols.records.memory :as mem-records]))

;------------------------------------------------------------------------------ Layer 0
;; Memory operations

(def create-memory
  "Create a new agent memory. Arg (optional): {:scope :scope-id :metadata};
   :scope defaults to :task. Returns an AgentMemory (a Memory) with an
   empty message list."
  mem-records/create-memory)

(def add-system-message
  "Append a :system message to memory. Args: memory, content (string).
   Returns the updated memory."
  mem-records/add-system-message)

(def add-user-message
  "Append a :user message to memory. Args: memory, content (string).
   Returns the updated memory."
  mem-records/add-user-message)

(def add-assistant-message
  "Append an :assistant message to memory. Args: memory, content (string),
   optional :tokens. Returns the updated memory."
  mem-records/add-assistant-message)

(def create-memory-store
  "Create an in-memory MemoryStore backed by an atom. No args.
   Returns an InMemoryStore (a MemoryStore)."
  mem-records/create-memory-store)

(defn add-to-memory
  "Append a message to memory under the given role. Args: memory, role
   (keyword), content. Returns the updated memory."
  [memory role content]
  (mem-proto/add-message memory role content {}))

(defn get-messages
  "Return all messages in memory in chronological order (a sequence of
   message maps). Arg: memory."
  [memory]
  (mem-proto/get-messages memory))

(defn get-memory-window
  "Return the most recent messages fitting within token-limit. Args: memory,
   token-limit (int). Returns {:messages [...] :total-tokens int
   :trimmed-count int}; system messages are always preserved."
  [memory token-limit]
  (mem-proto/get-window memory token-limit))

(defn clear-memory
  "Remove all messages from memory. Arg: memory. Returns the emptied memory."
  [memory]
  (mem-proto/clear-messages memory))

(defn memory-metadata
  "Return memory metadata. Arg: memory. Returns a map merging stored
   metadata with :memory/id :memory/scope :memory/message-count
   :memory/total-tokens."
  [memory]
  (mem-proto/get-metadata memory))

(defn get-memory
  "Retrieve a memory from a store by id. Args: store, memory-id.
   Returns the memory, or nil if absent."
  [store memory-id]
  (mem-proto/get-memory store memory-id))

(defn save-memory
  "Save/update a memory in a store. Args: store, memory. Returns the memory."
  [store memory]
  (mem-proto/save-memory store memory))

(defn delete-memory
  "Delete a memory from a store by id. Args: store, memory-id. Returns nil."
  [store memory-id]
  (mem-proto/delete-memory store memory-id))

(defn list-memories
  "List memories in a store matching a scope. Args: store, scope, scope-id.
   Returns a sequence of memories."
  [store scope scope-id]
  (mem-proto/list-memories store scope scope-id))
