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

(ns ai.miniforge.workflow.runner
  "Workflow pipeline orchestration.

   Thin pipeline composer that delegates to:
   - runner-defaults    — configuration as data
   - runner-events      — event publishing
   - runner-environment — execution environment lifecycle
   - runner-cleanup     — post-execution hooks
   - context            — context management
   - execution          — phase execution
   - monitoring         — health monitoring"
  (:require [ai.miniforge.dag-executor.interface :as dag-exec]
            [ai.miniforge.llm.interface :as llm-impl]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.execution :as exec]
            [ai.miniforge.workflow.fsm :as workflow-fsm]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-environment :as env]
            [ai.miniforge.workflow.runner-events :as events]
            [slingshot.slingshot :refer [try+]]))

(defonce ^:private runner-logger
  (log/create-logger {:min-level :debug :output :human}))

;; Re-export public API from extracted namespaces so external callers
;; (tests, interface/pipeline.clj) continue to work unchanged.
(def publish-event!              events/publish-event!)
(def publish-workflow-started!   events/publish-workflow-started!)
(def publish-workflow-completed! events/publish-workflow-completed!)
(def publish-phase-started!      events/publish-phase-started!)
(def publish-phase-completed!    events/publish-phase-completed!)
(def promote-mature-learnings!   cleanup/promote-mature-learnings!)

;------------------------------------------------------------------------------ Layer 0: Result factories

(defn- make-execution-error
  "Build a canonical execution error map."
  [error-type msg & [extra]]
  (cond-> {:type error-type :message msg}
    extra (merge extra)))

;------------------------------------------------------------------------------ Layer 0: Pipeline construction

(defn- legacy-phase-interceptor
  [phase-def]
  (phase/get-phase-interceptor
   {:phase (:phase/id phase-def)
    :config phase-def}))

(defn build-pipeline
  "Build interceptor pipeline from workflow config."
  [workflow]
  (let [pipeline-config (:workflow/pipeline workflow)]
    (if (seq pipeline-config)
      (mapv phase/get-phase-interceptor pipeline-config)
      (mapv legacy-phase-interceptor
            (:workflow/phases workflow)))))

(defn validate-pipeline
  "Validate a workflow pipeline. Returns {:valid? bool :errors [] :warnings []}."
  [workflow]
  (let [phase-validation (phase/validate-pipeline workflow)
        machine-validation (workflow-fsm/validate-execution-machine workflow)]
    {:valid? (and (:valid? phase-validation)
                  (:valid? machine-validation))
     :errors (vec (concat (:errors phase-validation)
                          (:errors machine-validation)))
     :warnings (vec (concat (:warnings phase-validation)
                            (:warnings machine-validation)))}))

;------------------------------------------------------------------------------ Layer 1: Orchestration helpers

(defn handle-empty-pipeline
  "Handle error case of empty pipeline."
  [context]
  (let [msg (messages/t :status/no-phases)]
    (-> context
        (update :execution/errors conj (make-execution-error :empty-pipeline msg))
        (update :execution/response-chain
                response/add-failure :pipeline
                :anomalies.workflow/empty-pipeline {:error msg})
        (ctx/transition-to-failed))))

(defn handle-max-phases-exceeded
  "Handle error case of max phases exceeded."
  [context max-phases]
  (let [msg (messages/t :status/max-phases {:max-phases max-phases})]
    (-> context
        (update :execution/errors conj (make-execution-error :max-phases-exceeded msg))
        (update :execution/response-chain
                response/add-failure :pipeline
                :anomalies.workflow/max-phases {:error msg :max-phases max-phases})
        (ctx/transition-to-failed))))

(defn terminal-state?
  "Check if workflow is in terminal state."
  [context]
  (or (phase/succeeded? context) (phase/failed? context)))

;------------------------------------------------------------------------------ Layer 1: Iteration helpers

(defn- rate-limited?
  "True when a phase error message indicates API rate limiting."
  [error-msg]
  (boolean
   (re-find #"(?i)rate.?limit|429|hit your limit|quota.?exceeded"
            (str error-msg))))

