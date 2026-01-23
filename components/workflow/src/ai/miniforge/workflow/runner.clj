;; Copyright 2025 miniforge.ai
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
  "Interceptor-based workflow pipeline execution.

   Executes workflows as chains of phase interceptors.
   Each phase interceptor has :enter, :leave, and :error functions.

   - :enter - Execute the phase
   - :leave - Post-processing and metrics
   - :error - Handle failures and transitions"
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Execution context

(defn create-context
  "Create initial execution context.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data for the workflow
   - opts: Execution options

   Returns execution context map."
  [workflow input opts]
  {:execution/id (random-uuid)
   :execution/workflow-id (:workflow/id workflow)
   :execution/workflow-version (:workflow/version workflow)
   :execution/status :running
   :execution/input input
   :execution/artifacts []
   :execution/errors []  ; Kept for backward compatibility
   :execution/response-chain (response/create (:workflow/id workflow))
   :execution/phase-results {}
   :execution/current-phase nil
   :execution/phase-index 0
   :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
   :execution/started-at (System/currentTimeMillis)
   :execution/opts opts})

(defn- merge-metrics
  "Merge phase metrics into execution metrics."
  [exec-metrics phase-metrics]
  (merge-with + exec-metrics
              (select-keys phase-metrics [:tokens :cost-usd :duration-ms])))

;------------------------------------------------------------------------------ Layer 1
;; Phase transition logic

