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
(ns ai.miniforge.cli.web.components.pr-analysis
  "AI-analysis section assembly for the PR detail panel."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.web.components.chat :as chat]
   [ai.miniforge.cli.web.components.pr-stats :as pr-stats]
   [ai.miniforge.cli.web.risk :as risk]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const factors-style
  "margin-top: 8px; color: var(--text-secondary);")

(def ^{:stratum 0} ^:const summary-message-style
  "margin-top: 16px;")

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recommendation-box
  [risk-level suggested-action]
  (let [background-color (get risk/bg-colors risk-level)
        style-value (str "margin-top: 16px; padding: 12px; border-radius: 6px; background: "
                         background-color)]
    [:div {:style style-value}
     [:strong (t :web-ui/recommendation-prefix)]
     suggested-action]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ai-analysis-section
  [repo number {:keys [risk complexity summary suggested-action reasons total-changes file-count]}]
  [:div.section
   [:div.section-title (t :web-ui/ai-analysis)]
   (pr-stats/analysis-stats risk complexity total-changes file-count)
   [:div {:style summary-message-style}
    [:strong (t :web-ui/summary-prefix)]
    summary]
   (when (seq reasons)
     [:div {:style factors-style}
      [:strong (t :web-ui/factors-prefix)]
      (str/join ", " reasons)])
   (recommendation-box risk suggested-action)
   [:div#ai-summary-container (chat/ai-summary-placeholder repo number)]])
