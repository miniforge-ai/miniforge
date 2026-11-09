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
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.merge-resolution :as merge-resolution]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.stop-signal :as stop-signal]))

;------------------------------------------------------------------------------ Layer 0

;; Results, predicates, controllers and one monitor cycle
(defn ^{:stratum 0} monitor-error
  "Construct a monitor error result map."
  [error train]
  {:success? false :error error :train train})

(defn ^{:stratum 0} all-prs-terminal?
  "Check if all PR controllers are in terminal states (merged or failed)."
  [controllers]
  (every? #(#{:merged :failed} (:status @%)) (vals controllers)))

(defn ^{:stratum 0} create-pr-controllers
  "Create a pr-lifecycle controller for each PR in the train.

   Arguments:
   - pr-infos: Vector of PR info maps from DAG result
   - context: Execution context with :worktree-path, :logger, :generate-fn, etc.

   Returns map of pr-number -> controller atom."
  [pr-infos context]
  (let [dag-id (or (:dag-id context) (random-uuid))
        run-id (or (:run-id context) (random-uuid))
        event-bus (or (:event-bus context)
                      (pr-lifecycle/create-event-bus))
        ;; Spec §6.4 hook: when the PR-lifecycle merge step finds a
        ;; CONFLICTING branch, dispatch into merge-resolution/
        ;; resolve-conflict! with an LLM-driven agent-edit-fn.
        ;;
        ;; Only construct/inject :resolve-fn when context carries
        ;; :llm-backend. Without a backend, merge-resolution would
        ;; fall back to its no-op stub agent and deterministically
        ;; return an unresolvable anomaly — the lifecycle would
        ;; then attempt resolution every cycle for no benefit.
        ;; Skipping injection lets the conflict-resolution dispatch
        ;; decline cleanly and the existing rebase / not-ready path
        ;; handles the PR. Callers that DO have a backend
        ;; (workflow's dag-orchestrator path) get the auto-engage
        ;; behaviour; callers that don't (e.g. phase-software-
        ;; factory's pr_monitor) keep the existing semantics.
        llm-backend (:llm-backend context)
        logger      (:logger context)
        resolve-fn  (when llm-backend
                      (let [agent-edit-fn
                            (merge-resolution/agent-driven-edit-fn
                             (cond-> {:llm-backend llm-backend}
                               logger (assoc :logger logger)))]
                        (fn [opts]
                          (merge-resolution/resolve-conflict!
                           (assoc opts :agent-edit-fn agent-edit-fn)))))]
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
                   :resolve-fn resolve-fn
                   :max-fix-iterations (get context :max-fix-iterations 5))]))
         (into {}))))

(defn ^{:stratum 0} build-monitor-result
  "Build the final monitor result when all PRs have reached terminal states.

   Partitions controllers into merged and failed, then returns
   `{:success? :merged-prs :failed-prs :train}`."
  [controllers manager train-id logger cycle]
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
    {:success? (empty? failed) :merged-prs merged :failed-prs failed :train train}))

(defn ^{:stratum 0} execute-monitor-cycle!
  "Execute one monitor cycle: check ready PRs and attempt merges.

   For each PR that the train gate marks as ready-to-merge and whose
   controller is in :ready-to-merge status, attempts the merge and
   updates train state accordingly. Once `control-state` is stopped, no
   further merge is attempted."
  [controllers manager train-id logger control-state]
  (let [ready-prs (pr-train/get-ready-to-merge manager train-id)]
    (doseq [pr-num ready-prs
            :while (not (stop-signal/stopped? control-state))]
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
                                    (.getMessage e)))))))))

;------------------------------------------------------------------------------ Layer 1

;; Train monitoring loop
(defn ^{:stratum 1} monitor-pr-train
  "Monitor all PRs in a train through CI, review, and merge.

   For each monitoring cycle:
   1. Check which PRs are ready to merge (train gate)
   2. For ready PRs, attempt merge via their controllers
   3. For non-ready PRs, continue CI/review monitoring
   4. Run fix loops as needed

   Arguments:
   - train-state: Map with :train-id :manager :train from dag-train assembly
   - controllers: Map of pr-number -> controller atom
   - context: Execution context. Its `:control-state` stops the loop: a
     cancel ends the poll wait, and no further merge is attempted.

   Returns:
   {:success? boolean
    :merged-prs [pr-numbers]
    :failed-prs [pr-numbers]
    :train final-train-state}
   A stopped run returns that map with `:success? false :stopped? true`
   and an `:error` message."
  [train-state controllers context]
  (let [{:keys [train-id manager]} train-state
        logger (:logger context)
        control-state (:control-state context)
        max-cycles (get context :max-monitor-cycles 100)
        poll-interval-ms (get context :monitor-poll-interval-ms 30000)]

    (when logger
      (log/info logger :dag-monitor :monitor/starting
                {:data {:train-id train-id
                        :pr-count (count controllers)}}))

    (loop [cycle 0]
      (cond
        (all-prs-terminal? controllers)
        (build-monitor-result controllers manager train-id logger cycle)

        (stop-signal/stopped? control-state)
        (do
          (when logger
            (log/info logger :dag-monitor :monitor/stopped {:data {:cycles cycle}}))
          ;; The PRs merged before the cancel are reported; nil skips the completion log.
          (assoc (build-monitor-result controllers manager train-id nil cycle)
                 :success? false
                 :stopped? true
                 :error (messages/t :status/stopped-control)))

        (>= cycle max-cycles)
        (do
          (when logger
            (log/warn logger :dag-monitor :monitor/max-cycles
                      {:message "Max monitoring cycles exceeded"
                       :data {:cycles cycle}}))
          (monitor-error "Max monitoring cycles exceeded"
                         (pr-train/get-train manager train-id)))

        :else
        (do
          (execute-monitor-cycle! controllers manager train-id logger control-state)
          (stop-signal/sleep! control-state poll-interval-ms)
          (recur (inc cycle)))))))

;------------------------------------------------------------------------------ Layer 2

;; Entry point
(defn ^{:stratum 2} monitor-dag-prs
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
