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

(def create-agent core/create-agent)
(def create-agent-map core/create-agent-map)
(def create-executor core/create-executor)

(defn execute
  [executor agent task context]
  (agent-proto/execute executor agent task context))

(defn invoke
  [agent task context]
  (supervisory-bridge/invoke-with-projection
   agent
   task
   context
   #(agent-proto/invoke agent task context)))

(defn validate
  [agent output context]
  (agent-proto/validate agent output context))

(defn repair
  [agent output errors context]
  (agent-proto/repair agent output errors context))

(defn init
  [agent config]
  (agent-proto/init agent config))

(defn agent-status
  [agent]
  (agent-proto/status agent))

(defn shutdown
  [agent]
  (agent-proto/shutdown agent))