(defn- backoff-ms
  "Exponential backoff duration for retry iteration, capped."
  [retry-count]
  (let [raw (* (defaults/backoff-base-ms) (Math/pow 2 (dec retry-count)))]
    (long (min (defaults/max-backoff-ms) raw))))

(defn- await-resume!
  "Block while the control state is paused."
  [control-state]
  (when (:paused @control-state)
    (log/debug runner-logger :system :workflow/execution-paused
               {:message (messages/t :status/paused)})
    (while (:paused @control-state)
      (Thread/sleep (defaults/pause-poll-interval-ms)))))

(defn- check-stopped!
  "Throw if the control state indicates a stop command."
  [control-state]
  (when (:stopped @control-state)
    (log/warn runner-logger :system :workflow/execution-stopped
              {:message (messages/t :status/stopped-dashboard)
               :data {:reason :dashboard-stop}})
    (response/throw-anomaly! :anomalies.dashboard/stop
                             (messages/t :status/stopped-control)
                             {:reason :dashboard-stop})))

(defn- apply-rate-limit-failure
  "Mark context as failed due to rate limiting."
  [phase-ctx error-msg]
  (-> phase-ctx
      (ctx/transition-to-failed)
      (update :execution/errors conj (make-execution-error :rate-limited error-msg))))

(defn- apply-backoff-if-retrying!
  "Sleep with exponential backoff when a phase is being retried."
  [phase-ctx _context]
  (let [current-phase (:execution/current-phase phase-ctx)
        retrying? (phase/retrying?
                   (get-in phase-ctx [:execution/phase-results current-phase]))
        retry-count (get-in phase-ctx [:phase :iterations] 1)]
    (when (and retrying? (> retry-count 1))
      (Thread/sleep (backoff-ms retry-count)))))

(defn- apply-health-switch
  "Check backend health and apply switch result if needed."
  [phase-ctx]
  (let [event-stream (get-in phase-ctx [:execution/opts :event-stream])
        switch-result (events/check-backend-health-at-boundary! event-stream phase-ctx)]
    (cond-> phase-ctx
      switch-result (assoc :self-healing/backend-switch switch-result))))

(defn execute-single-iteration
  "Execute single pipeline iteration: control check -> health -> phase -> backoff."
  [pipeline context callbacks iteration control-state]
  (await-resume! control-state)
  (check-stopped! control-state)
  (let [supervision-runtime (:execution/supervision-runtime context)
        workflow-state (monitoring/build-supervision-state context iteration)
        supervision-result (monitoring/check-workflow-supervision
                            supervision-runtime
                            workflow-state)
        iteration-result
        (if (= :halt (:status supervision-result))
          (monitoring/handle-supervision-halt
           context
           supervision-result
           ctx/transition-to-failed)
          (let [phase-ctx (-> (exec/execute-phase-step pipeline context callbacks
                                                       ctx/merge-metrics
                                                       ctx/transition-to-completed
                                                       ctx/transition-to-failed)
                              (monitoring/clear-transient-state))
                _ (env/persist-workspace-at-phase-boundary! context phase-ctx)
                phase-error-msg (get-in phase-ctx
                                        [:execution/phase-results
                                         (:execution/current-phase phase-ctx)
                                         :error :message] "")]
            (if (rate-limited? phase-error-msg)
              (apply-rate-limit-failure phase-ctx phase-error-msg)
              (do (apply-backoff-if-retrying! phase-ctx context)
                  (apply-health-switch phase-ctx)))))]
    (checkpoint-store/persist-execution-state! iteration-result)
    iteration-result))

;------------------------------------------------------------------------------ Layer 1.5: Output extraction

(defn- resolve-runtime-class
  "Return the executor's runtime class (:docker, :worktree, etc.) or nil."
  [executor]
  (when executor
    (try (dag-exec/executor-type executor) (catch Exception _ nil))))

