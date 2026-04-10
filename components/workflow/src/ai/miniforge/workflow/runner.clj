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

   Top-level runner that orchestrates workflow execution by composing:
   - Context management (context namespace)
   - Phase execution (execution namespace)
   - Health monitoring (monitoring namespace)

   Provides the main run-pipeline entry point."
  (:require [ai.miniforge.dag-executor.executor :as dag-exec]
            [ai.miniforge.dag-executor.result :as dag-result]
            [ai.miniforge.llm.protocols.impl.llm-client :as llm-impl]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.execution :as exec]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [cheshire.core :as json]))

(defonce ^:private runner-logger
  (log/create-logger {:min-level :debug :output :human}))

;------------------------------------------------------------------------------ Layer -1: Event publishing

(defn publish-event!
  "Publish event to event stream or dashboard WebSocket."
  [event-stream event]
  (when event-stream
    (try
      (cond
        ;; WebSocket connection to dashboard
        (:websocket event-stream)
        (try
          (require '[org.httpkit.client :as http])
          (when-let [http-ns (find-ns 'org.httpkit.client)]
            (when-let [ws (:websocket event-stream)]
              (when-let [send-fn (ns-resolve http-ns 'send!)]
                (send-fn ws (json/generate-string event)))))
          (catch Exception e
            (println (messages/t :warn/websocket-send {:error (ex-message e)}))))

        ;; In-memory event stream (same process)
        :else
        (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
          (when es-ns
            (when-let [publish! (ns-resolve es-ns 'publish!)]
              (publish! event-stream event)))))
      (catch Exception e
        (println (messages/t :warn/publish-event {:error (ex-message e)}))))))

(defn publish-workflow-started!
  "Publish workflow started event using N3-compliant constructor."
  [event-stream context]
  (when event-stream
    (try
      (let [workflow-started (requiring-resolve 'ai.miniforge.event-stream.interface/workflow-started)]
        (publish-event! event-stream
                        (workflow-started event-stream
                                         (:execution/id context)
                                         (select-keys (:execution/workflow context)
                                                      [:workflow/id :workflow/version]))))
      (catch Exception e
        (println (messages/t :warn/publish-started {:error (ex-message e)}))))))

(defn publish-workflow-completed!
  "Publish workflow completed/failed event using N3-compliant constructor."
  [event-stream context]
  (when event-stream
    (try
      (let [status (:execution/status context)
            wf-id (:execution/id context)
            metrics (:execution/metrics context)
            duration-ms (:duration-ms metrics)
            tokens (:tokens metrics)
            cost-usd (:cost-usd metrics)
            pr-info (:workflow/pr-info context)
            metrics-opts (cond-> {}
                           tokens (assoc :tokens tokens)
                           cost-usd (assoc :cost-usd cost-usd)
                           pr-info (assoc :pr-info pr-info))
            workflow-failed (requiring-resolve 'ai.miniforge.event-stream.interface/workflow-failed)
            workflow-completed (requiring-resolve 'ai.miniforge.event-stream.interface/workflow-completed)]
        (if (= status :failed)
          (publish-event! event-stream
                          (workflow-failed event-stream wf-id
                                          {:message (messages/t :status/failed)
                                           :errors (:execution/errors context)}))
          (publish-event! event-stream
                          (workflow-completed event-stream wf-id status duration-ms
                                              (when (seq metrics-opts) metrics-opts)))))
      (catch Exception e
        (println (messages/t :warn/publish-completed {:error (ex-message e)}))))))

(defn publish-phase-started!
  "Publish phase started event."
  [event-stream context phase-name]
  (when event-stream
    (try
      (let [phase-started (requiring-resolve 'ai.miniforge.event-stream.interface/phase-started)]
        (publish-event! event-stream
                        (phase-started event-stream (:execution/id context) phase-name
                                       {:phase/index (:execution/phase-index context)})))
      (catch Exception e
        (println (messages/t :warn/publish-phase-started {:error (ex-message e)}))))))

(defn publish-phase-completed!
  "Publish phase completed event."
  [event-stream context phase-name result]
  (when event-stream
    (let [succeeded? (get result :success? (phase/succeeded-or-done? result))
          outcome    (if succeeded? :success :failure)
          duration-ms (get result :duration-ms
                        (get-in result [:phase/metrics :duration-ms]
                          (get-in result [:metrics :duration-ms])))
          error-info (when-not succeeded?
                       (or (:error result)
                           (when-let [msg (get-in result [:phase/gate-errors])]
                             {:message (messages/t :status/gate-failed) :details msg})
                           (when-let [msg (:message result)]
                             {:message msg})))
          redirect-to (:redirect-to result)
          tokens   (get-in result [:metrics :tokens]
                     (get-in result [:phase/metrics :tokens]))
          cost-usd (get-in result [:metrics :cost-usd]
                     (get-in result [:phase/metrics :cost-usd]))
          event-data (cond-> {:outcome outcome :duration-ms duration-ms}
                       error-info (assoc :error error-info)
                       redirect-to (assoc :redirect-to redirect-to)
                       tokens (assoc :tokens tokens)
                       cost-usd (assoc :cost-usd cost-usd))]
      (try
        (let [phase-completed (requiring-resolve 'ai.miniforge.event-stream.interface/phase-completed)]
          (publish-event! event-stream
                          (phase-completed event-stream (:execution/id context) phase-name
                                           event-data)))
        (catch Exception e
          (println (messages/t :warn/publish-phase-completed {:error (ex-message e)})))))))

(defn check-backend-health-at-boundary!
  "Check backend health at phase boundary. Returns switch-result or nil."
  [event-stream context]
  (try
    (when-let [check-fn (requiring-resolve
                          'ai.miniforge.self-healing.interface/check-backend-health-and-switch)]
      (let [backend (or (get-in context [:execution/opts :llm-backend :config :backend])
                        :anthropic)
            sh-ctx {:llm {:backend backend}
                    :config (get-in context [:execution/opts :self-healing-config])}
            switch-result (check-fn sh-ctx)]
        (when (:switched? switch-result)
          (when-let [emit-fn (requiring-resolve
                               'ai.miniforge.self-healing.interface/emit-backend-switch-event)]
            (publish-event! event-stream (emit-fn sh-ctx switch-result)))
          switch-result)))
    (catch Exception e
      (println (messages/t :warn/health-check {:error (ex-message e)}))
      nil)))

;------------------------------------------------------------------------------ Layer 0: Pipeline helpers

(defn build-pipeline
  "Build interceptor pipeline from workflow config.

   Arguments:
   - workflow: Workflow configuration with :workflow/pipeline

   Returns vector of interceptor maps."
  [workflow]
  (let [pipeline-config (:workflow/pipeline workflow)]
    (if (seq pipeline-config)
      ;; New simplified format
      (mapv phase/get-phase-interceptor pipeline-config)
      ;; Fall back to legacy format
      (let [phases (:workflow/phases workflow)]
        (mapv (fn [phase-def]
                (phase/get-phase-interceptor
                 {:phase (:phase/id phase-def)
                  :config phase-def}))
              phases)))))

(defn validate-pipeline
  "Validate a workflow pipeline.

   Returns {:valid? bool :errors []}."
  [workflow]
  (phase/validate-pipeline workflow))

;------------------------------------------------------------------------------ Layer 0.5: Execution environment lifecycle

(defn- dag-executor-fns
  "Return dag-executor function references."
  []
  {:create-registry dag-exec/create-executor-registry
   :select-exec     dag-exec/select-executor
   :acquire-env!    dag-exec/acquire-environment!
   :executor-type   dag-exec/executor-type
   :result-ok?      dag-result/ok?
   :result-unwrap   dag-result/unwrap})

(defn- registry-config-for-mode
  "Build executor registry config for execution mode.
   :governed — Docker/K8s only (no worktree; no-silent-downgrade per N11 §7.4).
   :local    — worktree only."
  [mode executor-config]
  (case mode
    :governed (merge (select-keys (or executor-config {}) [:docker :kubernetes])
                     (when-not executor-config
                       {:docker {:image "miniforge/task-runner-clojure:latest"}}))
    {:worktree {}}))

(defn- select-capsule-executor
  "Select executor for mode; rejects worktree fallback in governed mode (N11 §7.4)."
  [registry mode {:keys [select-exec executor-type]}]
  (let [raw (case mode
              :governed (select-exec registry)
              (select-exec registry :preferred :worktree))]
    (when-not (and (= :governed mode) raw (= :worktree (executor-type raw)))
      raw)))

(defn- assert-executor-for-mode!
  "Throws for :governed mode when no capsule executor is available."
  [executor mode]
  (when (and (nil? executor) (= :governed mode))
    (throw (ex-info (messages/t :governed/no-capsule)
                    {:execution-mode :governed
                     :hint (messages/t :governed/no-capsule-hint)}))))

(defn- build-env-record
  "Acquire environment from executor and build the result map.
   Forwards env-config (repo-url, branch, etc.) to the executor so it can
   bootstrap the workspace inside the capsule (N11 §4.2)."
  [executor workflow-id mode env-config {:keys [acquire-env! result-ok? result-unwrap]}]
  (let [task-id    (if (uuid? workflow-id) workflow-id (random-uuid))
        env-result (acquire-env! executor task-id env-config)]
    (when (result-ok? env-result)
      (let [env (result-unwrap env-result)]
        {:executor       executor
         :environment-id (:environment-id env)
         :worktree-path  (:workdir env)
         :execution-mode mode}))))

(defn- acquire-execution-environment!
  "Acquire an isolated execution environment before pipeline starts.

   Execution mode (from opts):
   - :local (default) — worktree only; agent-on-host model.
   - :governed — host worktree for agent file I/O + Docker/K8s capsule for
     governed command execution. Agent writes to the worktree; commands
     (tests, git, builds) are dispatched into the capsule.

   Returns map with :executor, :environment-id, :worktree-path, :execution-mode,
   or nil if acquisition fails (:local) / throws (:governed)."
  [workflow-id {:keys [repo-url branch execution-mode executor-config]}]
  (let [mode (get {:governed :governed} execution-mode :local)]
    (try
      (let [fns      (dag-executor-fns)
            config   (registry-config-for-mode mode executor-config)
            registry ((:create-registry fns) config)
            executor (select-capsule-executor registry mode fns)
            env-config (cond-> {}
                         repo-url (assoc :repo-url repo-url)
                         branch   (assoc :branch branch))]
        (assert-executor-for-mode! executor mode)
        (when executor
          (if (= :governed mode)
            ;; Governed: acquire BOTH a host worktree (for agent file I/O)
            ;; and a capsule (for governed commands). The agent writes to the
            ;; worktree; persist-workspace! syncs to the capsule/remote.
            (let [worktree-fns (assoc fns
                                      :create-registry (:create-registry fns)
                                      :select-exec (:select-exec fns))
                  wt-registry  ((:create-registry fns) {:worktree {}})
                  wt-executor  ((:select-exec fns) wt-registry :preferred :worktree)
                  wt-result    (when wt-executor
                                 (build-env-record wt-executor workflow-id mode env-config fns))
                  capsule      (build-env-record executor workflow-id mode env-config fns)]
              (when (and wt-result capsule)
                (assoc capsule
                       ;; Agent uses host worktree for file I/O
                       :worktree-path (:worktree-path wt-result)
                       ;; Keep capsule executor + env-id for governed commands
                       :worktree-executor wt-executor
                       :worktree-environment-id (:environment-id wt-result))))
            ;; Local: worktree only
            (build-env-record executor workflow-id mode env-config fns))))
      (catch Exception e
        (when (= :governed mode) (throw e))
        (log/warn runner-logger :workflow :workflow/env-acquisition-failed
                  {:message (str "Environment acquisition failed: " (ex-message e))})
        nil))))

(defn- release-execution-environment!
  "Release an execution environment acquired by the runner.
   Safe to call with nil values."
  [executor environment-id]
  (when (and executor environment-id)
    (try
      (dag-exec/release-environment! executor environment-id)
      (catch Exception e
        (println (messages/t :warn/publish-event
                             {:error (str "Environment release failed: " (ex-message e))}))))))

;------------------------------------------------------------------------------ Layer 1: Orchestration helpers

(defn handle-empty-pipeline
  "Handle error case of empty pipeline."
  [context]
  (let [msg (messages/t :status/no-phases)]
    (-> context
        (update :execution/errors conj
                {:type :empty-pipeline
                 :message msg})
        (update :execution/response-chain
                response/add-failure :pipeline
                :anomalies.workflow/empty-pipeline
                {:error msg})
        (ctx/transition-to-failed))))

(defn handle-max-phases-exceeded
  "Handle error case of max phases exceeded."
  [context max-phases]
  (let [msg (messages/t :status/max-phases {:max-phases max-phases})]
    (-> context
        (update :execution/errors conj
                {:type :max-phases-exceeded
                 :message msg})
        (update :execution/response-chain
                response/add-failure :pipeline
                :anomalies.workflow/max-phases
                {:error msg
                 :max-phases max-phases})
        (ctx/transition-to-failed))))

(defn terminal-state?
  "Check if workflow is in terminal state."
  [context]
  (or (registry/succeeded? context) (registry/failed? context)))

(defn execute-single-iteration
  "Execute single pipeline iteration: health check -> phase execution -> cleanup.

   Returns updated context."
  [pipeline context callbacks iteration control-state]
  (let [;; Check control state from dashboard
        state @control-state

        ;; Handle pause - wait until resumed
        _ (when (:paused state)
            (log/debug runner-logger :system :workflow/execution-paused
                       {:message (messages/t :status/paused)})
            (while (:paused @control-state)
              (Thread/sleep 1000)))

        ;; Check for stop command
        _ (when (:stopped state)
            (log/warn runner-logger :system :workflow/execution-stopped
                      {:message (messages/t :status/stopped-dashboard)
                       :data {:reason :dashboard-stop}})
            (throw (ex-info (messages/t :status/stopped-control) {:reason :dashboard-stop})))

        ;; Check workflow health via meta-agents
        coordinator (:execution/meta-coordinator context)
        workflow-state (monitoring/build-workflow-state context iteration)
        health-check (monitoring/check-workflow-health coordinator workflow-state)]

    (if (= :halt (:status health-check))
      ;; Meta-agent signaled halt - stop workflow
      (monitoring/handle-meta-agent-halt context health-check ctx/transition-to-failed)

      ;; Healthy or warning - continue execution
      (let [phase-ctx (-> (exec/execute-phase-step pipeline context callbacks
                                                   ctx/merge-metrics
                                                   ctx/transition-to-completed
                                                   ctx/transition-to-failed)
                          (monitoring/clear-transient-state))
            ;; Persist workspace after phase completes (governed mode only).
            ;; Git commit + push to task branch so changes survive capsule destruction.
            _ (when-let [executor (get context :execution/executor)]
                (when (= :governed (get context :execution/mode))
                  (let [env-id (get context :execution/environment-id)
                        branch (get context :execution/task-branch)
                        phase  (or (:execution/current-phase phase-ctx) "unknown")]
                    (try
                      (dag-exec/persist-workspace! executor env-id
                                                   {:branch  branch
                                                    :message (str phase " phase completed")
                                                    :workdir (get context :execution/worktree-path)})
                      (catch Exception e
                        (log/warn runner-logger :workflow :workflow/persist-failed
                                  {:message (str "Workspace persist failed: " (ex-message e))}))))))
            ;; Check if phase failed due to rate limiting — if so, stop retrying
            ;; and bubble the error up to the DAG orchestrator's resilience module
            current-phase (:execution/current-phase phase-ctx)
            phase-result (get-in phase-ctx [:execution/phase-results current-phase])
            phase-error-msg (get-in phase-result [:error :message] "")
            rate-limited? (boolean
                           (re-find #"(?i)rate.?limit|429|hit your limit|quota.?exceeded"
                                    (str phase-error-msg)))]
        (if rate-limited?
          ;; Stop retrying immediately — let the DAG orchestrator handle the wait/pause
          (-> phase-ctx
              (assoc :execution/status :failed)
              (update :execution/errors conj
                      {:type :rate-limited
                       :message phase-error-msg}))
          ;; Normal path: exponential backoff when phase is retrying
          (let [retrying? (= (:execution/phase-index phase-ctx)
                             (:execution/phase-index context))
                retry-count (get-in phase-ctx [:phase :iterations] 1)]
            (when (and retrying? (> retry-count 1))
              (let [backoff-ms (min 30000 (long (* 1000 (Math/pow 2 (dec retry-count)))))]
                (Thread/sleep backoff-ms)))
            (let [event-stream (get-in phase-ctx [:execution/opts :event-stream])
                  switch-result (check-backend-health-at-boundary! event-stream phase-ctx)]
              (if switch-result
                (assoc phase-ctx :self-healing/backend-switch switch-result)
                phase-ctx))))))))

;------------------------------------------------------------------------------ Layer 1.5: Output extraction

(defn extract-output
  "Synthesize :execution/output from completed pipeline context.
   Provides a stable interface for chaining: downstream consumers
   read :execution/output instead of reaching into phase-results."
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        current-phase (:execution/current-phase ctx)
        last-result (get phase-results current-phase)]
    (assoc ctx :execution/output
           {:artifacts (:execution/artifacts ctx)
            :phase-results phase-results
            :last-phase-result last-result
            :status (:execution/status ctx)})))

;------------------------------------------------------------------------------ Layer 1.75: Post-execution hooks

(defn promote-mature-learnings!
  "Query promotable learnings from KB and auto-promote high-confidence ones.
   No-ops when knowledge-store is nil."
  [knowledge-store]
  (try
    (when knowledge-store
      (let [list-learnings (requiring-resolve 'ai.miniforge.knowledge.interface/list-learnings)
            promote-learning (requiring-resolve 'ai.miniforge.knowledge.interface/promote-learning)
            promotable (list-learnings knowledge-store {:promotable? true})]
        (doseq [learning promotable]
          (try
            (promote-learning knowledge-store (:zettel/id learning) {})
            (catch Exception _e nil)))))
    (catch Exception _e nil)))

(defn- synthesize-patterns!
  "Detect recurring patterns in KB learnings and synthesize meta-loop learnings.
   Delegates to knowledge component. No-ops when knowledge-store is nil."
  [knowledge-store]
  (try
    (when knowledge-store
      (let [synthesize (requiring-resolve 'ai.miniforge.knowledge.interface/synthesize-recurring-patterns!)]
        (synthesize knowledge-store)))
    (catch Exception _e nil)))

(defn- observe-workflow-signal!
  "Feed workflow completion/failure signal to the operator for pattern analysis.
   Callers provide :observe-signal-fn in opts — a (fn [signal]) callback that
   forwards to their operator instance. No-ops when no callback is configured."
  [opts output-ctx]
  (try
    (when-let [observe-fn (:observe-signal-fn opts)]
      (let [signal-type (if (phase/succeeded? output-ctx)
                          :workflow-complete
                          :workflow-failed)]
        (observe-fn {:signal/type signal-type
                     :workflow-id (:execution/id output-ctx)
                     :phase-results (:execution/phase-results output-ctx)
                     :metrics (:execution/metrics output-ctx)
                     :timestamp (java.time.Instant/now)})))
    (catch Exception _e nil)))

;------------------------------------------------------------------------------ Layer 2: Main entry point

(defn run-pipeline
  "Execute a workflow pipeline.

   Acquires an isolated execution environment before building the pipeline.
   The environment type depends on :execution-mode in opts:
   - :local (default) — git worktree on host; agent-on-host model
   - :governed — Docker or Kubernetes capsule required (N11 §7); fails loudly
     if no container runtime available

   The environment is released on completion regardless of success or failure.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data (may contain :repo-url and :branch)
   - opts: Execution options
     - :execution-mode     - :local (default) or :governed
     - :executor-config    - Executor config map {:docker {...} :kubernetes {...}}
     - :max-phases         - Max phases to execute (default 50)
     - :on-phase-start     - Callback fn [ctx interceptor]
     - :on-phase-complete  - Callback fn [ctx interceptor result]
     - :executor            - Pre-acquired TaskExecutor (skips acquisition)
     - :environment-id      - Pre-acquired environment ID (skips acquisition)
     - :observe-signal-fn   - Callback fn [signal] for operator integration; caller
                              wraps their operator: (fn [sig] (operator/observe-signal op sig))

   Returns final execution context."
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   (let [;; Skip acquisition if executor already provided (e.g., sandbox setup)
         has-executor? (and (:executor opts) (:environment-id opts))

         ;; Acquire isolated environment before pipeline starts
         repo-url (or (:repo-url opts) (:repo-url input))
         branch   (or (:branch opts) (:branch input) "main")
         acquired-env (when-not has-executor?
                        (acquire-execution-environment!
                         (:workflow/id workflow)
                         {:repo-url        repo-url
                          :branch          branch
                          :execution-mode  (:execution-mode opts)
                          :executor-config (:executor-config opts)}))

         ;; Merge acquired environment info into opts
         opts (if acquired-env
                (merge opts acquired-env)
                opts)

         pipeline (build-pipeline workflow)
         max-phases (get opts :max-phases 50)
         ;; Control state for dashboard commands — caller can provide their own
         control-state (or (:control-state opts)
                           (atom {:paused false :stopped false :adjustments {}}))
         event-stream (:event-stream opts)

         ;; Wrap callbacks to publish events
         callbacks {:on-phase-start (fn [ctx interceptor]
                                      (when-let [phase-name (or (:phase interceptor)
                                                                (get-in interceptor [:config :phase]))]
                                        (publish-phase-started! event-stream ctx phase-name))
                                      (when-let [cb (:on-phase-start opts)]
                                        (cb ctx interceptor)))
                    :on-phase-complete (fn [ctx interceptor result]
                                         (when-let [phase-name (or (:phase interceptor)
                                                                   (get-in interceptor [:config :phase]))]
                                           (publish-phase-completed! event-stream ctx phase-name result))
                                         (when-let [cb (:on-phase-complete opts)]
                                           (cb ctx interceptor result)))}

         skip-lifecycle? (:skip-lifecycle-events opts)
         ;; Build capsule-aware exec-fn for governed mode (N11 §6.2)
         governed?       (and (= :governed (get opts :execution-mode))
                              (get opts :executor)
                              (get opts :environment-id))
         capsule-exec-fn (when governed?
                           (llm-impl/capsule-exec-fn
                            dag-exec/execute!
                            (get opts :executor)
                            (get opts :environment-id)
                            (or (:worktree-path opts) "/workspace")))

         initial-ctx (-> (ctx/create-context workflow input opts)
                         (assoc :execution/executor (get opts :executor)
                                :execution/environment-id (get opts :environment-id)
                                :execution/worktree-path (or (:worktree-path opts)
                                                             (:sandbox-workdir opts))
                                :execution/mode (get opts :execution-mode :local)
                                ;; Pass execute! so downstream phases can call it without
                                ;; requiring dag-executor (N11 §6.2, §9.3)
                                :execution/execute-fn (when governed? dag-exec/execute!)
                                :execution/exec-fn capsule-exec-fn
                                ;; Task branch for workspace persistence (git push between phases)
                                :execution/task-branch (when governed?
                                                         (str "task/" (or (get opts :environment-id)
                                                                         (subs (str (random-uuid)) 0 8))))
                                ;; Injected so dag-orchestrator can call back into the
                                ;; runner without a circular namespace dependency.
                                :execution/run-pipeline-fn run-pipeline))]

     ;; Publish workflow started event (unless caller already did)
     (when-not skip-lifecycle?
       (publish-workflow-started! event-stream initial-ctx))

     (try
      (let [final-ctx
           (try
             (if (empty? pipeline)
               (handle-empty-pipeline initial-ctx)

               ;; Execute pipeline loop
               (loop [context initial-ctx
                      iteration 0]
                 (cond
                   ;; Terminal state reached
                   (terminal-state? context)
                   context

                   ;; Max iterations exceeded
                   (>= iteration max-phases)
                   (handle-max-phases-exceeded context max-phases)

                   ;; Execute next iteration: health check -> phase -> cleanup
                   :else
                   (recur (execute-single-iteration pipeline context callbacks iteration control-state)
                          (inc iteration)))))
             (catch Exception e
               (-> initial-ctx
                   (update :execution/errors conj
                           {:type :pipeline-exception
                            :message (ex-message e)
                            :data (ex-data e)})
                   (assoc :execution/status :failed))))

           ;; Validate completed workflows produced results
           final-ctx (if (and (phase/succeeded? final-ctx)
                              (empty? (:execution/artifacts final-ctx))
                              (empty? (:execution/phase-results final-ctx)))
                       (-> final-ctx
                           (update :execution/errors conj
                                   {:type :empty-completion
                                    :message (messages/t :status/empty-completion)})
                           (assoc :execution/status :completed-with-warnings))
                       final-ctx)
           ;; Extract output and publish workflow completed event
           output-ctx (extract-output final-ctx)]
       (when-not skip-lifecycle?
         (publish-workflow-completed! event-stream output-ctx))

       ;; Post-workflow: feed signals to operator for pattern analysis
       (observe-workflow-signal! opts output-ctx)

       ;; Post-workflow: synthesize recurring patterns into meta-loop learnings
       (synthesize-patterns! (:knowledge-store opts))

       ;; Post-workflow: auto-promote high-confidence learnings
       (promote-mature-learnings! (:knowledge-store opts))

       output-ctx)
      (finally
        (when acquired-env
          (release-execution-environment! (:executor acquired-env)
                                          (:environment-id acquired-env))
          ;; Governed mode acquires a separate host worktree — release it too.
          ;; release-execution-environment! is nil-safe, so this is a no-op
          ;; when :worktree-executor / :worktree-environment-id are absent (local mode).
          (release-execution-environment! (:worktree-executor acquired-env)
                                          (:worktree-environment-id acquired-env))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.phase.plan])
  (require '[ai.miniforge.phase.implement])
  (require '[ai.miniforge.phase.verify])
  (require '[ai.miniforge.phase.review])
  (require '[ai.miniforge.phase.release])

  ;; Simple workflow config
  (def simple-workflow
    {:workflow/id :simple-test
     :workflow/version "2.0.0"
     :workflow/pipeline
     [{:phase :plan}
      {:phase :implement}
      {:phase :done}]})

  ;; Build pipeline
  (build-pipeline simple-workflow)

  ;; Validate pipeline
  (validate-pipeline simple-workflow)

  ;; Run pipeline
  (def result
    (run-pipeline simple-workflow
                  {:task "Test task"}
                  {:on-phase-start (fn [_ctx ic]
                                     (println "Starting:" (get-in ic [:config :phase])))
                   :on-phase-complete (fn [_ctx ic result]
                                        (println "Completed:" (get-in ic [:config :phase])
                                                 "Status:" (:phase/status result)))}))

  (:execution/status result)
  (:execution/phase-results result)
  (:execution/metrics result)

  :leave-this-here)
