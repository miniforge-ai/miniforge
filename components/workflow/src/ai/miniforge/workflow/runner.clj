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
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.knowledge.interface :as knowledge]
            [ai.miniforge.self-healing.interface :as self-healing]
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
        (es/publish! event-stream event))
      (catch Exception e
        (println (messages/t :warn/publish-event {:error (ex-message e)}))))))

(defn publish-workflow-started!
  "Publish workflow started event using N3-compliant constructor."
  [event-stream context]
  (when event-stream
    (try
      (publish-event! event-stream
                      (es/workflow-started event-stream
                                          (:execution/id context)
                                          (select-keys (:execution/workflow context)
                                                       [:workflow/id :workflow/version])))
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
                           pr-info (assoc :pr-info pr-info))]
        (if (= status :failed)
          (publish-event! event-stream
                          (es/workflow-failed event-stream wf-id
                                             {:message (messages/t :status/failed)
                                              :errors (:execution/errors context)}))
          (publish-event! event-stream
                          (es/workflow-completed event-stream wf-id status duration-ms
                                                 (when (seq metrics-opts) metrics-opts)))))
      (catch Exception e
        (println (messages/t :warn/publish-completed {:error (ex-message e)}))))))

(defn publish-phase-started!
  "Publish phase started event."
  [event-stream context phase-name]
  (when event-stream
    (try
      (publish-event! event-stream
                      (es/phase-started event-stream (:execution/id context) phase-name
                                        {:phase/index (:execution/phase-index context)}))
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
        (publish-event! event-stream
                        (es/phase-completed event-stream (:execution/id context) phase-name
                                            event-data))
        (catch Exception e
          (println (messages/t :warn/publish-phase-completed {:error (ex-message e)})))))))

(defn check-backend-health-at-boundary!
  "Check backend health at phase boundary. No-ops when :self-healing-config is absent.
   Returns switch-result or nil."
  [event-stream context]
  (when (get-in context [:execution/opts :self-healing-config])
    (try
      (let [backend (or (get-in context [:execution/opts :llm-backend :config :backend])
                        :anthropic)
            sh-ctx {:llm {:backend backend}
                    :config (get-in context [:execution/opts :self-healing-config])}
            switch-result (self-healing/check-backend-health-and-switch sh-ctx)]
        (when (:switched? switch-result)
          (publish-event! event-stream (self-healing/emit-backend-switch-event sh-ctx switch-result))
          switch-result))
      (catch Exception e
        (println (messages/t :warn/health-check {:error (ex-message e)}))
        nil))))

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
   bootstrap the workspace inside the capsule (N11 §4.2).
   Propagates environment metadata (including :image-digest for N11 §11)."
  [executor workflow-id mode env-config {:keys [acquire-env! result-ok? result-unwrap]}]
  (let [task-id    (if (uuid? workflow-id) workflow-id (random-uuid))
        env-result (acquire-env! executor task-id env-config)]
    (when (result-ok? env-result)
      (let [env (result-unwrap env-result)]
        {:executor             executor
         :environment-id       (:environment-id env)
         :worktree-path        (:workdir env)
         :execution-mode       mode
         :environment-metadata (:metadata env)}))))

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

(defn- persist-workspace-at-phase-boundary!
  "Persist workspace to task branch after phase completes (governed mode only).
   Git commit + push so changes survive capsule destruction."
  [context phase-ctx]
  (when-let [executor (get context :execution/executor)]
    (when (= :governed (get context :execution/mode))
      (let [env-id (get context :execution/environment-id)
            branch (get context :execution/task-branch)
            phase  (or (:execution/current-phase phase-ctx) :unknown)]
        (try
          (dag-exec/persist-workspace! executor env-id
                                       {:branch  branch
                                        :message (str (name phase) " phase completed")
                                        :workdir (get context :execution/worktree-path)})
          (catch Exception e
            (log/warn runner-logger :workflow :workflow/persist-failed
                      {:message (str "Workspace persist failed: " (ex-message e))})
            nil))))))

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
            _ (persist-workspace-at-phase-boundary! context phase-ctx)
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

(defn- resolve-runtime-class
  "Return the executor's runtime class (:docker, :worktree, etc.) or nil."
  [executor]
  (when executor
    (try
      (dag-exec/executor-type executor)
      (catch Exception _ nil))))

