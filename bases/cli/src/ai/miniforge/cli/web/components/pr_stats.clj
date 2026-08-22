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
(ns ai.miniforge.cli.web.components.pr-stats
  "PR risk/complexity stat-card fragments."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.web.risk :as risk]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} risk-label
  [risk-level]
  (t (case risk-level
       :low :web-ui/risk-low
       :medium :web-ui/risk-medium
       :high :web-ui/risk-high
       :web-ui/risk-unknown)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} analysis-stats
  [risk-level complexity total-changes file-count]
  [:div.stats-grid
   [:div.stat-card
    [:div.stat-card-value {:style (str "color: " (get risk/colors risk-level))}
     (risk-label risk-level)]
    [:div.stat-card-label (t :web-ui/risk-level-label)]]
   [:div.stat-card
    [:div.stat-card-value (str/capitalize (name complexity))]
    [:div.stat-card-label (t :web-ui/complexity-label)]]
   [:div.stat-card
    [:div.stat-card-value total-changes]
    [:div.stat-card-label (t :web-ui/lines-changed-label)]]
   [:div.stat-card
    [:div.stat-card-value file-count]
    [:div.stat-card-label (t :web-ui/files-modified-label)]]])
