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
;; Context builders

(def ^:private chat-views
  "Views where chat mode is available."
  #{:pr-fleet :pr-detail})

(defn- pr-detail-context
  "Build chat context from a PR detail view."
  [model]
  (let [pr (get-in model [:detail :selected-pr])]
    {:type      :pr-detail
     :pr        pr
     :readiness (:pr/readiness pr)
     :risk      (:pr/risk pr)
     :policy    (:pr/policy pr)}))

(defn- pr-fleet-context
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

(defn- build-context
  "Build chat context from the current view."
  [model]
  (case (:view model)
    :pr-detail (pr-detail-context model)
    :pr-fleet  (pr-fleet-context model)
    {:type :unknown :view (:view model)}))

;------------------------------------------------------------------------------ Layer 1
;; Mode transitions

(defn enter
  "Enter chat mode. Builds context from current view."
  [model]
  (if (chat-views (:view model))
    (-> model
        (assoc :mode :chat :command-buf "chat> ")
        (assoc-in [:chat :context] (build-context model))
        (assoc-in [:chat :input-buf] "")
        (assoc-in [:chat :pending?] false))
    (transition/flash model "Chat available in PR Fleet or PR Detail views")))

(defn escape
  "Exit chat mode back to normal."
  [model]
  (assoc model :mode :normal :command-buf ""))

;------------------------------------------------------------------------------ Layer 2
;; Input handling

(defn- sync-command-buf
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
            (assoc :side-effect (effect/chat-send context msg messages)))))))
