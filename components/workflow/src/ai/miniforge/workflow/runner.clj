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
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.execution :as exec]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer -1: Event publishing

(defn- publish-event!
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
            (println "Warning: Failed to send event via WebSocket:" (ex-message e))))

        ;; In-memory event stream (same process)
        :else
        (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
          (when es-ns
            (when-let [publish! (ns-resolve es-ns 'publish!)]
              (publish! event-stream event)))))
      (catch Exception e
        (println "Warning: Failed to publish event:" (ex-message e))))))

(defn- publish-workflow-started!
  "Publish workflow started event using N3-compliant constructor."
  [event-stream context]
  (when event-stream
    (try
      (when-let [constructor (requiring-resolve 'ai.miniforge.event-stream.interface/workflow-started)]
        (publish-event! event-stream
                        (constructor event-stream
                                     (:execution/id context)
                                     (select-keys (:execution/workflow context)
                                                  [:workflow/id :workflow/version]))))
      (catch Exception _
        ;; Fallback to ad-hoc event if constructor not available
        (publish-event! event-stream
                        {:event/type :workflow/started
                         :workflow-id (:execution/id context)
                         :workflow-spec (select-keys (:execution/workflow context)
                                                     [:workflow/id :workflow/version])
                         :timestamp (java.time.Instant/now)})))))

(defn- publish-workflow-completed!
  "Publish workflow completed/failed event using N3-compliant constructor."
  [event-stream context]
  (when event-stream
    (try
      (let [status (:execution/status context)
            wf-id (:execution/id context)
            duration-ms (get-in context [:execution/metrics :duration-ms])]
        (if (= status :failed)
          (when-let [constructor (requiring-resolve 'ai.miniforge.event-stream.interface/workflow-failed)]
            (publish-event! event-stream
                            (constructor event-stream wf-id
                                         {:message "Workflow failed"
                                          :errors (:execution/errors context)})))
          (when-let [constructor (requiring-resolve 'ai.miniforge.event-stream.interface/workflow-completed)]
            (publish-event! event-stream
                            (constructor event-stream wf-id status duration-ms)))))
      (catch Exception _
        ;; Fallback to ad-hoc event
        (publish-event! event-stream
                        {:event/type :workflow/completed
                         :workflow-id (:execution/id context)
                         :status (:execution/status context)
                         :metrics (:execution/metrics context)
                         :timestamp (java.time.Instant/now)})))))

(defn- publish-phase-started!
  "Publish phase started event."
  [event-stream context phase-name]
  (publish-event! event-stream
                  {:event/type :workflow/phase-started
                   :workflow-id (:execution/id context)
                   :phase phase-name
                   :timestamp (java.time.Instant/now)}))

(defn- publish-phase-completed!
  "Publish phase completed event."
  [event-stream context phase-name result]
  (publish-event! event-stream
                  {:event/type :workflow/phase-completed
                   :workflow-id (:execution/id context)
                   :phase phase-name
                   :success? (:success? result)
                   :timestamp (java.time.Instant/now)}))

(defn- check-backend-health-at-boundary!
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
      (println "Warning: Self-healing health check failed:" (ex-message e))
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

;------------------------------------------------------------------------------ Layer 1: Orchestration helpers

(defn- handle-empty-pipeline
  "Handle error case of empty pipeline."
  [context]
  (-> context
      (update :execution/errors conj
              {:type :empty-pipeline
               :message "Workflow has no phases"})
      (update :execution/response-chain
              response/add-failure :pipeline
              :anomalies.workflow/empty-pipeline
              {:error "Workflow has no phases"})
      (ctx/transition-to-failed)))

(defn- handle-max-phases-exceeded
  "Handle error case of max phases exceeded."
  [context max-phases]
  (-> context
      (update :execution/errors conj
              {:type :max-phases-exceeded
               :message (str "Exceeded maximum phase count: " max-phases)})
      (update :execution/response-chain
              response/add-failure :pipeline
              :anomalies.workflow/max-phases
              {:error (str "Exceeded maximum phase count: " max-phases)
               :max-phases max-phases})
      (ctx/transition-to-failed)))

(defn- terminal-state?
  "Check if workflow is in terminal state."
  [context]
  (#{:completed :failed} (:execution/status context)))

(defn- execute-single-iteration
  "Execute single pipeline iteration: health check -> phase execution -> cleanup.

   Returns updated context."
  [pipeline context callbacks iteration control-state]
  (let [;; Check control state from dashboard
        state @control-state

        ;; Handle pause - wait until resumed
        _ (when (:paused state)
            (println "⏸  Workflow paused, waiting for resume...")
            (while (:paused @control-state)
              (Thread/sleep 1000)))

        ;; Check for stop command
        _ (when (:stopped state)
            (println "⏹  Workflow stopped by dashboard")
            (throw (ex-info "Workflow stopped by control plane" {:reason :dashboard-stop})))

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
            event-stream (get-in phase-ctx [:execution/opts :event-stream])
            switch-result (check-backend-health-at-boundary! event-stream phase-ctx)]
        (if switch-result
          (assoc phase-ctx :self-healing/backend-switch switch-result)
          phase-ctx)))))

;------------------------------------------------------------------------------ Layer 2: Main entry point

(defn run-pipeline
  "Execute a workflow pipeline.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data
   - opts: Execution options
     - :max-phases - Max phases to execute (default 50)
     - :on-phase-start - Callback fn [ctx interceptor]
     - :on-phase-complete - Callback fn [ctx interceptor result]

   Returns final execution context."
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   (let [pipeline (build-pipeline workflow)
         max-phases (or (:max-phases opts) 50)
         ;; Control state for dashboard commands — caller can provide their own
         control-state (or (:control-state opts)
                           (atom {:paused false :stopped false :adjustments {}}))
         event-stream (:event-stream opts)

         ;; Wrap callbacks to publish events
         callbacks {:on-phase-start (fn [ctx interceptor]
                                      (when-let [phase-name (:phase interceptor)]
                                        (publish-phase-started! event-stream ctx phase-name))
                                      (when-let [cb (:on-phase-start opts)]
                                        (cb ctx interceptor)))
                    :on-phase-complete (fn [ctx interceptor result]
                                         (when-let [phase-name (:phase interceptor)]
                                           (publish-phase-completed! event-stream ctx phase-name result))
                                         (when-let [cb (:on-phase-complete opts)]
                                           (cb ctx interceptor result)))}

         skip-lifecycle? (:skip-lifecycle-events opts)
         initial-ctx (ctx/create-context workflow input opts)]

     ;; Publish workflow started event (unless caller already did)
     (when-not skip-lifecycle?
       (publish-workflow-started! event-stream initial-ctx))

     (let [final-ctx
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
                        (inc iteration)))))]

       ;; Publish workflow completed event (unless caller already does)
       (when-not skip-lifecycle?
         (publish-workflow-completed! event-stream final-ctx))
       final-ctx))))

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
