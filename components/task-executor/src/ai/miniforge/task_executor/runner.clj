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

(ns ai.miniforge.task-executor.runner
  "Execute a single task through its full lifecycle.

  Orchestrates:
  1. Environment acquisition (worktree + executor)
  2. Code generation (inner loop)
  3. PR lifecycle (create → CI → review → merge)
  4. Event bridging (PR events → scheduler events)
  5. Resource cleanup (always in finally block)"
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.task-executor.bridge :as bridge]
            [ai.miniforge.task-executor.generate :as generate]
            [ai.miniforge.pr-lifecycle.interface :as pr]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.agent-runtime.interface :as error-classifier]
            [ai.miniforge.spec-parser.interface :as spec-parser]))

(def ^:private defaults
  (config/load-config-resource "config/task-executor/defaults.edn"
                               [:worktree-acquire-timeout-ms :token-pricing]))

(defn create-task-context
  [task-id task run-context]
  (let [{:keys [workflow-id executor config logger event-stream]} run-context
        {:keys [max-iterations llm-backend github-token]} config]
    {:task-id task-id
     :task task
     :workflow-id workflow-id
     :executor executor
     :logger logger
     :event-stream event-stream
     :max-iterations max-iterations
     :llm-backend llm-backend
     :github-token github-token
     :run-atom (:run-atom run-context)
     :config config}))

(defn log-event
  "Log an event if logger is available."
  [logger event-type task-id data]
  (when logger
    (log/info logger :task-executor event-type
              {:message (str "Task executor: " (name event-type))
               :data (assoc data :task-id task-id)})))

(defn acquire-resources!
  "Acquire worktree and environment for task execution.

  Returns DAG ok with {:worktree-acquired? bool, :env-record map}, or DAG error."
  [task-id lock-pool executor logger config]
  (log-event logger :acquiring-resources task-id {})

  ;; Acquire worktree semaphore (timeout from config defaults)
  (let [worktree-result (dag/acquire-worktree! lock-pool task-id
                                               (:worktree-acquire-timeout-ms defaults)
                                               logger)]
    (if (dag/err? worktree-result)
      (dag/err :task-executor/worktree-acquire-failed
               "Failed to acquire worktree"
               {:task-id task-id
                :error (:error worktree-result)})
      ;; Acquire environment from executor
      (let [env-config {:repo-url (:repo-url config)
                        :branch (:branch config "main")
                        :env (:env config {})
                        :resources (:resources config {})}
            env-result (dag/acquire-environment! executor task-id env-config)]
        (if (dag/err? env-result)
          (do
            (dag/release-worktree! lock-pool task-id logger)
            (dag/err :task-executor/environment-acquire-failed
                     "Failed to acquire environment"
                     {:task-id task-id
                      :error (:error env-result)}))

          (do
            (log-event logger :resources-acquired task-id
                       {:env-id (get-in env-result [:data :env-id])})

            (dag/ok {:worktree-acquired? true
                     :env-record (:data env-result)})))))))

(defn generate-code!
  "Run inner loop to generate code artifact.

  Returns: {:artifact map, :tokens int}"
  [task-id task task-context]
  (let [{:keys [llm-backend logger event-stream workflow-id max-iterations
                env-record]} task-context
        gen-fn (generate/create-generate-fn llm-backend
                 :logger logger
                 :event-stream event-stream
                 :workflow-id workflow-id
                 :max-iterations max-iterations)
        context {:worktree-path (:worktree-path env-record)
                 :base-commit (:base-commit env-record)
                 :session-id (:session-id env-record)}]

    (log-event logger :generating-code task-id
               {:max-iterations max-iterations})

    (gen-fn task context)))

(defn create-event-bridge
  "Create callback that bridges PR events to scheduler events.

  Returns: Function (fn [pr-event] -> nil) that translates and forwards events"
  [run-atom logger]
  (fn [pr-event]
    (when-let [scheduler-event (bridge/translate-event pr-event)]
      (log-event logger :event-bridged nil
                 {:pr-event (:event/type pr-event)
                  :scheduler-action (:event/action scheduler-event)})
      (dag/handle-task-event run-atom scheduler-event))))

