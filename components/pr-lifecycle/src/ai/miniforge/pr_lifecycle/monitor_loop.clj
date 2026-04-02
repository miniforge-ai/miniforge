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

(ns ai.miniforge.pr-lifecycle.monitor-loop
  "PR monitor loop orchestration."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.classifier :as classifier]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-events :as mevents]
   [ai.miniforge.pr-lifecycle.monitor-handlers :as handlers]
   [ai.miniforge.pr-lifecycle.monitor-state :as state]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; API compatibility + schemas

(def create-monitor
  "Compatibility alias for monitor creation."
  state/create-monitor)

(def CycleResult
  [:map
   [:processed nat-int?]
   [:results [:vector any?]]
   [:new-comments nat-int?]
   [:stopped? {:optional true} :boolean]
   [:stop-reason {:optional true} keyword?]
   [:error {:optional true} any?]
   [:classified-stats {:optional true} map?]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

;------------------------------------------------------------------------------ Layer 1
;; Single cycle helpers

(defn- cycle-result
  [result]
  (validate! CycleResult result))

(defn- time-budget-stop-result
  [_pr-number]
  (cycle-result {:processed 0
                 :results []
                 :new-comments 0
                 :stopped? true
                 :stop-reason :time-budget-exhausted}))

(defn- poll-error-result
  [poll-result]
  (cycle-result {:processed 0
                 :results []
                 :new-comments 0
                 :error (:error poll-result)}))

(defn- emit-time-budget-stop!
  [monitor pr-number pr-budget]
  (let [summary (budget/budget-summary pr-budget)]
    (state/emit! monitor (mevents/budget-exhausted pr-number
                                                   (assoc summary :exhausted-by :time-limit)))
    (state/emit! monitor (mevents/escalated pr-number :time-limit
                                            {:budget-summary summary}))))

(defn- classify-comments
  [new-comments generate-fn self-author]
  (if (seq new-comments)
    (classifier/classify-comments new-comments
                                  :generate-fn generate-fn
                                  :self-author self-author)
    {:change-requests []
     :questions []
     :approvals []
     :bot-comments []
     :noise []
     :all []
     :stats {:total 0
             :change-requests 0
             :questions 0
             :approvals 0
             :bot-comments 0
             :noise 0}}))

(defn- actionable-comments
  [classified]
  (concat (:change-requests classified)
          (:questions classified)))

(defn- emit-classification-events!
  [monitor pr-number classified]
  (doseq [classification (:all classified)]
    (state/emit! monitor
                 (mevents/comment-received pr-number
                                           (:comment classification)))
    (state/emit! monitor
                 (mevents/comment-classified pr-number
                                             (:comment classification)
                                             {:category (:category classification)
                                              :confidence (:confidence classification)
                                              :method (:method classification)}))))

(defn- persist-poll-state!
  [monitor pr-number watermarks new-comments]
  (swap! monitor assoc :watermarks watermarks)
  (poller/save-watermarks! watermarks)
  (swap! monitor update-in [:evidence :comments-received] + (count new-comments))
  (state/emit! monitor
               (mevents/poll-completed pr-number
                                       {:new-comment-count (count new-comments)})))

(defn- route-actionable-comments
  [monitor pr-info classified]
  (into [] (map #(handlers/route-comment monitor pr-info %))
        (actionable-comments classified)))

(defn- completed-cycle-result
  [pr-number new-comments classified results]
  (let [classified-stats (:stats classified)]
    (cycle-result {:processed (count results)
                   :results results
                   :new-comments (count new-comments)
                   :classified-stats classified-stats})))

(defn run-cycle
  "Run one poll → classify → route cycle for a single PR."
  [monitor pr-info]
  (let [{:keys [worktree-path self-author generate-fn logger]} (:config @monitor)
        pr-number  (:pr/number pr-info)
        watermarks (:watermarks @monitor)
        pr-budget  (state/get-or-create-budget monitor pr-number)]
    (if (budget/time-budget-exhausted? pr-budget)
      (do
        (emit-time-budget-stop! monitor pr-number pr-budget)
        (time-budget-stop-result pr-number))
      (let [poll-result (poller/poll-pr-for-new-comments
                         worktree-path pr-number watermarks logger)]
        (if (dag/err? poll-result)
          (poll-error-result poll-result)
          (let [{:keys [new-comments watermarks]} (:data poll-result)
                _          (persist-poll-state! monitor pr-number watermarks new-comments)
                classified (classify-comments new-comments generate-fn self-author)
                _          (emit-classification-events! monitor pr-number classified)
                results    (route-actionable-comments monitor pr-info classified)]
            (state/emit! monitor
                         (mevents/cycle-completed pr-number
                                                  {:new-comments (count new-comments)
                                                   :classified-stats (:stats classified)
                                                   :actions-taken (count results)}))
            (completed-cycle-result pr-number new-comments classified results)))))))

;------------------------------------------------------------------------------ Layer 2
;; Loop lifecycle

(declare step-monitor-loop!)

(defn- retry-after-poll-error!
  [monitor author logger poll-interval-ms pr-result]
  (when logger
    (log/warn logger :pr-monitor :loop/poll-prs-failed
              {:message "Failed to poll open PRs — retrying"
               :data {:error (:error pr-result)}}))
  (Thread/sleep poll-interval-ms)
  (step-monitor-loop! monitor author))

(defn- stop-when-no-open-prs!
  [monitor logger]
  (when logger
    (log/info logger :pr-monitor :loop/no-open-prs
              {:message "No open PRs found — stopping monitor loop"}))
  (swap! monitor assoc :running? false))

(defn- log-cycle-stop
  [logger pr cycle-result]
  (when (and (:stopped? cycle-result) logger)
    (log/info logger :pr-monitor :loop/pr-budget-stopped
              {:message (str "PR #" (:pr/number pr)
                             " stopped: "
                             (:stop-reason cycle-result))})))

(defn- run-pr-cycle!
  [monitor logger pr]
  (when (:running? @monitor)
    (try
      (log-cycle-stop logger pr (run-cycle monitor pr))
      (catch Exception e
        (when logger
          (log/error logger :pr-monitor :loop/cycle-error
                     {:message "Error in monitor cycle"
                      :data {:pr-number (:pr/number pr)
                             :error (.getMessage e)}}))))))

(defn- finalize-loop-iteration!
  [monitor]
  (swap! monitor (fn [state-map]
                   (-> state-map
                       (update :cycles inc)
                       (assoc :last-cycle-at (java.util.Date.))))))

(defn- continue-loop!
  [monitor author poll-interval-ms]
  (when (:running? @monitor)
    (Thread/sleep poll-interval-ms)
    (step-monitor-loop! monitor author)))

(defn- step-monitor-loop!
  [monitor author]
  (let [{:keys [poll-interval-ms logger worktree-path]} (:config @monitor)]
    (when (:running? @monitor)
      (let [pr-result (poller/poll-open-prs worktree-path author)]
        (cond
          (dag/err? pr-result)
          (retry-after-poll-error! monitor author logger poll-interval-ms pr-result)

          (empty? (:prs (:data pr-result)))
          (stop-when-no-open-prs! monitor logger)

          :else
          (let [prs (:prs (:data pr-result))]
            (run! #(run-pr-cycle! monitor logger %) prs)
            (finalize-loop-iteration! monitor)
            (continue-loop! monitor author poll-interval-ms)))))))

(defn run-monitor-loop
  "Run the PR monitor loop continuously until stopped."
  [monitor author]
  (let [{:keys [logger]} (:config @monitor)]
    (swap! monitor assoc :running? true :started-at (java.util.Date.))
    (state/log-loop-start! monitor author)
    (step-monitor-loop! monitor author)
    (when logger
      (log/info logger :pr-monitor :loop/stopped
                {:message "PR monitor loop stopped"
                 :data {:cycles (:cycles @monitor)}}))
    (let [evidence (:evidence @monitor)]
      (merge evidence
             {:cycles (:cycles @monitor)
              :duration-hours (when-let [started (:started-at @monitor)]
                                (/ (double (- (System/currentTimeMillis)
                                              (.getTime ^java.util.Date started)))
                                   3600000.0))}))))

(defn stop-monitor-loop
  "Stop a running monitor loop gracefully."
  [monitor]
  (swap! monitor assoc :running? false))

(defn monitor-running?
  "Check if the monitor loop is currently running."
  [monitor]
  (:running? @monitor))

(defn monitor-evidence
  "Get current evidence from the monitor."
  [monitor]
  (:evidence @monitor))
