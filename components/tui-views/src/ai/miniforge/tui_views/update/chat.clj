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

(ns ai.miniforge.tui-views.update.chat
  "Chat mode: model transforms for the conversational interface.

   Chat mode provides a natural-language interface within the TUI for
   interacting with miniforge about PRs. Available in :pr-fleet and
   :pr-detail views.

   Layer 2."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.effect :as effect]
   [ai.miniforge.tui-views.transition :as transition]))

;------------------------------------------------------------------------------ Layer 0
;; Thread key derivation

(defn chat-thread-key
  "Derive a stable thread key from the current view context.
   Each PR or workflow gets its own conversation thread."
  [model]
  (case (:view model)
    :pr-detail (let [pr (get-in model [:detail :selected-pr])]
                 [:pr (:pr/repo pr) (:pr/number pr)])
    :pr-fleet  [:fleet]
    [:global]))

(def ^:private empty-thread
  {:messages [] :input-buf "" :context {} :pending? false :suggested-actions []})

;; Context builders

(def chat-views
  "Views where chat mode is available."
  #{:pr-fleet :pr-detail})

(defn pr-detail-context
  "Build chat context from a PR detail view."
  [model]
  (let [pr (get-in model [:detail :selected-pr])]
    {:type      :pr-detail
     :pr        pr
     :readiness (:pr/readiness pr)
     :risk      (:pr/risk pr)
     :policy    (:pr/policy pr)}))

(defn pr-fleet-context
  "Build chat context from a PR fleet view."
  [model]
  (let [sel-ids (:selected-ids model #{})
        prs     (if (seq sel-ids)
                  (->> (:pr-items model [])
                       (filter #(contains? sel-ids [(:pr/repo %) (:pr/number %)]))
                       vec)
                  [])]
    {:type          :pr-fleet
     :selected-prs  prs
     :active-filter (:active-filter model)
     :total-prs     (count (:pr-items model []))}))

(defn build-context
  "Build chat context from the current view."
  [model]
  (case (:view model)
    :pr-detail (pr-detail-context model)
    :pr-fleet  (pr-fleet-context model)
    {:type :unknown :view (:view model)}))

;------------------------------------------------------------------------------ Layer 1
;; Mode transitions

(defn enter
  "Enter chat mode. Loads or creates thread for current context."
  [model]
  (if (chat-views (:view model))
    (let [tk      (chat-thread-key model)
          thread  (get-in model [:chat-threads tk] empty-thread)
          context (build-context model)]
      (-> model
          (assoc :mode :chat :command-buf "chat> " :chat-active-key tk)
          (assoc :chat (-> thread
                           (assoc :context context)
                           (assoc :input-buf "")
                           (assoc :pending? false)))))
    (transition/flash model "Chat available in PR Fleet or PR Detail views")))

(defn escape
  "Exit chat mode. Saves thread back to chat-threads."
  [model]
  (let [tk (get model :chat-active-key)]
    (cond-> (assoc model :mode :normal :command-buf "")
      tk (assoc-in [:chat-threads tk] (:chat model)))))

;------------------------------------------------------------------------------ Layer 2
;; Input handling

(defn sync-command-buf
  "Keep :command-buf in sync with chat input for the command bar overlay."
  [model]
  (assoc model :command-buf (str "chat> " (get-in model [:chat :input-buf] ""))))

(defn append
  "Append character to chat input buffer."
  [model ch]
  (-> model
      (update-in [:chat :input-buf] str ch)
      sync-command-buf))

(defn backspace
  "Backspace in chat input buffer."
  [model]
  (let [buf (get-in model [:chat :input-buf] "")]
    (if (pos? (count buf))
      (-> model
          (assoc-in [:chat :input-buf] (subs buf 0 (dec (count buf))))
          sync-command-buf)
      model)))

(defn send-message
  "Send the current chat input as a user message.
   Appends to messages, clears input, sets pending, fires side-effect."
  [model]
  (let [msg (str/trim (get-in model [:chat :input-buf] ""))]
    (if (str/blank? msg)
      model
      (let [user-msg {:role :user :content msg :timestamp (java.util.Date.)}
            messages (conj (get-in model [:chat :messages] []) user-msg)
            context  (get-in model [:chat :context] {})]
        (-> model
            (assoc-in [:chat :messages] messages)
            (assoc-in [:chat :input-buf] "")
            (assoc-in [:chat :pending?] true)
            (assoc-in [:chat :pending-since] (System/currentTimeMillis))
            sync-command-buf
            (assoc :side-effect (effect/chat-send context msg messages)))))))

(defn execute-action
  "Execute a suggested action by index (0-based).
   Fires a :chat-execute-action side-effect."
  [model idx]
  (let [actions (get-in model [:chat :suggested-actions] [])
        context (get-in model [:chat :context] {})]
    (if-let [action (get actions idx)]
      (-> model
          (assoc :side-effect {:type :chat-execute-action
                               :action action
                               :context context})
          (assoc :flash-message (str "Executing: " (:label action))))
      (transition/flash model "No action at that index"))))

(defn scroll-up
  "Scroll the chat panel up by one line."
  [model]
  (update-in model [:chat :scroll-offset] #(max 0 (dec (or % 0)))))

(defn scroll-down
  "Scroll the chat panel down by one line."
  [model]
  (update-in model [:chat :scroll-offset] #(inc (or % 0))))

(defn scroll-bottom
  "Scroll the chat panel to the bottom (latest messages)."
  [model]
  (assoc-in model [:chat :scroll-offset] nil))
