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

(ns ai.miniforge.agent.interface.meta
  "Meta-agent orchestration API."
  (:require
   [ai.miniforge.agent.meta-coordinator :as meta-coord]
   [ai.miniforge.agent.meta.progress-monitor :as progress-monitor]))

;------------------------------------------------------------------------------ Layer 0
;; Meta-agent operations

(def create-progress-monitor-agent progress-monitor/create-progress-monitor-agent)
(def create-meta-coordinator meta-coord/create-coordinator)
(def check-all-meta-agents meta-coord/check-all-agents)
(def reset-all-meta-agents! meta-coord/reset-all-agents!)
(def get-meta-check-history meta-coord/get-check-history)
(def get-meta-agent-stats meta-coord/get-agent-stats)