(defn run-pr-lifecycle!
  "Create PR controller and run full lifecycle.

  Returns: Final status map from run-lifecycle!"
  [task-id task code-artifact task-context]
  (let [{:keys [workflow-id logger event-stream env-record run-atom
                llm-backend max-iterations]} task-context
        {:keys [worktree-path]} env-record

        ;; Transition to :pr-opening before creating controller
        _ (dag/transition-task! run-atom task-id :pr-opening)

        ;; Create event bus and controller
        event-bus (pr/create-event-bus)
        gen-fn (generate/create-generate-fn llm-backend
                 :logger logger
                 :event-stream event-stream
                 :workflow-id workflow-id
                 :max-iterations max-iterations)

        controller (pr/create-controller
                     workflow-id      ; dag-id
                     (str (random-uuid)) ; run-id
                     task-id
                     task
                     :worktree-path worktree-path
                     :event-bus event-bus
                     :logger logger
                     :generate-fn gen-fn)

        ;; Set up event bridge
        bridge-fn (create-event-bridge run-atom logger)]

    (log-event logger :pr-lifecycle-starting task-id
               {:branch-name (str "task-" task-id)})

    ;; Subscribe to events and forward to scheduler
    (pr/subscribe! event-bus bridge-fn)

    ;; Run blocking lifecycle
    (pr/run-lifecycle! controller code-artifact)))

(defn cleanup-resources!
  "Clean up environment and locks in finally block."
  [task-id lock-pool executor env-record logger]
  (when env-record
    (log-event logger :releasing-resources task-id
               {:env-id (:env-id env-record)})

    ;; Release environment
    (try
      (dag/release-environment! executor (:env-id env-record))
      (catch Exception e
        (log-event logger :release-environment-error task-id
                   {:error (ex-message e)})))

    ;; Release worktree and all locks
    (try
      (dag/release-all-locks! lock-pool task-id logger)
      (catch Exception e
        (log-event logger :release-locks-error task-id
                   {:error (ex-message e)})))))

(defn validate-spec!
  "Validate task spec before acquiring any resources.

   If the task carries a :spec/source-file path, attempts to parse it.
   A parse failure means the agent would have no structured context and
   would waste its entire turn budget exploring the codebase.

   Returns DAG ok on success. Returns DAG error on failure so the caller
   can fail-fast before touching the environment."
  [task-id task logger]
  (if-let [spec-path (or (get-in task [:spec/provenance :source-file])
                         (get task :spec/source-file)
                         (get task :spec-path))]
    (do
      (log-event logger :validating-spec task-id {:spec-path spec-path})
      (try
        (spec-parser/parse-spec-file spec-path)
        (log-event logger :spec-valid task-id {:spec-path spec-path})
        (dag/ok nil)
        (catch Exception e
          (dag/err
           :task-executor/spec-validation-failed
           (str "Spec validation failed — refusing to spawn agent without valid spec. "
                "Fix the spec at: " spec-path ". "
                "Cause: " (ex-message e))
           {:task-id task-id
            :spec-path spec-path
            :cause (ex-message e)}))))
    (dag/ok nil)))

(defn calculate-cost-usd
  "Calculate estimated cost in USD based on tokens used.
   Pricing model is configurable, sourced from :token-pricing in the
   defaults EDN (config/task-executor/defaults.edn): the per-million input
   and output costs and the input/output token-share split are all read from
   there rather than hard-coded here. Edit the EDN to change the model."
  [total-tokens]
  (let [{:keys [input-cost-per-million output-cost-per-million
                input-token-share output-token-share]}
        (:token-pricing defaults)
        input-tokens (* total-tokens input-token-share)
        output-tokens (* total-tokens output-token-share)
        cost (+ (/ (* input-tokens input-cost-per-million) 1000000.0)
                (/ (* output-tokens output-cost-per-million) 1000000.0))]
    (double cost)))

