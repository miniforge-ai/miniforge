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

(def create-memory mem-records/create-memory)
(def add-system-message mem-records/add-system-message)
(def add-user-message mem-records/add-user-message)
(def add-assistant-message mem-records/add-assistant-message)
(def create-memory-store mem-records/create-memory-store)

(defn add-to-memory
  [memory role content]
  (mem-proto/add-message memory role content {}))

(defn get-messages
  [memory]
  (mem-proto/get-messages memory))

(defn get-memory-window
  [memory token-limit]
  (mem-proto/get-window memory token-limit))

(defn clear-memory
  [memory]
  (mem-proto/clear-messages memory))

(defn memory-metadata
  [memory]
  (mem-proto/get-metadata memory))

(defn get-memory
  [store memory-id]
  (mem-proto/get-memory store memory-id))

(defn save-memory
  [store memory]
  (mem-proto/save-memory store memory))

(defn delete-memory
  [store memory-id]
  (mem-proto/delete-memory store memory-id))

(defn list-memories
  [store scope scope-id]
  (mem-proto/list-memories store scope scope-id))
