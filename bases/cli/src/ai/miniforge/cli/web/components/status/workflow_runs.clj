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
(ns ai.miniforge.cli.web.components.status.workflow-runs
  "Per-run workflow list rendering: the run status icon, a single rendered
   run row, and the run list (or its \"none\" placeholder). Split out of
   `ai.miniforge.cli.web.components.status` (rule 210: the parent namespace
   measured 4 real layers, max 3; this icon -> run -> runs composition chain
   is layer-coherent on its own)."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.web.fleet :as fleet]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const no-workflows-style
  "color: var(--text-muted); font-size: 12px; text-align: center;")

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

(defn ^{:stratum 0} workflow-status-icon
  [run]
  (let [status (get run :status)
        conclusion (get run :conclusion)]
    (cond
      (= status "in_progress") "⏳"
      (= conclusion "success") "✓"
      (#{"failure" "timed_out" "startup_failure"} conclusion) "✗"
      :else "○")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} workflow-run
  [{:keys [workflowName createdAt] :as run}]
  [:div.workflow-run
   [:span.workflow-run-status (workflow-status-icon run)]
   [:span.workflow-run-name workflowName]
   [:span.workflow-run-time (fleet/format-time-ago createdAt)]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} workflow-runs
  [runs]
  (if (seq runs)
    (map workflow-run runs)
    [[:div {:style no-workflows-style}
      (t :web-ui/workflow-status-none)]]))
