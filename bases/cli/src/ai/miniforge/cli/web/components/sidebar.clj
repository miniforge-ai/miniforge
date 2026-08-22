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
(ns ai.miniforge.cli.web.components.sidebar
  "Repository/PR sidebar tree fragments."
  (:require
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const selected-class
  "selected")

(def ^{:stratum 0} ^:const sidebar-refresh-style
  "padding: 4px 8px; font-size: 11px;")

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

(defn- ^{:stratum 0} pr-url [repo number]
  (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number))

(defn- ^{:stratum 0} repo-item-selected?
  [selected-pr repo number]
  (and selected-pr
       (= (:repo selected-pr) repo)
       (= (:number selected-pr) number)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sidebar-header
  []
  [:div.sidebar-header
   [:span (t :web-ui/repositories-heading)]
   [:button.btn.btn-secondary
    {:hx-get "/api/refresh"
     :hx-target "#main-content"
     :hx-swap "innerHTML"
     :style sidebar-refresh-style}
    (t :web-ui/refresh-button)]])

(defn- ^{:stratum 1} repo-pr-item
  [repo selected-pr {:keys [number title analysis]}]
  (let [selected? (repo-item-selected? selected-pr repo number)
        item-class (when selected? selected-class)]
    [:div.pr-item
     {:class item-class
      :hx-get (pr-url repo number)
      :hx-target "#detail-panel"
      :hx-swap "innerHTML"}
     [:span.pr-risk-dot {:class (str "pr-risk-" (name (:risk analysis)))}]
     [:span.pr-number (str "#" number)]
     [:span.pr-title title]]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} repo-group
  [selected-pr {:keys [repo prs]}]
  [:div.repo-group
   [:div.repo-header.expanded
    [:span.repo-icon "📦"]
    [:span.repo-name repo]
    [:span.repo-count (count prs)]]
   [:div.pr-list
    (for [pr prs]
      (repo-pr-item repo selected-pr pr))]])
