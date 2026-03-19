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

(ns ai.miniforge.workflow.dag-monitor
  "Monitors a PR train assembled from DAG task results.

   For each PR in merge order:
   - Creates a pr-lifecycle controller
   - Monitors CI → on failure: runs fix loop
   - Monitors review → on changes-requested: runs fix loop
   - On approved + CI green: attempts merge respecting train ordering

   The train provides merge-ordering coordination. Each PR gets its own
   controller. Monitoring is a loop over controllers with a train gate."
  (:require
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Controller creation

(defn create-pr-controllers
  "Create a pr-lifecycle controller for each PR in the train.

   Arguments:
   - pr-infos: Vector of PR info maps from DAG result
   - context: Execution context with :worktree-path, :logger, :generate-fn, etc.

   Returns map of pr-number -> controller atom."
  [pr-infos context]
  (let [dag-id (or (:dag-id context) (random-uuid))
        run-id (or (:run-id context) (random-uuid))
        event-bus (or (:event-bus context)
                      (pr-lifecycle/create-event-bus))]
    (->> pr-infos
         (map (fn [{:keys [pr-number task-id]}]
                [pr-number
                 (pr-lifecycle/create-controller
                   dag-id run-id task-id
                   {:task/id task-id
                    :task/title (str "Task " (when task-id (subs (str task-id) 0 8)))}
                   :worktree-path (:worktree-path context)
                   :event-bus event-bus
                   :logger (:logger context)
                   :generate-fn (:generate-fn context)
                   :merge-policy (:merge-policy context)
                   :max-fix-iterations (get context :max-fix-iterations 5))]))
         (into {}))))

;------------------------------------------------------------------------------ Layer 1
;; Train monitoring loop

(defn pr-terminal?
  "Check if a controller is in a terminal state."
  [controller]
  (#{:merged :failed} (:status @controller)))

(defn all-prs-terminal?
  "Check if all PR controllers are in terminal states."
  [controllers]
  (every? pr-terminal? (vals controllers)))

(defn all-prs-merged?
  "Check if all PR controllers are merged."
  [controllers]
  (every? #(= :merged (:status @%)) (vals controllers)))

(defn monitor-pr-train
  "Monitor all PRs in a train through CI, review, and merge.

   For each monitoring cycle:
   1. Check which PRs are ready to merge (train gate)
   2. For ready PRs, attempt merge via their controllers
   3. For non-ready PRs, continue CI/review monitoring
   4. Run fix loops as needed

   Arguments:
   - train-state: Map with :train-id :manager :train from dag-train assembly
   - controllers: Map of pr-number -> controller atom
   - context: Execution context

   Returns:
   {:success? boolean
    :merged-prs [pr-numbers]
    :failed-prs [pr-numbers]
    :train final-train-state}"
  [train-state controllers context]
  (let [{:keys [train-id manager]} train-state
        logger (:logger context)
        max-cycles (get context :max-monitor-cycles 100)
        poll-interval-ms (get context :monitor-poll-interval-ms 30000)]

    (when logger
      (log/info logger :dag-monitor :monitor/starting
                {:data {:train-id train-id
                        :pr-count (count controllers)}}))

    (loop [cycle 0]
      (cond
        ;; All done
        (all-prs-terminal? controllers)
        (let [merged (->> controllers
                          (filter (fn [[_ ctrl]] (= :merged (:status @ctrl))))
                          (map first) vec)
              failed (->> controllers
                          (filter (fn [[_ ctrl]] (= :failed (:status @ctrl))))
                          (map first) vec)
              train (pr-train/get-train manager train-id)]
          (when logger
            (log/info logger :dag-monitor :monitor/completed
                      {:data {:merged (count merged)
                              :failed (count failed)
                              :cycles cycle}}))
          {:success? (empty? failed)
           :merged-prs merged
           :failed-prs failed
           :train train})

        ;; Safety valve
        (>= cycle max-cycles)
        (do
          (when logger
            (log/warn logger :dag-monitor :monitor/max-cycles
                      {:message "Max monitoring cycles exceeded"
                       :data {:cycles cycle}}))
          {:success? false
           :error "Max monitoring cycles exceeded"
           :train (pr-train/get-train manager train-id)})

        ;; Monitor cycle
        :else
        (do
          ;; Check which PRs are ready to merge per train ordering
          (let [ready-prs (pr-train/get-ready-to-merge manager train-id)]
            (doseq [pr-num ready-prs]
              (when-let [ctrl (get controllers pr-num)]
                (when (= :ready-to-merge (:status @ctrl))
                  (try
                    (pr-lifecycle/attempt-merge! ctrl)
                    ;; Update train state on successful merge
                    (when (= :merged (:status @ctrl))
                      (pr-train/complete-merge manager train-id pr-num))
                    (catch Exception e
                      (when logger
                        (log/warn logger :dag-monitor :monitor/merge-error
                                  {:message "Merge attempt failed"
                                   :data {:pr-number pr-num
                                          :error (.getMessage e)}}))
                      (pr-train/fail-merge manager train-id pr-num
                                            (.getMessage e))))))))

          ;; Wait before next cycle
          (Thread/sleep poll-interval-ms)
          (recur (inc cycle)))))))

;------------------------------------------------------------------------------ Layer 2
;; Convenience entry point

(defn monitor-dag-prs
  "Top-level entry point: create controllers and monitor the train.

   Arguments:
   - train-state: From dag-train/create-train-from-dag-result
   - pr-infos: PR info vector from DAG result
   - context: Execution context

   Returns monitoring result map."
  [train-state pr-infos context]
  (let [controllers (create-pr-controllers pr-infos context)]
    ;; Set PR info on each controller (PR already exists, skip creation)
    (doseq [{:keys [pr-number pr-url branch]} pr-infos]
      (when-let [ctrl (get controllers pr-number)]
        (swap! ctrl assoc :pr {:pr/id pr-number
                               :pr/url pr-url
                               :pr/branch branch})
        (swap! ctrl assoc :status :monitoring-ci)))
    (monitor-pr-train train-state controllers context)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example usage (conceptual — needs real controllers)
  ;; (def train-state (dag-train/create-train-from-dag-result dag-result plan-tasks))
  ;; (def result (monitor-dag-prs train-state (:pr-infos dag-result) context))
  :leave-this-here)