(defn extract-output
  "Synthesize :execution/output from completed pipeline context.
   Provides a stable interface for chaining: downstream consumers
   read :execution/output instead of reaching into phase-results.

   Includes N11 §9.1 evidence fields:
   - :evidence/execution-mode  — :local or :governed
   - :evidence/runtime-class   — :docker, :worktree, or nil
   - :evidence/task-started-at — from execution context
   - :evidence/task-finished-at — captured at extraction time
   - :evidence/image-digest    — Docker image SHA256 (SHOULD per N11 §11)"
  [ctx]
  (let [phase-results (:execution/phase-results ctx)
        current-phase (:execution/current-phase ctx)
        last-result   (get phase-results current-phase)
        ;; N11 §9.1 evidence fields
        execution-mode (get ctx :execution/mode :local)
        runtime-class  (resolve-runtime-class (:execution/executor ctx))
        started-at     (:execution/started-at ctx)
        finished-at    (java.time.Instant/now)
        image-digest   (get-in ctx [:execution/environment-metadata :image-digest])]
    (assoc ctx :execution/output
           (cond-> {:artifacts          (:execution/artifacts ctx)
                    :phase-results      phase-results
                    :last-phase-result  last-result
                    :status             (:execution/status ctx)
                    ;; N11 §9.1 evidence record
                    :evidence/execution-mode  execution-mode
                    :evidence/runtime-class   runtime-class
                    :evidence/task-started-at started-at
                    :evidence/task-finished-at finished-at}
             image-digest
             (assoc :evidence/image-digest image-digest)))))

;------------------------------------------------------------------------------ Layer 1.75: Post-execution hooks

(defn promote-mature-learnings!
  "Query promotable learnings from KB and auto-promote high-confidence ones.
   No-ops when knowledge-store is nil. Returns a vector of errors (may be empty)."
  [knowledge-store]
  (let [errors (volatile! [])]
    (try
      (when knowledge-store
        (let [promotable (knowledge/list-learnings knowledge-store {:promotable? true})]
          (doseq [learning promotable]
            (try
              (knowledge/promote-learning knowledge-store (:zettel/id learning) {})
              (catch Exception e
                (vswap! errors conj {:zettel/id (:zettel/id learning)
                                     :error (ex-message e)}))))))
      (catch Exception e
        (vswap! errors conj {:error (ex-message e)})))
    @errors))

(defn- synthesize-patterns!
  "Detect recurring patterns in KB learnings and synthesize meta-loop learnings.
   Delegates to knowledge component. No-ops when knowledge-store is nil.
   Throws on failure — callers are responsible for catching and emitting events."
  [knowledge-store]
  (when knowledge-store
    (knowledge/synthesize-recurring-patterns! knowledge-store)))

(defn- observe-workflow-signal!
  "Feed workflow completion/failure signal to the operator for pattern analysis.
   Callers provide :observe-signal-fn in opts — a (fn [signal]) callback that
   forwards to their operator instance. No-ops when no callback is configured.
   When output-ctx is nil (uncaught exception), builds a minimal failure signal
   from the workflow config and exception.
   Throws on failure — callers are responsible for catching and emitting events."
  [opts output-ctx workflow exception]
  (when-let [observe-fn (:observe-signal-fn opts)]
    (let [signal (if output-ctx
                   (let [signal-type (if (phase/succeeded? output-ctx)
                                       :workflow-complete
                                       :workflow-failed)]
                     {:signal/type signal-type
                      :workflow-id (:execution/id output-ctx)
                      :phase-results (:execution/phase-results output-ctx)
                      :metrics (:execution/metrics output-ctx)
                      :timestamp (java.time.Instant/now)})
                   ;; Fallback: output-ctx unavailable due to uncaught exception
                   {:signal/type :workflow-failed
                    :workflow-id (:workflow/id workflow)
                    :error (when exception (ex-message exception))
                    :timestamp (java.time.Instant/now)})]
      (observe-fn signal))))

;------------------------------------------------------------------------------ Layer 2: Pipeline stages

(declare run-pipeline)

(def ^:private ^:const default-max-phases 50)

