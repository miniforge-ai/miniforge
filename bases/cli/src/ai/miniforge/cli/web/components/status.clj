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
(ns ai.miniforge.cli.web.components.status
  "Status-oriented dashboard fragments."
  (:require
   [hiccup2.core :as h]
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

(defn- ^{:stratum 0} overall-status-key
  [overall]
  (case overall
    :healthy :web-ui/status-healthy
    :degraded :web-ui/status-degraded
    :warning :web-ui/status-warning
    :error :web-ui/status-error
    :web-ui/status-unknown))

(defn- ^{:stratum 0} workflow-stat
  [class-name value]
  [:span {:class class-name}
   [:span value]])

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

(defn ^{:stratum 1} status-indicator
  [status]
  (let [overall (get status :overall)
        status-class (name overall)
        status-text (t (overall-status-key overall))]
    (h/html
     [:div.status-indicator {:class (str "status-" status-class)
                             :hx-get "/api/status"
                             :hx-trigger "every 60s"
                             :hx-swap "outerHTML"}
      [:span.status-dot]
      [:span status-text]])))

(defn- ^{:stratum 1} workflow-run
  [{:keys [workflowName createdAt] :as run}]
  [:div.workflow-run
   [:span.workflow-run-status (workflow-status-icon run)]
   [:span.workflow-run-name workflowName]
   [:span.workflow-run-time (fleet/format-time-ago createdAt)]])

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} workflow-runs
  [runs]
  (if (seq runs)
    (map workflow-run runs)
    [[:div {:style no-workflows-style}
      (t :web-ui/workflow-status-none)]]))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} workflow-status
  [repos]
  (let [{:keys [running failed succeeded runs]} (fleet/get-workflow-status repos)
        running-value (str running " ⏳")
        failed-value (str failed " ✗")
        succeeded-value (str succeeded " ✓")
        rendered-runs (workflow-runs runs)]
    (h/html
     [:div.workflow-status
      {:hx-get "/api/workflows"
       :hx-trigger "every 60s"
       :hx-swap "outerHTML"}
      [:div.workflow-status-header
       [:span (t :web-ui/workflow-status-header)]]
      [:div.workflow-stats
       (workflow-stat "workflow-stat workflow-stat-running" running-value)
       (workflow-stat "workflow-stat workflow-stat-failed" failed-value)
       (workflow-stat "workflow-stat workflow-stat-passed" succeeded-value)]
      (into [:div.workflow-runs] rendered-runs)])))
