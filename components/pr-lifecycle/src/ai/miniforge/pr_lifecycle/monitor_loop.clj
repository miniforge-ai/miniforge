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
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]))

;------------------------------------------------------------------------------ Layer 0
;; API compatibility

(def create-monitor
  "Compatibility alias for monitor creation."
  state/create-monitor)

;------------------------------------------------------------------------------ Layer 1
;; Single cycle

(defn run-cycle
  "Run one poll → classify → route cycle for a single PR."
  [monitor pr-info]
  (let [{:keys [worktree-path self-author generate-fn logger]} (:config @monitor)
        pr-number (:pr/number pr-info)
        watermarks (:watermarks @monitor)
        pr-budget (state/get-or-create-budget monitor pr-number)]
    (if (budget/time-budget-exhausted? pr-budget)
      (do
        (state/emit! monitor (mevents/budget-exhausted pr-number
                               (assoc (budget/budget-summary pr-budget)
                                      :exhausted-by :time-limit)))
        (state/emit! monitor (mevents/escalated pr-number :time-limit
                               {:budget-summary (budget/budget-summary pr-budget)}))
        {:processed 0 :results [] :new-comments 0
         :stopped? true :stop-reason :time-budget-exhausted})
      (let [poll-result (poller/poll-pr-for-new-comments
                         worktree-path pr-number watermarks logger)]
        (if (dag/err? poll-result)
          {:processed 0 :results [] :new-comments 0
           :error (:error poll-result)}
          (let [{:keys [new-comments watermarks]} (:data poll-result)
                _ (swap! monitor assoc :watermarks watermarks)
                _ (poller/save-watermarks! watermarks)
                _ (swap! monitor update-in [:evidence :comments-received]
                         + (count new-comments))
                _ (state/emit! monitor
                               (mevents/poll-completed pr-number
                                                       {:new-comment-count
                                                        (count new-comments)}))
                classified (when (seq new-comments)
                             (classifier/classify-comments new-comments
                               :generate-fn generate-fn
                               :self-author self-author))
                _ (doseq [classification (:all classified)]
                    (state/emit! monitor
                                 (mevents/comment-received pr-number
                                                           (:comment classification)))
                    (state/emit! monitor
                                 (mevents/comment-classified
                                  pr-number
                                  (:comment classification)
                                  {:category (:category classification)
                                   :confidence (:confidence classification)
                                   :method (:method classification)})))
                actionable (concat (:change-requests classified)
                                   (:questions classified))
                results (mapv #(handlers/route-comment monitor pr-info %) actionable)]
            (state/emit! monitor
                         (mevents/cycle-completed pr-number
                                                  {:new-comments (count new-comments)
                                                   :classified-stats (:stats classified)
                                                   :actions-taken (count results)}))
            {:processed (count results)
             :results results
             :new-comments (count new-comments)
             :classified-stats (:stats classified)}))))))

;------------------------------------------------------------------------------ Layer 2
;; Loop lifecycle

(defn run-monitor-loop
  "Run the PR monitor loop continuously until stopped."
  [monitor author]
  (let [{:keys [poll-interval-ms logger worktree-path]} (:config @monitor)]
    (swap! monitor assoc :running? true :started-at (java.util.Date.))
    (state/log-loop-start! monitor author)
    (loop []
      (when (:running? @monitor)
        (let [pr-result (poller/poll-open-prs worktree-path author)]
          (cond
            (dag/err? pr-result)
            (do
              (when logger
                (log/warn logger :pr-monitor :loop/poll-prs-failed
                          {:message "Failed to poll open PRs — retrying"
                           :data {:error (:error pr-result)}}))
              (Thread/sleep poll-interval-ms)
              (recur))

            (empty? (:prs (:data pr-result)))
            (do
              (when logger
                (log/info logger :pr-monitor :loop/no-open-prs
                          {:message "No open PRs found — stopping monitor loop"}))
              (swap! monitor assoc :running? false))

            :else
            (let [prs (:prs (:data pr-result))]
              (doseq [pr prs]
                (when (:running? @monitor)
                  (try
                    (let [cycle-result (run-cycle monitor pr)]
                      (when (and (:stopped? cycle-result) logger)
                        (log/info logger :pr-monitor :loop/pr-budget-stopped
                                  {:message (str "PR #" (:pr/number pr)
                                                 " stopped: "
                                                 (:stop-reason cycle-result))})))
                    (catch Exception e
                      (when logger
                        (log/error logger :pr-monitor :loop/cycle-error
                                   {:message "Error in monitor cycle"
                                    :data {:pr-number (:pr/number pr)
                                           :error (.getMessage e)}})))))
              (swap! monitor (fn [state-map]
                               (-> state-map
                                   (update :cycles inc)
                                   (assoc :last-cycle-at (java.util.Date.)))))
              (when (:running? @monitor)
                (Thread/sleep poll-interval-ms)
                (recur))))))))
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
