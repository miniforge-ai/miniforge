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

(ns ai.miniforge.pr-lifecycle.monitor-state
  "Shared monitor loop state and persistence helpers."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-config :as config]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration + state

(def default-config
  "Default PR monitor loop configuration loaded from shared EDN."
  (config/monitor-defaults))

(defn create-monitor
  "Create a PR monitor loop state atom from config."
  [config]
  (let [merged (merge default-config config)]
    (atom {:config merged
           :watermarks (poller/load-watermarks)
           :budgets {}
           :running? false
           :started-at nil
           :cycles 0
           :last-cycle-at nil
           :evidence {:comments-received 0
                      :comments-addressed 0
                      :fixes-pushed []
                      :questions-answered []}})))

;------------------------------------------------------------------------------ Layer 1
;; Shared helpers

(declare load-budget-from-disk!)

(defn emit!
  "Publish an event to the configured event bus when present."
  [monitor event]
  (let [{:keys [event-bus logger]} (:config @monitor)]
    (when event-bus
      (events/publish! event-bus event logger))))

(defn get-or-create-budget
  "Get an existing budget for a PR, loading from disk when available."
  [monitor pr-number]
  (or (get-in @monitor [:budgets pr-number])
      (when-let [persisted (load-budget-from-disk! monitor pr-number)]
        persisted)
      (let [created (budget/create-budget
                     pr-number
                     (select-keys (:config @monitor)
                                  [:max-fix-attempts-per-comment
                                   :max-total-fix-attempts-per-pr
                                   :abandon-after-hours]))]
        (swap! monitor assoc-in [:budgets pr-number] created)
        created)))

(defn load-budget-from-disk!
  "Load a persisted budget into monitor state when it exists."
  [monitor pr-number]
  (when-let [persisted (budget/load-budget pr-number)]
    (swap! monitor assoc-in [:budgets pr-number] persisted)
    persisted))

(defn update-budget!
  "Persist the latest budget state for a PR."
  [monitor pr-number budget-state]
  (swap! monitor assoc-in [:budgets pr-number] budget-state)
  (budget/save-budget! budget-state))

(defn log-loop-start!
  "Log loop startup when a logger is present."
  [monitor author]
  (let [{:keys [poll-interval-ms logger]} (:config @monitor)]
    (when logger
      (log/info logger :pr-monitor :loop/started
                {:message (str "PR monitor loop started for author: " author)
                 :data {:poll-interval-ms poll-interval-ms}}))))
