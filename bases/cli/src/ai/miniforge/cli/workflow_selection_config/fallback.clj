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
(ns ai.miniforge.cli.workflow-selection-config.fallback
  "Generic fallback resolution of a selection profile via workflow
   characteristics, used when no configured profile points at a workflow
   present on the active classpath. Split out of
   `ai.miniforge.cli.workflow-selection-config` (rule 210: the parent
   namespace measured 4 real layers, max 3; this fallback-scoring concern is
   layer-coherent on its own)."
  (:require
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow-characteristics
  "Resolve workflow characteristics through the workflow interface."
  [workflow-def]
  (workflow/workflow-characteristics workflow-def))

(defn ^{:stratum 0} available-workflow-definitions
  "Return full workflow definitions from the registry for fallback scoring."
  []
  (workflow/ensure-initialized!)
  (keep workflow/get-workflow (workflow/list-workflow-ids)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} simplest-workflow-id
  "Choose the simplest available workflow by phase count and max iterations."
  [available-workflows]
  (->> available-workflows
       (sort-by (fn [workflow]
                  (let [{:keys [phases max-iterations]} (workflow-characteristics workflow)]
                    [phases max-iterations])))
       first
       :workflow/id))

(defn- ^{:stratum 1} most-comprehensive-workflow-id
  "Choose the most comprehensive available workflow by phase count."
  [available-workflows]
  (->> available-workflows
       (sort-by (fn [workflow]
                  (let [{:keys [phases max-iterations]} (workflow-characteristics workflow)]
                    [(- phases) (- max-iterations)])))
       first
       :workflow/id))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-profile-fallback
  "Resolve a profile via generic workflow characteristics when no config is present."
  [profile available-workflows]
  (case profile
    :comprehensive (most-comprehensive-workflow-id available-workflows)
    :fast (simplest-workflow-id available-workflows)
    :default (or (resolve-profile-fallback :fast available-workflows)
                 (most-comprehensive-workflow-id available-workflows))
    nil))
