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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-config :as config]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]))

;------------------------------------------------------------------------------ Layer 0

;; Configuration + state
(def ^{:stratum 0} default-config
  "Default PR monitor loop configuration loaded from shared EDN."
  (config/monitor-defaults))

(defn ^{:stratum 0} emit!
  "Publish an event to the configured event bus when present."
  [monitor event]
  (let [{:keys [event-bus logger]} (:config @monitor)]
    (when event-bus
      (events/publish! event-bus event logger))))

(defn ^{:stratum 0} load-budget-from-disk!
  "Load a persisted budget into monitor state when it exists. While the
   monitor has no usable state for its repo (`:state-error`: no owner/name
   slug, or unreadable watermarks), every PR gets an exhausted budget
   instead: it cannot tell which comments it already answered or fixed."
  [monitor pr-number]
  (let [{:keys [config state-error]} @monitor
        persisted (if state-error
                    (budget/exhausted-budget pr-number (:anomaly/message state-error))
                    (budget/load-budget (:repo-slug config) pr-number))]
    (when persisted
      (swap! monitor assoc-in [:budgets pr-number] persisted)
      persisted)))

(defn ^{:stratum 0} update-budget!
  "Persist the latest budget state for a PR. Keeps the merged copy that was
   written, or `budget-state` when the save fails (logged). Returns the save
   result."
  [monitor pr-number budget-state]
  (let [{:keys [repo-slug logger]} (:config @monitor)
        saved   (budget/save-budget! repo-slug budget-state)
        failed? (anomaly/anomaly? saved)]
    (swap! monitor assoc-in [:budgets pr-number] (if failed? budget-state saved))
    (when (and failed? logger)
      (log/warn logger :pr-monitor :budget/save-failed
                {:message (:anomaly/message saved)
                 :data {:pr-number pr-number}}))
    saved))

(defn ^{:stratum 0} change-budget!
  "Apply `f` to PR `pr-number`'s budget as saved on disk merged with
   `budget-state` (see `budget/change-saved-budget!`), together with any
   changes still pending from failed saves, and cache the result. Returns
   the budget written, or the anomaly.

   A refusal (an anomaly carrying a budget in [:anomaly/data :budget])
   caches that budget. Any other failure is logged and leaves the cache as
   it was; with `on-failure` :retry, `f` stays pending for the next
   successful change of this PR. The default, :block, is for reserving a
   fix attempt, which must not run unless it was saved."
  ([monitor pr-number budget-state f]
   (change-budget! monitor pr-number budget-state f :block))
  ([monitor pr-number budget-state f on-failure]
   (let [{:keys [repo-slug logger]} (:config @monitor)
         pending (get-in @monitor [:pending-budget-changes pr-number] [])
         ;; Pending changes go after `f`, so a refusal carries none of them.
         changed (budget/change-saved-budget! repo-slug budget-state
                                              #(let [result (f %)]
                                                 (if (anomaly/anomaly? result)
                                                   result
                                                   (reduce (fn [b change] (change b)) result pending))))]
     (cond
       (not (anomaly/anomaly? changed))
       (swap! monitor #(-> %
                           (assoc-in [:budgets pr-number] changed)
                           (update-in [:pending-budget-changes pr-number] (comp vec (partial drop (count pending))))))

       (get-in changed [:anomaly/data :budget])
       (swap! monitor assoc-in [:budgets pr-number] (get-in changed [:anomaly/data :budget]))

       :else
       (do (when (= :retry on-failure)
             (swap! monitor update-in [:pending-budget-changes pr-number] (fnil conj []) f))
           (when logger
             (log/warn logger :pr-monitor :budget/save-failed
                       {:message (:anomaly/message changed)
                        :data {:pr-number pr-number :pending (= :retry on-failure)}}))))
     changed)))

(defn ^{:stratum 0} log-loop-start!
  "Log loop startup when a logger is present."
  [monitor author]
  (let [{:keys [poll-interval-ms logger]} (:config @monitor)]
    (when logger
      (log/info logger :pr-monitor :loop/started
                {:message (str "PR monitor loop started for author: " author)
                 :data {:poll-interval-ms poll-interval-ms}}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-monitor
  "Create a PR monitor loop state atom from config. State on disk is kept
   per `:repo-slug` (owner/name), resolved from `:worktree-path` with gh when
   not given. `:state-error` holds the anomaly when it cannot be resolved or
   its watermarks cannot be read; see `load-budget-from-disk!`."
  [config]
  (let [merged      (merge default-config config)
        repo-slug   (or (:repo-slug merged)
                        (some-> (:worktree-path merged) poller/resolve-repo-slug))
        watermarks  (poller/load-watermarks repo-slug)
        unreadable? (anomaly/anomaly? watermarks)]
    (atom {:config (assoc merged :repo-slug repo-slug)
           :watermarks (if unreadable? (poller/empty-watermarks repo-slug) watermarks)
           :state-error (when unreadable? watermarks)
           :budgets {}
           :running? false
           :started-at nil
           :cycles 0
           :last-cycle-at nil
           :evidence {:comments-received 0
                      :comments-addressed 0
                      :fixes-pushed []
                      :questions-answered []}})))

(defn ^{:stratum 1} get-or-create-budget
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
