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

(ns ai.miniforge.web-dashboard.views
  "Main view components for web dashboard."
  (:require
   [re-frame.core :as rf]
   [ai.miniforge.web-dashboard.subscriptions :as subs]))

;; Layer 0 - Status indicator

(defn connection-status
  "WebSocket connection status indicator."
  []
  (let [status @(rf/subscribe [::subs/websocket-status])
        connected? @(rf/subscribe [::subs/websocket-connected?])]
    [:div.status-bar
     [:span.status-label "WebSocket: "]
     [:span {:class (if connected? "status-connected" "status-disconnected")}
      (name status)]]))

;; Layer 1 - Event log view

(defn event-item
  "Single event item."
  [event]
  (let [event-type (or (:event/type event) (:type event) "unknown")
        timestamp (or (:event/timestamp event) (:timestamp event))
        workflow-id (or (:workflow-id event) (:workflow/id event))]
    [:div.event
     [:div.event-header
      [:span.event-type (str event-type)]
      [:span.event-time (when timestamp (.toLocaleTimeString (js/Date. timestamp)))]]
     (when workflow-id
       [:div.event-workflow
        [:span.workflow-id (subs (str workflow-id) 0 8)]])]))

(defn event-log
  "Event log panel."
  []
  (let [events @(rf/subscribe [::subs/events])]
    [:div.event-log
     [:h2 "Event Stream"]
     (if (empty? events)
       [:p "Waiting for events..."]
       [:div.event-list
        (for [[idx event] (map-indexed vector events)]
          ^{:key idx} [event-item event])])]))

;; Layer 2 - Main panel

(defn header
  "Application header."
  []
  [:header.app-header
   [:h1 "MINIFORGE | Web Dashboard"]
   [connection-status]])

(defn main-panel
  "Root application component."
  []
  [:div.app-container
   [header]
   [:main.app-main
    [event-log]]])
