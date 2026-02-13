;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state
  "State management for web dashboard — thin re-export from sub-namespaces."
  (:require
   [ai.miniforge.web-dashboard.state.core :as core]
   [ai.miniforge.web-dashboard.state.trains :as trains]
   [ai.miniforge.web-dashboard.state.workflows :as workflows]
   [ai.miniforge.web-dashboard.state.fleet :as fleet]
   [ai.miniforge.web-dashboard.state.archive :as archive]))

;------------------------------------------------------------------------------ Layer 0
;; Re-exports from sub-namespaces

;; core
(def create-state core/create-state)
(def get-uptime core/get-uptime)

;; trains
(def get-trains trains/get-trains)
(def get-train-detail trains/get-train-detail)
(def train-action! trains/train-action!)
(def get-dags trains/get-dags)
(def get-dag-state trains/get-dag-state)

;; workflows
(def get-workflows workflows/get-workflows)
(def get-workflow-detail workflows/get-workflow-detail)
(def get-events workflows/get-events)
(def enqueue-command! workflows/enqueue-command!)
(def dequeue-commands! workflows/dequeue-commands!)
;; archive
(def archive-loading? archive/archive-loading?)
(def get-archived-workflows archive/get-archived-workflows)
(def get-archived-workflow-events archive/get-archived-workflow-events)
(def delete-archived-workflow! archive/delete-archived-workflow!)
(def apply-retention! archive/apply-retention!)

;; fleet
(def calculate-risk-score fleet/calculate-risk-score)
(def compute-stats fleet/compute-stats)
(def compute-risk-analysis fleet/compute-risk-analysis)
(def get-fleet-state fleet/get-fleet-state)
(def get-risk-analysis fleet/get-risk-analysis)
(def get-recent-activity fleet/get-recent-activity)
(def get-evidence-state fleet/get-evidence-state)

;------------------------------------------------------------------------------ Layer 1
;; Composite state

(defn get-dashboard-state
  "Get complete dashboard state for initial load.
   Computes shared data once to avoid redundant calls.
   Combines live and archived workflows for stats and summaries."
  [state]
  (let [fleet-data (get-fleet-state state)
        trains (:trains fleet-data)
        live-wfs (get-workflows state)
        archived-wfs (get-archived-workflows state)
        all-wfs (concat live-wfs archived-wfs)]
    {:stats (compute-stats trains all-wfs)
     :fleet fleet-data
     :risk (get-risk-analysis state)
     :activity (get-recent-activity state)
     :workflows (take 10 (if (seq live-wfs) live-wfs archived-wfs))}))