(defn extract-output
  "Synthesize :execution/output from completed pipeline context.
   Includes N11 §9.1 evidence fields."
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        current-phase (ctx/active-or-last-phase ctx)
        image-digest  (get-in ctx [:execution/environment-metadata :image-digest])]
    (assoc ctx :execution/output
           (cond-> {:artifacts              (:execution/artifacts ctx)
                    :phase-results          phase-results
                    :last-phase-result      (get phase-results current-phase)
                    :status                 (:execution/status ctx)
                    :evidence/execution-mode  (get ctx :execution/mode :local)
                    :evidence/runtime-class   (resolve-runtime-class (:execution/executor ctx))
                    :evidence/task-started-at (:execution/started-at ctx)
                    :evidence/task-finished-at (java.time.Instant/now)}
             image-digest (assoc :evidence/image-digest image-digest)))))

;------------------------------------------------------------------------------ Layer 2: Pipeline stages

(declare run-pipeline)

(defn- acquire-environment
  "Acquire an isolated execution environment (worktree or Docker capsule).
   Returns [acquired-env opts'] where acquired-env is nil when pre-acquired."
  [workflow input opts]
  (if (and (:executor opts) (:environment-id opts))
    [nil opts]
    (let [repo-url (get opts :repo-url (get input :repo-url))
          branch   (get opts :branch (get input :branch "main"))
          acquired (env/acquire-execution-environment!
                    (:workflow/id workflow)
                    {:repo-url        repo-url
                     :branch          branch
                     :execution-mode  (:execution-mode opts)
                     :executor-config (:executor-config opts)})]
      [acquired (if acquired (merge opts acquired) opts)])))

(defn- wrap-phase-callbacks
  "Wrap caller callbacks with event publishing."
  [event-stream opts]
  (letfn [(phase-name [interceptor]
            (get interceptor :phase
                 (get-in interceptor [:config :phase])))
          (on-phase-start [ctx interceptor]
            (when-let [phase-name' (phase-name interceptor)]
              (events/publish-phase-started! event-stream ctx phase-name'))
            (when-let [callback (:on-phase-start opts)]
              (callback ctx interceptor)))
          (on-phase-complete [ctx interceptor result]
            (when-let [phase-name' (phase-name interceptor)]
              (events/publish-phase-completed! event-stream ctx phase-name' result))
            (when-let [callback (:on-phase-complete opts)]
              (callback ctx interceptor result)))]
    {:on-phase-start on-phase-start
     :on-phase-complete on-phase-complete}))

(defn- build-initial-context
  "Build the initial execution context from workflow, input, and opts."
  [workflow input opts]
  (let [resume-machine-snapshot (:resume-machine-snapshot opts)
        resume-phase-results (:resume-phase-results opts)
        mode      (get opts :execution-mode :local)
        governed? (and (= :governed mode)
                       (get opts :executor)
                       (get opts :environment-id))]
    (when (and (= :governed mode) (not governed?))
      (response/throw-anomaly! :anomalies.workflow/no-capsule-executor
                               (messages/t :governed/no-capsule)
                               {}))
    (let [capsule-exec-fn (when governed?
                            (llm-impl/capsule-exec-fn
                             dag-exec/execute!
                             (get opts :executor)
                             (get opts :environment-id)
                             (get opts :worktree-path (defaults/default-workdir))))]
      (-> (if (and resume-machine-snapshot resume-phase-results)
            (ctx/restore-context workflow input
                                 resume-machine-snapshot
                                 resume-phase-results
                                 opts)
            (ctx/create-context workflow input opts))
          (assoc :execution/executor (get opts :executor)
                 :execution/environment-id (get opts :environment-id)
                 :execution/worktree-path (get opts :worktree-path
                                               (get opts :sandbox-workdir))
                 :execution/mode mode
                 :execution/started-at (java.time.Instant/now)
                 :execution/environment-metadata (get opts :environment-metadata)
                 :execution/execute-fn (when governed? dag-exec/execute!)
                 :execution/exec-fn capsule-exec-fn
                 :execution/task-branch (when governed?
                                          (str (defaults/task-branch-prefix)
                                               (get opts :environment-id
                                                    (subs (str (random-uuid)) 0 8))))
                 :execution/run-pipeline-fn run-pipeline)))))

(defn- execute-pipeline-loop
  "Execute the phase pipeline loop. Returns final context."
  [pipeline initial-ctx callbacks control-state max-phases]
  (if (empty? pipeline)
    (handle-empty-pipeline initial-ctx)
    (loop [context initial-ctx
           iteration 0]
      (cond
        (terminal-state? context)      context
        (>= iteration max-phases)      (handle-max-phases-exceeded context max-phases)
        :else (recur (execute-single-iteration pipeline context callbacks iteration control-state)
                     (inc iteration))))))

(defn- validate-completion
  "Warn when a succeeded workflow produced no results."
  [final-ctx]
  (if (and (phase/succeeded? final-ctx)
           (empty? (:execution/artifacts final-ctx))
           (empty? (:execution/phase-results final-ctx)))
    (let [ctx' (-> final-ctx
                   (update :execution/errors conj
                           (make-execution-error :empty-completion
                                                (messages/t :status/empty-completion))))]
      (if (:execution/fsm-machine ctx')
        (-> ctx'
            (assoc :execution/completed-with-warnings? true)
            (ctx/sync-machine-projections))
        (assoc ctx' :execution/status :completed-with-warnings)))
    final-ctx))

;------------------------------------------------------------------------------ Layer 3: Main entry point

(defn run-pipeline
  "Execute a workflow pipeline.

   Pipeline: acquire environment -> build context -> execute phases
           -> validate -> publish completion -> cleanup (always).

   Returns final execution context."
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   (let [[acquired-env opts] (acquire-environment workflow input opts)
         pipeline            (build-pipeline workflow)
         max-phases          (get opts :max-phases (defaults/max-phases))
         control-state       (get opts :control-state
                                  (atom {:paused false :stopped false :adjustments {}}))
         event-stream        (:event-stream opts)
         callbacks           (wrap-phase-callbacks event-stream opts)
         skip-lifecycle?     (:skip-lifecycle-events opts)
         initial-ctx         (build-initial-context workflow input opts)
         output-ctx-vol      (volatile! nil)
         exception-vol       (volatile! nil)]

     (when event-stream
       (supervisory/ensure-attached! event-stream))

     (checkpoint-store/persist-execution-state! initial-ctx)

     (when-not skip-lifecycle?
       (events/publish-workflow-started! event-stream initial-ctx))

     (try+
       (let [final-ctx (try+
                         (execute-pipeline-loop pipeline initial-ctx callbacks
                                               control-state max-phases)
                         #_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
                         (catch [:anomaly/category :anomalies.dashboard/stop]
                                {:keys [anomaly/message]}
                           (vreset! exception-vol (ex-info message {:type :dashboard-stop}))
                           (-> initial-ctx
                               (update :execution/errors conj
                                       (make-execution-error :dashboard-stop message))
                               (ctx/transition-to-failed)))
                         #_{:clj-kondo/ignore [:unresolved-symbol]}
                         (catch Object e
                           (let [throwable (:throwable &throw-context)
                                 msg (if (instance? Throwable e) (ex-message e) (str e))]
                             (vreset! exception-vol (or throwable (ex-info msg {})))
                             (-> initial-ctx
                                 (update :execution/errors conj
                                         (make-execution-error :pipeline-exception msg
                                                               {:data (when (instance? Throwable e) (ex-data e))}))
                                 (ctx/transition-to-failed)))))
             output-ctx (-> final-ctx validate-completion extract-output)]
         (vreset! output-ctx-vol output-ctx)
         (when-not skip-lifecycle?
           (events/publish-workflow-completed! event-stream output-ctx))
         output-ctx)
       (finally
         (cleanup/post-workflow-cleanup! opts @output-ctx-vol workflow
                                        @exception-vol acquired-env))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.phase-software-factory.plan])
  (require '[ai.miniforge.phase-software-factory.implement])

  (def simple-workflow
    {:workflow/id :simple-test
     :workflow/version "2.0.0"
     :workflow/pipeline
     [{:phase :plan}
      {:phase :implement}
      {:phase :done}]})

  (build-pipeline simple-workflow)
  (validate-pipeline simple-workflow)

  :leave-this-here)
