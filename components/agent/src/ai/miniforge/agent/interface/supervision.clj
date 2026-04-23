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

(ns ai.miniforge.agent.interface.supervision
  "Live workflow supervision API.

   This namespace provides a workflow-facing supervision boundary over the
   current meta-coordinator implementation, without changing the underlying
   runtime behavior."
  (:require
   [ai.miniforge.agent.meta-coordinator :as meta-coord]
   [ai.miniforge.agent.meta.progress-monitor :as progress-monitor]))

;------------------------------------------------------------------------------ Layer 0
;; Supervision runtime operations

(def create-progress-monitor-agent progress-monitor/create-progress-monitor-agent)
(def create-supervision-coordinator meta-coord/create-coordinator)
(def check-all-supervisors meta-coord/check-all-agents)
(def reset-all-supervisors! meta-coord/reset-all-agents!)
(def get-supervision-check-history meta-coord/get-check-history)
(def get-supervisor-stats meta-coord/get-agent-stats)