(defn- determine-next-index
  "Determine next phase index based on result and config.

   Arguments:
   - pipeline: Vector of phase interceptors
   - current-index: Current phase index
   - phase-config: Current phase configuration
   - phase-result: Result from phase execution

   Returns next index or :done."
  [pipeline current-index phase-config phase-result]
  ;; Check both :status and :phase/status for compatibility
  (let [status (or (:status phase-result) (:phase/status phase-result))
        on-fail (:on-fail phase-config)
        on-success (:on-success phase-config)]
    (cond
      ;; Phase completed successfully
      (= :completed status)
      (if on-success
        ;; Find target phase by name
        (let [target-index (->> pipeline
                                (map-indexed vector)
                                (filter #(= on-success (get-in (second %) [:config :phase])))
                                (first))]
          (if target-index (first target-index) (inc current-index)))
        ;; Default: next phase
        (let [next-idx (inc current-index)]
          (if (< next-idx (count pipeline))
            next-idx
            :done)))

      ;; Phase failed
      (= :failed status)
      (if on-fail
        ;; Find target phase by name
        (let [target-index (->> pipeline
                                (map-indexed vector)
                                (filter #(= on-fail (get-in (second %) [:config :phase])))
                                (first))]
          (if target-index
            (first target-index)
            ;; Target not found, fail the workflow
            :error))
        ;; No retry target, fail the workflow
        :error)

      ;; Default: move to next
      :else (inc current-index))))

;------------------------------------------------------------------------------ Layer 2
;; Interceptor execution

(defn- execute-enter
  "Execute the :enter function of an interceptor.

   Returns updated context."
  [interceptor ctx]
  (let [phase-name (get-in interceptor [:config :phase])]
    (if-let [enter-fn (:enter interceptor)]
      (try
        (enter-fn ctx)
        (catch Exception ex
          (if-let [error-fn (:error interceptor)]
            (error-fn ctx ex)
            (-> ctx
                (assoc :execution/status :failed)
                (update :execution/errors conj
                        {:type :phase-error
                         :phase phase-name
                         :message (ex-message ex)
                         :data (ex-data ex)})
                (update :execution/response-chain
                        response/add-failure phase-name
                        :anomalies.phase/enter-failed
                        {:error (ex-message ex)
                         :data (ex-data ex)})))))
      ctx)))

(defn- execute-leave
  "Execute the :leave function of an interceptor.

   Returns updated context."
  [interceptor ctx]
  (let [phase-name (get-in interceptor [:config :phase])]
    (if-let [leave-fn (:leave interceptor)]
      (try
        (leave-fn ctx)
        (catch Exception ex
          (-> ctx
              (update :execution/errors conj
                      {:type :leave-error
                       :phase phase-name
                       :message (ex-message ex)})
              (update :execution/response-chain
                      response/add-failure phase-name
                      :anomalies.phase/leave-failed
                      {:error (ex-message ex)}))))
      ctx)))

(defn- run-gates
  "Run gates for a phase and return result.

   Returns {:passed? bool :errors []}."
  [gate-keywords artifact ctx]
  (gate/check-gates gate-keywords artifact ctx))

;------------------------------------------------------------------------------ Layer 3
;; Pipeline execution

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

(defn- execute-phase-step
  "Execute a single phase step and return updated context.

   Arguments:
   - pipeline: Vector of interceptors
   - ctx: Current execution context
   - callbacks: Map with :on-phase-start, :on-phase-complete

   Returns updated context or nil if done."
  [pipeline ctx callbacks]
  (let [phase-index (:execution/phase-index ctx)
        interceptor (get pipeline phase-index)
        phase-name (get-in interceptor [:config :phase])
        {:keys [on-phase-start on-phase-complete]} callbacks]

    ;; Update current phase
    (let [ctx' (assoc ctx :execution/current-phase phase-name)]

      ;; Callback: phase start
      (when on-phase-start
        (on-phase-start ctx' interceptor))

      ;; Execute :enter
      (let [ctx-entered (execute-enter interceptor ctx')
            phase-result (get-in ctx-entered [:phase])

            ;; Run gates if phase has them
            gate-keywords (get-in interceptor [:config :gates] [])
            artifact (:artifact phase-result)
            gate-result (when (and (seq gate-keywords) artifact)
                          (run-gates gate-keywords artifact ctx-entered))

            ;; Update phase result with gate status
            phase-result' (if gate-result
                            (if (:passed? gate-result)
                              phase-result
                              (assoc phase-result
                                     :phase/status :failed
                                     :phase/gate-errors (:errors gate-result)))
                            phase-result)

            ;; Execute :leave
            ctx-left (execute-leave interceptor
                                    (assoc ctx-entered :phase phase-result'))

            ;; Get final phase result after :leave (may have updated status)
            final-phase-result (get-in ctx-left [:phase])

            ;; Record phase result in response chain
            ;; Check both :status and :phase/status for compatibility
            phase-succeeded? (or (= :completed (:status final-phase-result))
                                 (= :completed (:phase/status final-phase-result)))
            ctx-with-response (if phase-succeeded?
                                (update ctx-left :execution/response-chain
                                        response/add-success phase-name final-phase-result)
                                (update ctx-left :execution/response-chain
                                        response/add-failure phase-name
                                        (if (:phase/gate-errors final-phase-result)
                                          :anomalies.gate/validation-failed
                                          :anomalies.phase/agent-failed)
                                        final-phase-result))

            ;; Record phase result (legacy format)
            ctx-recorded (-> ctx-with-response
                             (assoc-in [:execution/phase-results phase-name] final-phase-result)
                             (update :execution/metrics merge-metrics
                                     (or (:metrics final-phase-result) {}))
                             (update :execution/artifacts into
                                     (or (:artifacts final-phase-result) [])))]

        ;; Callback: phase complete
        (when on-phase-complete
          (on-phase-complete ctx-recorded interceptor final-phase-result))

        ;; Determine next phase
        (let [next-index (determine-next-index
                          pipeline phase-index
                          (:config interceptor)
                          final-phase-result)]
          (cond
            ;; Workflow complete
            (= :done next-index)
            (-> ctx-recorded
                (assoc :execution/status :completed)
                (assoc :execution/ended-at (System/currentTimeMillis)))

            ;; Error - workflow failed
            (= :error next-index)
            (-> ctx-recorded
                (assoc :execution/status :failed)
                (assoc :execution/ended-at (System/currentTimeMillis)))

            ;; Continue to next phase
            (number? next-index)
            (if (< next-index (count pipeline))
              (assoc ctx-recorded :execution/phase-index next-index)
              (-> ctx-recorded
                  (assoc :execution/status :completed)
                  (assoc :execution/ended-at (System/currentTimeMillis))))

            ;; Unknown - shouldn't happen
            :else
            (-> ctx-recorded
                (assoc :execution/status :failed)
                (update :execution/errors conj
                        {:type :invalid-transition
                         :message (str "Invalid next index: " next-index)})
                (update :execution/response-chain
                        response/add-failure :pipeline
                        :anomalies.workflow/invalid-transition
                        {:error (str "Invalid next index: " next-index)
                         :next-index next-index}))))))))

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
         callbacks {:on-phase-start (:on-phase-start opts)
                    :on-phase-complete (:on-phase-complete opts)}
         initial-ctx (create-context workflow input opts)]

     (if (empty? pipeline)
       (-> initial-ctx
           (assoc :execution/status :failed)
           (update :execution/errors conj
                   {:type :empty-pipeline
                    :message "Workflow has no phases"})
           (update :execution/response-chain
                   response/add-failure :pipeline
                   :anomalies.workflow/empty-pipeline
                   {:error "Workflow has no phases"}))

       ;; Execute pipeline loop
       (loop [ctx initial-ctx
              iteration 0]
         (cond
           ;; Terminal state
           (#{:completed :failed} (:execution/status ctx))
           ctx

           ;; Max phases exceeded
           (>= iteration max-phases)
           (-> ctx
               (assoc :execution/status :failed)
               (update :execution/errors conj
                       {:type :max-phases-exceeded
                        :message (str "Exceeded maximum phase count: " max-phases)})
               (update :execution/response-chain
                       response/add-failure :pipeline
                       :anomalies.workflow/max-phases
                       {:error (str "Exceeded maximum phase count: " max-phases)
                        :max-phases max-phases})
               (assoc :execution/ended-at (System/currentTimeMillis)))

           ;; Execute next phase
           :else
           (recur (execute-phase-step pipeline ctx callbacks)
                  (inc iteration))))))))

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
                  {:on-phase-start (fn [ctx ic]
                                     (println "Starting:" (get-in ic [:config :phase])))
                   :on-phase-complete (fn [ctx ic result]
                                        (println "Completed:" (get-in ic [:config :phase])
                                                 "Status:" (:phase/status result)))}))

  (:execution/status result)
  (:execution/phase-results result)
  (:execution/metrics result)

  :leave-this-here)
