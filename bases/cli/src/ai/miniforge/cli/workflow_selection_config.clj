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
(ns ai.miniforge.cli.workflow-selection-config
  "Resource-driven workflow selection profile resolution. Configured-profile
   lookup lives in `workflow-selection-config.profiles-resource`; generic
   fallback scoring lives in `workflow-selection-config.fallback` (rule 210:
   this namespace measured 4 real layers, max 3, split by concern)."
  (:require
   [ai.miniforge.cli.workflow-selection-config.fallback :as fallback]
   [ai.miniforge.cli.workflow-selection-config.profiles-resource :as profiles-resource]))

;------------------------------------------------------------------------------ Layer 0

;; Public API
(defn ^{:stratum 0} resolve-selection-profile
  "Resolve a logical selection profile to a concrete workflow id.

   Profiles are app-owned configuration. If a configured profile points at a
   workflow not present on the active classpath, fall back to generic workflow
  characteristics."
  ([profile]
   (let [available-workflows (fallback/available-workflow-definitions)]
     (resolve-selection-profile profile available-workflows)))
  ([profile available-workflows]
   (let [configured-id (get (profiles-resource/configured-selection-profiles) profile)
         available-ids (set (map :workflow/id available-workflows))]
     (cond
       (contains? available-ids configured-id) configured-id
       :else (fallback/resolve-profile-fallback profile available-workflows)))))
