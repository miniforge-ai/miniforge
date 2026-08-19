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
(ns ai.miniforge.cli.web.components.chat
  "AI-summary and chat-panel dashboard fragments."
  (:require
   [hiccup2.core :as h]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const ai-placeholder-style
  "color: var(--text-muted); font-style: italic; padding: 12px;")

(def ^{:stratum 0} ^:const chat-empty-style
  "color: var(--text-muted); font-size: 13px;")

(def ^{:stratum 0} ^:const chat-response-style
  "white-space: pre-wrap; word-wrap: break-word;")

(defn- ^{:stratum 0} t
  ([message-key]
   (messages/t message-key))
  ([message-key params]
   (messages/t message-key params)))

(defn- ^{:stratum 0} pr-url [repo number]
  (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} chat-message [question response]
  (h/html
   [:div
    [:div.chat-message.user question]
    [:div.chat-message.assistant
     [:pre {:style chat-response-style} response]]]))

(defn ^{:stratum 1} ai-summary [summary]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header [:span "🤖"] [:span (t :web-ui/ai-analysis)]]
    [:div.ai-summary-content (:summary summary)]]))

(defn ^{:stratum 1} ai-summary-error [message]
  (h/html
   [:div.ai-summary
    [:div.ai-summary-header [:span "⚠️"] [:span (t :web-ui/summary-unavailable)]]
    [:div.ai-summary-content {:style "color: var(--text-muted)"} message]]))

(defn ^{:stratum 1} ai-summary-placeholder [repo number]
  (h/html
   [:div
    {:hx-post (str "/api/pr/" (java.net.URLEncoder/encode repo "UTF-8") "/" number "/summary")
     :hx-trigger "load" :hx-target "this" :hx-swap "outerHTML"}
    [:div {:style ai-placeholder-style}
     (t :web-ui/ai-summary-loading)]]))

(defn- ^{:stratum 1} quick-question-buttons
  [repo number]
  (for [{:keys [label prompt]} (t :web-ui/chat-quick-questions)]
    [:button.quick-question
     {:hx-post (str (pr-url repo number) "/chat")
      :hx-target "#chat-messages"
      :hx-swap "beforeend"
      :hx-vals (str "{\"question\": \"" prompt "\"}")}
     label]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} chat-section
  [repo number]
  [:div.chat-section
   [:div.chat-header
    [:span "💬"]
    [:span (t :web-ui/chat-heading)]]
   [:div#chat-messages.chat-messages
    [:div {:style chat-empty-style}
     (t :web-ui/chat-empty-state)]]
   [:div.quick-questions
    (quick-question-buttons repo number)]
   [:form.chat-input-container
    {:hx-post (str (pr-url repo number) "/chat")
     :hx-target "#chat-messages"
     :hx-swap "beforeend"}
    [:input.chat-input
     {:type "text"
      :name "question"
      :placeholder (t :web-ui/chat-placeholder)
      :autocomplete "off"}]
    [:button.btn.btn-primary {:type "submit"}
     (t :web-ui/chat-submit-button)]]])
