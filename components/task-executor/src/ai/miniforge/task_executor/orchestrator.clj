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

(ns ai.miniforge.task-executor.orchestrator
  "Provide execute-task-fn callback and manage concurrent task futures.

  This is the top-level integration layer that:
  - Creates the execute-task-fn callback for the DAG scheduler
  - Launches task execution in futures
  - Tracks futures for graceful shutdown
  - Handles task failures and cascading to dependents"
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.task-executor.runner :as runner]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.logging.interface :as log]))

(def ^:private defaults
  (config/load-config-resource "config/task-executor/defaults.edn"
                               [:max-parallel :scheduler-poll-interval-ms]))

(defn create-run-context
  [run-atom config]
  (let [lock-pool (or (:lock-pool config)
                      (dag/create-lock-pool
                        :max-worktrees (:max-parallel config (:max-parallel defaults))))]
    {:run-atom run-atom
     :workflow-id (:workflow-id config)
     :executor (:executor config)
     :llm-backend (:llm-backend config)
     :logger (:logger config)
     :event-stream (:event-stream config)
     :lock-pool lock-pool
     :config config}))

(defn log-event
  "Log an event if logger is available."
  [logger event-type data]
  (when logger
    (log/info logger :task-orchestrator event-type
              {:message (str "Task orchestrator: " (name event-type))
               :data data})))

(defn skip-dependent-tasks!
  "Skip all tasks that depend on a failed task."
  [run-atom task-id logger]
  (log-event logger :skipping-dependents {:failed-task-id task-id})

  ;; Get all task IDs from run-atom
  (let [run-state @run-atom
        all-tasks (:tasks run-state)
        dependents (filter (fn [[_tid task]]
                            (contains? (set (:dependencies task)) task-id))
                          all-tasks)]

    (doseq [[dependent-id _task] dependents]
      (log-event logger :skipping-task {:task-id dependent-id
                                        :reason :dependency-failed
                                        :failed-dependency task-id})
      (dag/mark-failed! run-atom dependent-id
                        {:message "Dependency failed"
                         :dependency-id task-id}
                        logger))))

(defn make-execute-task-fn
  [run-context]
  (let [{:keys [run-atom logger]} run-context
        ;; Atom to track active futures
        futures-atom (atom {})]

    (fn [task-id _scheduler-context]
      (let [;; Look up full task definition
            run-state @run-atom
            task (get-in run-state [:tasks task-id])]

        (if-not task
          (do
            (log-event logger :task-not-found {:task-id task-id})
            nil)

          ;; Launch task execution in future
          (let [task-future
                (future
                  (try
                    (log-event logger :task-future-started {:task-id task-id})

                    ;; Execute task through full lifecycle
                    (let [result (runner/execute-task task-id task run-context)]

                      (when-not (:ok? result)
                        ;; Task failed - cascade to dependents
                        (skip-dependent-tasks! run-atom task-id logger))

                      result)

                    (catch Exception e
                      (log-event logger :task-future-exception
                                 {:task-id task-id
                                  :error (ex-message e)})

                      ;; Mark task as failed and cascade
                      (dag/mark-failed! run-atom task-id e logger)
                      (skip-dependent-tasks! run-atom task-id logger)

                      {:ok? false
                       :error e})

                    (finally
                      ;; Remove from tracking
                      (swap! futures-atom dissoc task-id)
                      (log-event logger :task-future-completed {:task-id task-id}))))]

            ;; Track future
            (swap! futures-atom assoc task-id task-future)

            task-future))))))

(defn create-orchestrated-scheduler-context
  [run-atom config]
  (let [run-context (create-run-context run-atom config)
        execute-task-fn (make-execute-task-fn run-context)]
    {:run-atom run-atom
     :execute-task-fn execute-task-fn
     :max-parallel (:max-parallel config (:max-parallel defaults))
     :config config}))

(defn- schedule-iteration-result
  "Run one scheduler iteration, returning failures as data so the orchestrator
   can record a terminal failed run instead of relying on exception control
   flow from the scheduler boundary."
  [run-atom scheduler-context]
  (try
    (dag/schedule-iteration run-atom scheduler-context)
    (catch Exception e
      {:ok? false
       :error {:code :scheduler-exception
               :message (ex-message e)
               :class (some-> e class .getName)
               :data (ex-data e)}})))

(defn- scheduler-result-state
  [result]
  (cond
    (:ok? result) (:value result)
    (contains? result :run-state) (:run-state result)
    :else result))

(defn- scheduler-status
  [state]
  (or (:status state)
      (:run/status state)))

(defn- scheduler-count
  [state old-key run-key]
  (if-let [task-statuses (seq (vals (:run/tasks state)))]
    (case old-key
      :pending (count (filter #(= :pending (:task/status %)) task-statuses))
      :running (count (filter #(#{:running :implementing} (:task/status %)) task-statuses))
      :completed (+ (count (:run/completed state))
                    (count (:run/merged state)))
      (count (or (get state run-key) #{})))
    (count (or (get state old-key)
               (get state run-key)
               #{}))))

(defn- continue-scheduler?
  [result status]
  (if (contains? result :continue?)
    (:continue? result)
    (not (#{:completed :failed :budget-exceeded} status))))

(defn- mark-scheduler-error!
  [run-atom error]
  (swap! run-atom assoc
         :status :failed
         :run/status :failed
         :error error
         :run/error error))

(defn execute-dag!
  [dag-id task-defs config]
  (let [{:keys [logger budget state-profile state-profile-provider]} config
        ;; Initialize DAG using dag-executor's function
        run-state (dag/create-dag-from-tasks dag-id task-defs
                                             :budget budget
                                             :state-profile state-profile
                                             :state-profile-provider state-profile-provider)
        run-atom (dag/create-run-atom run-state)

        ;; Create orchestrated context
        scheduler-context (create-orchestrated-scheduler-context run-atom config)]

    (log-event logger :dag-execution-starting {:dag-id dag-id
                                                :task-count (count task-defs)})

    ;; Run scheduler loop
    (loop [iteration 0]
      (let [result (schedule-iteration-result run-atom scheduler-context)]
        (if (= false (:ok? result))
          (let [error (:error result)]
            (log-event logger :dag-execution-error
                       {:error (:message error)
                        :code (:code error)})
            (mark-scheduler-error! run-atom error))
          (let [state (scheduler-result-state result)
                status (scheduler-status state)]

            (log-event logger :scheduler-iteration
                       {:iteration iteration
                        :status status
                        :tasks-pending (scheduler-count state :pending :run/pending)
                        :tasks-running (scheduler-count state :running :run/running)
                        :tasks-completed (scheduler-count state :completed :run/completed)})

            ;; Continue if not terminal
            (when (continue-scheduler? result status)
              (Thread/sleep (:scheduler-poll-interval-ms defaults)) ; Poll interval
              (recur (inc iteration)))))))

    ;; Return final state
    (let [final-state @run-atom]
      (log-event logger :dag-execution-completed
                 {:dag-id dag-id
                  :status (:status final-state)
                  :tasks-completed (count (filter #(= :merged (second %))
                                                 (:task-states final-state)))
                  :tasks-failed (count (filter #(= :failed (second %))
                                              (:task-states final-state)))})
      final-state)))