(defn- acquire-environment
  "Acquire an isolated execution environment (worktree or Docker capsule).
   Returns [acquired-env opts'] where acquired-env is nil when pre-acquired."
  [workflow input opts]
  (let [has-executor? (and (:executor opts) (:environment-id opts))]
    (if has-executor?
      [nil opts]
      (let [repo-url (or (:repo-url opts) (:repo-url input))
            branch   (or (:branch opts) (:branch input) "main")
            acquired (acquire-execution-environment!
                      (:workflow/id workflow)
                      {:repo-url        repo-url
                       :branch          branch
                       :execution-mode  (:execution-mode opts)
                       :executor-config (:executor-config opts)})]
        [acquired (if acquired (merge opts acquired) opts)]))))

(defn- wrap-phase-callbacks
  "Wrap caller callbacks with event publishing."
  [event-stream opts]
  {:on-phase-start
   (fn [ctx interceptor]
     (when-let [phase-name (or (:phase interceptor)
                               (get-in interceptor [:config :phase]))]
       (publish-phase-started! event-stream ctx phase-name))
     (when-let [cb (:on-phase-start opts)]
       (cb ctx interceptor)))
   :on-phase-complete
   (fn [ctx interceptor result]
     (when-let [phase-name (or (:phase interceptor)
                               (get-in interceptor [:config :phase]))]
       (publish-phase-completed! event-stream ctx phase-name result))
     (when-let [cb (:on-phase-complete opts)]
       (cb ctx interceptor result)))})

(defn- build-initial-context
  "Build the initial execution context from workflow, input, and opts."
  [workflow input opts]
  (let [governed? (and (= :governed (get opts :execution-mode))
                       (get opts :executor)
                       (get opts :environment-id))
        capsule-exec-fn (when governed?
                          (llm-impl/capsule-exec-fn
                           dag-exec/execute!
                           (get opts :executor)
                           (get opts :environment-id)
                           (get opts :worktree-path "/workspace")))]
    (-> (ctx/create-context workflow input opts)
        (assoc :execution/executor (get opts :executor)
               :execution/environment-id (get opts :environment-id)
               :execution/worktree-path (or (:worktree-path opts)
                                            (:sandbox-workdir opts))
               :execution/mode (get opts :execution-mode :local)
               :execution/started-at (java.time.Instant/now)
               :execution/environment-metadata (get opts :environment-metadata)
               :execution/execute-fn (when governed? dag-exec/execute!)
               :execution/exec-fn capsule-exec-fn
               :execution/task-branch (when governed?
                                        (str "task/" (or (get opts :environment-id)
                                                         (subs (str (random-uuid)) 0 8))))
               :execution/run-pipeline-fn run-pipeline))))

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
    (-> final-ctx
        (update :execution/errors conj
                {:type :empty-completion
                 :message (messages/t :status/empty-completion)})
        (assoc :execution/status :completed-with-warnings))
    final-ctx))

(defn- post-workflow-cleanup!
  "Post-workflow cleanup that fires on ALL exit paths.
   Each step is independently try-caught so one failure cannot mask another.
   Failures are emitted as events so the meta-loop can detect broken subsystems."
  [opts output-ctx workflow exception acquired-env]
  (let [event-stream (:event-stream opts)
        workflow-id  (or (:execution/id output-ctx) (:workflow/id workflow))]
    (try (observe-workflow-signal! opts output-ctx workflow exception)
         (catch Exception e
           (when event-stream
             (publish-event! event-stream (es/observer-signal-failed event-stream workflow-id e)))))
    (try (synthesize-patterns! (:knowledge-store opts))
         (catch Exception e
           (when event-stream
             (publish-event! event-stream (es/knowledge-synthesis-failed event-stream e)))))
    (let [errors (promote-mature-learnings! (:knowledge-store opts))]
      (doseq [err errors]
        (when event-stream
          (publish-event! event-stream
                          (es/knowledge-promotion-failed event-stream (ex-info (:error err) err))))))
    (when acquired-env
      (release-execution-environment! (:executor acquired-env)
                                      (:environment-id acquired-env))
      (release-execution-environment! (:worktree-executor acquired-env)
                                      (:worktree-environment-id acquired-env)))))

;------------------------------------------------------------------------------ Layer 3: Main entry point

(defn run-pipeline
  "Execute a workflow pipeline.

   Pipeline: acquire environment → build context → execute phases
           → validate → publish completion → cleanup (always).

   Arguments:
   - workflow: Workflow configuration
   - input: Input data (may contain :repo-url and :branch)
   - opts: Execution options (see docstring for keys)

   Returns final execution context."
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   (let [[acquired-env opts] (acquire-environment workflow input opts)
         pipeline            (build-pipeline workflow)
         max-phases          (get opts :max-phases default-max-phases)
         control-state       (or (:control-state opts)
                                 (atom {:paused false :stopped false :adjustments {}}))
         event-stream        (:event-stream opts)
         callbacks           (wrap-phase-callbacks event-stream opts)
         skip-lifecycle?     (:skip-lifecycle-events opts)
         initial-ctx         (build-initial-context workflow input opts)
         output-ctx-vol      (volatile! nil)
         exception-vol       (volatile! nil)]

     (when-not skip-lifecycle?
       (publish-workflow-started! event-stream initial-ctx))

     (try
       (let [final-ctx (try
                         (execute-pipeline-loop pipeline initial-ctx callbacks
                                               control-state max-phases)
                         (catch Exception e
                           (vreset! exception-vol e)
                           (-> initial-ctx
                               (update :execution/errors conj
                                       {:type :pipeline-exception
                                        :message (ex-message e)
                                        :data (ex-data e)})
                               (assoc :execution/status :failed))))
             output-ctx (-> final-ctx validate-completion extract-output)]
         (vreset! output-ctx-vol output-ctx)
         (when-not skip-lifecycle?
           (publish-workflow-completed! event-stream output-ctx))
         output-ctx)
       (finally
         (post-workflow-cleanup! opts @output-ctx-vol workflow
                                @exception-vol acquired-env))))))

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