(defn update-task-metrics!
  "Update comprehensive task metrics in run-atom.

   Collects:
   - tokens-used: Total tokens consumed
   - cost-usd: Estimated cost based on token usage
   - iterations: Number of fix/retry cycles
   - time-to-merge-ms: Duration from start to merge
   - ci-runs: Number of CI executions
   - review-cycles: Number of review iterations
   - pr-url: GitHub PR URL
   - status: Final status (:merged, :failed, etc.)"
  [run-atom task-id lifecycle-result start-time]
  (let [total-tokens (get lifecycle-result :total-tokens 0)
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)

        metrics {:tokens-used total-tokens
                 :cost-usd (calculate-cost-usd total-tokens)
                 :iterations (get lifecycle-result :fix-iterations 0)
                 :time-to-merge-ms duration-ms
                 :ci-runs (get lifecycle-result :ci-runs 0)
                 :review-cycles (get lifecycle-result :review-cycles 0)
                 :pr-url (:pr-url lifecycle-result)
                 :status (:status lifecycle-result)
                 :start-time start-time
                 :end-time end-time}]
    (dag/update-metrics! run-atom task-id metrics)))

(defn- task-state
  [run-atom task-id]
  (when run-atom
    (get-in @run-atom [:tasks task-id :state])))

(defn- fail-task-with-error!
  [task-id run-atom logger start-time error]
  (log-event logger :task-execution-failed task-id
             {:error (:message error)
              :duration-ms (- (System/currentTimeMillis) start-time)
              :error-classification (:code error)})
  (dag/mark-failed! run-atom task-id error logger)
  {:ok? false
   :error error
   :error-classification {:type (:code error)}})

(defn- fail-task-with-exception!
  [task-id run-atom logger start-time e]
  (let [classified (error-classifier/classify-error e (task-state run-atom task-id))]
    (log-event logger :task-execution-failed task-id
               {:error (ex-message e)
                :cause (ex-cause e)
                :duration-ms (- (System/currentTimeMillis) start-time)
                :error-classification (:type classified)
                :vendor (:vendor classified)})

    (dag/mark-failed! run-atom task-id e logger)

    {:ok? false
     :error e
     :error-classification classified}))

(defn- run-task-after-resources!
  [task-id task task-context run-atom logger start-time env-record]
  (let [task-context-with-env (assoc task-context :env-record env-record)
        generation-result (generate-code! task-id task task-context-with-env)
        code-artifact (:artifact generation-result)
        lifecycle-result (run-pr-lifecycle! task-id task code-artifact task-context-with-env)]
    (update-task-metrics! run-atom task-id lifecycle-result start-time)
    (log-event logger :task-execution-completed task-id
               {:status (:status lifecycle-result)
                :pr-url (:pr-url lifecycle-result)
                :duration-ms (- (System/currentTimeMillis) start-time)})
    {:ok? true
     :value lifecycle-result}))

(defn- continue-with-resources!
  [task-id task task-context run-atom logger start-time env-record resource-result]
  (if (dag/err? resource-result)
    (fail-task-with-error! task-id run-atom logger start-time (:error resource-result))
    (let [env (get-in resource-result [:data :env-record])]
      (reset! env-record env)
      (run-task-after-resources! task-id task task-context run-atom logger start-time env))))

(defn- continue-after-spec-validation!
  [task-id task task-context run-atom lock-pool executor logger config start-time env-record spec-result]
  (if (dag/err? spec-result)
    (fail-task-with-error! task-id run-atom logger start-time (:error spec-result))
    (continue-with-resources!
     task-id task task-context run-atom logger start-time env-record
     (acquire-resources! task-id lock-pool executor logger config))))

(defn execute-task
  [task-id task run-context]
  (let [{:keys [run-atom executor logger lock-pool config]} run-context
        task-context (create-task-context task-id task run-context)
        start-time (System/currentTimeMillis)]

    (log-event logger :task-execution-starting task-id
               {:description (:description task)
                :start-time start-time})

    (let [env-record (atom nil)]
      (try
        (continue-after-spec-validation!
         task-id task task-context run-atom lock-pool executor logger config start-time env-record
         (validate-spec! task-id task logger))

        (catch Exception e
          (fail-task-with-exception! task-id run-atom logger start-time e))

        (finally
          ;; Always clean up resources
          (cleanup-resources! task-id lock-pool executor @env-record logger))))))
