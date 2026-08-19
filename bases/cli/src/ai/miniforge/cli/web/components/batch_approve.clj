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
(ns ai.miniforge.cli.web.components.batch-approve
  "Batch-approve button and fleet-summary banner fragments."
  (:require
   [hiccup2.core :as h]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const batch-approve-style
  "padding: 6px 12px;")

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} batch-approve-confirm
  [count]
  (t :web-ui/batch-approve-confirm {:count count}))

(defn- ^{:stratum 1} batch-approve-label
  [count]
  (t :web-ui/batch-approve-label {:count count}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} batch-approve-safe-button
  [safe-count]
  [:button.btn.btn-success
   {:hx-post "/api/batch-approve"
    :hx-target "#toast-container"
    :hx-swap "innerHTML"
    :hx-confirm (batch-approve-confirm safe-count)
    :style batch-approve-style}
   (t :web-ui/batch-approve-safe)])

(defn ^{:stratum 2} fleet-summary [summary]
  (let [{:keys [total recommendation high-risk medium-risk low-risk]} summary
        icon (cond
               (pos? (:count high-risk)) "🚨"
               (pos? (:count medium-risk)) "📋"
               (pos? (:count low-risk)) "✅"
               :else "🎉")]
    (h/html
     [:div.fleet-summary
      [:div.fleet-summary-icon icon]
      [:div.fleet-summary-content
       [:div.fleet-summary-title (t :web-ui/open-prs-title {:count total})]
       [:div.fleet-summary-recommendation recommendation]]
      [:div.fleet-summary-actions
       (when (pos? (:count low-risk))
         [:button.btn.btn-success
          {:hx-post "/api/batch-approve"
           :hx-target "#toast-container"
           :hx-swap "innerHTML"
           :hx-confirm (batch-approve-confirm (:count low-risk))}
          (batch-approve-label (:count low-risk))])]])))
