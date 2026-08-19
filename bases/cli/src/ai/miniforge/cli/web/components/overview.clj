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
(ns ai.miniforge.cli.web.components.overview
  "Fleet header, keyboard-hint, and dashboard stat-pill fragments."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.web.components.batch-approve :as batch-approve]
   [ai.miniforge.cli.web.components.status :as status-components]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

(defn- ^{:stratum 0} stat-pill
  [class-name text]
  [:span {:class class-name} text])

(defn- ^{:stratum 0} keyboard-hint
  [prefix-key suffix-key label]
  [:span
   [:kbd prefix-key]
   "/"
   [:kbd suffix-key]
   (str " " label)])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fleet-header
  [fleet-status]
  [:div.header
   [:div {:style "display: flex; align-items: center; gap: 16px;"}
    [:h1 (t :web-ui/fleet-dashboard-heading)]
    (status-components/status-indicator fleet-status)]])

(defn ^{:stratum 1} keyboard-hints
  []
  [:div.keyboard-hints
   (keyboard-hint "j" "k" (t :web-ui/hint-navigate))
   [:span [:kbd "r"] (str " " (t :web-ui/hint-refresh))]
   [:span [:kbd "a"] (str " " (t :web-ui/hint-approve))]
   [:span [:kbd "x"] (str " " (t :web-ui/hint-reject))]])

(defn ^{:stratum 1} dashboard-stats
  [{:keys [total low medium high]}]
  [:div.header-stats
   (stat-pill "stat" (t :web-ui/pr-total {:count total}))
   (stat-pill "stat stat-low" (t :web-ui/pr-low {:count low}))
   (stat-pill "stat stat-medium" (t :web-ui/pr-medium {:count medium}))
   (stat-pill "stat stat-high" (t :web-ui/pr-high {:count high}))
   (when (pos? low)
     (batch-approve/batch-approve-safe-button low))])
