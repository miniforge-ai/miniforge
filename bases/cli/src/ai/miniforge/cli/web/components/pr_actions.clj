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
(ns ai.miniforge.cli.web.components.pr-actions
  "PR detail header and action-button fragments."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.web.components.pr-stats :as pr-stats]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

(defn- ^{:stratum 0} pr-url [repo number]
  (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} detail-header
  [risk-level number repo author additions deletions]
  (let [author-login (get author :login (t :web-ui/unknown-author))]
    [:div.detail-header
     [:div.detail-title
      [:span.risk-badge {:class (name risk-level)}
       (case risk-level :low "●" :medium "◐" :high "◉")
       (pr-stats/risk-label risk-level)]
      (t :web-ui/pr-number {:number number})]
     [:div.detail-meta
      [:span (t :web-ui/repo-meta {:repo repo})]
      [:span (t :web-ui/author-meta {:author author-login})]
      [:span (t :web-ui/change-meta {:additions additions :deletions deletions})]]]))

(defn- ^{:stratum 1} action-buttons
  [repo number url]
  [:div.actions
   [:button.btn.btn-success
    {:hx-post (str (pr-url repo number) "/approve")
     :hx-target "#toast-container"
     :hx-swap "innerHTML"}
    (t :web-ui/approve-button)]
   [:button.btn.btn-danger
    {:hx-post (str (pr-url repo number) "/reject")
     :hx-target "#toast-container"
     :hx-swap "innerHTML"
     :hx-prompt (t :web-ui/reject-prompt)}
    (t :web-ui/reject-button)]
   [:a.btn.btn-secondary {:href url :target "_blank"}
    (t :web-ui/open-github-button)]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} detail-actions
  [repo number url]
  [:div.section
   [:div.section-title (t :web-ui/actions-heading)]
   (action-buttons repo number url)])
