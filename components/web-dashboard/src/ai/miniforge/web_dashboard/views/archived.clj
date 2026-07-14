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

(ns ai.miniforge.web-dashboard.views.archived
  "Archived workflow list and detail views."
  (:require
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.components :as c]
   [ai.miniforge.web-dashboard.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn format-date
  "Format timestamp as full date+time for historical entries."
  [ts]
  (let [date (cond
               (instance? java.util.Date ts) ts
               (instance? java.time.Instant ts) (java.util.Date/from ts)
               (string? ts) (try
                              (java.util.Date/from (java.time.Instant/parse ts))
                              (catch Exception _ nil))
               :else nil)]
    (if date
      (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm") date)
      (msg/t :time/none))))

(defn format-file-size
  "Format bytes to human-readable size."
  [bytes]
  (cond
    (nil? bytes)              (msg/t :time/none)
    (< bytes 1024)            (msg/t :archived/size-bytes {:size bytes})
    (< bytes (* 1024 1024))   (msg/t :archived/size-kb {:size (quot bytes 1024)})
    :else                     (msg/t :archived/size-mb {:size (format "%.1f" (/ bytes 1024.0 1024.0))})))

(defn status-label
  [status]
  (case status
    :running   (msg/t :workflow.status/running)
    :completed (msg/t :workflow.status/completed)
    :failed    (msg/t :status/failed-label)
    :stale     (msg/t :workflow.status/stale)
    (msg/t :workflow.status/unknown)))

;------------------------------------------------------------------------------ Layer 1
;; Fragments

(defn archived-workflow-list-fragment
  "Archived workflow list fragment — expandable cards with on-demand event loading."
  [archived-workflows loading?]
  (html
   (cond
     loading?
     [:div.loading-spinner (msg/t :archived/scanning)]

     (empty? archived-workflows)
     [:div.empty-state [:p (msg/t :archived/none)]]

     :else
     [:div.workflow-card-list
      (for [wf archived-workflows]
        (let [wf-id  (str (:id wf))
              status (get wf :status :stale)]
          [:details.workflow-card.archived-card
           {:id (str "arch-" wf-id)}
           [:summary.workflow-card-summary
            [:span.wf-status-dot (c/status-dot status)]
            [:span.wf-name (:name wf)]
            [:span.wf-badge {:class (str "badge-" (name status))}
             (status-label status)]
            [:span.wf-phase (or (some-> (:phase wf) name) (msg/t :time/none))]
            [:span.wf-date (format-date (:started-at wf))]
            [:span.wf-size (format-file-size (:file-size wf))]
            [:span.wf-expand-icon "▸"]]
           [:div.workflow-card-body
            {:id (str "arch-panel-" wf-id)
             :hx-get (str "/api/archive/" wf-id "/events")
             :hx-trigger "toggle once from:closest details"
             :hx-swap "innerHTML"}
            [:div.loading-spinner (msg/t :archived/loading-events)]]
           [:div.workflow-card-actions
            [:button.btn.btn-sm.btn-ghost.btn-danger
             {:hx-post (str "/api/archive/" wf-id "/delete")
              :hx-confirm (msg/t :archived/delete-confirm)
              :hx-target (str "#arch-" wf-id)
              :hx-swap "outerHTML"}
             (msg/t :action/delete)]]]))])))
