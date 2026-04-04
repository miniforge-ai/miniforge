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

(ns ai.miniforge.workflow.interface.profiles
  "Workflow-owned state-profile provider helpers.")

(defn load-state-profile-provider
  []
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/load-state-profile-provider)))

(defn available-state-profile-ids
  []
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/available-state-profile-ids)))

(defn default-state-profile-id
  []
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/default-state-profile-id)))

(defn resolve-state-profile
  [profile]
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/resolve-state-profile) profile))
