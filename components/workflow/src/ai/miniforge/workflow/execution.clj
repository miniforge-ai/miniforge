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

(ns ai.miniforge.workflow.execution
  "Phase execution lifecycle.

   Handles execution of individual phase steps through the enter -> gates -> leave lifecycle.
   Processes results, tracks metrics/files/artifacts, and determines phase transitions."
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0: Atomic operations

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

(defn- extract-phase-result
  "Extract phase result from context."
  [ctx]
  (get-in ctx [:phase]))

(defn- apply-gate-validation
  "Apply gate validation to phase result.

   Returns updated phase-result with :phase/status and :phase/gate-errors if gates fail."
  [interceptor phase-result ctx]
  (let [gate-keywords (get-in interceptor [:config :gates] [])
        artifact (:artifact phase-result)]
    (if (and (seq gate-keywords) artifact)
      (let [gate-result (gate/check-gates gate-keywords artifact ctx)]
        (if (:passed? gate-result)
          phase-result
          (assoc phase-result
                 :phase/status :failed
                 :phase/gate-errors (:errors gate-result))))
      phase-result)))

(defn- phase-succeeded?
  "Check if phase completed successfully."
  [phase-result]
  (or (= :completed (:status phase-result))
      (= :completed (:phase/status phase-result))))

(defn- update-response-chain
  "Update response chain with phase result."
  [ctx phase-name phase-result]
  (if (phase-succeeded? phase-result)
    (update ctx :execution/response-chain
            response/add-success phase-name phase-result)
    (update ctx :execution/response-chain
            response/add-failure phase-name
            (if (:phase/gate-errors phase-result)
              :anomalies.gate/validation-failed
              :anomalies.phase/agent-failed)
            phase-result)))

(defn- record-phase-metrics
  "Record phase metrics in execution context."
  [ctx phase-result merge-metrics-fn]
  (update ctx :execution/metrics merge-metrics-fn
          (or (:metrics phase-result) {})))

(defn- track-phase-files
  "Track files written by phase for meta-agent monitoring."
  [ctx phase-result]
  (update ctx :execution/files-written into
          (or (:files-written phase-result)
              (:artifacts phase-result)
              [])))

(defn- record-phase-artifacts
  "Record phase artifacts in execution context."
  [ctx phase-result]
  (update ctx :execution/artifacts into
          (or (:artifacts phase-result) [])))

;------------------------------------------------------------------------------ Layer 1: Composition

(defn- execute-phase-lifecycle
  "Execute phase enter -> gates -> leave lifecycle.

   Returns [ctx phase-result]."
  [interceptor ctx]
  (let [ctx-entered (execute-enter interceptor ctx)
        phase-result (extract-phase-result ctx-entered)
        phase-result-gated (apply-gate-validation interceptor phase-result ctx-entered)
        ctx-left (execute-leave interceptor (assoc ctx-entered :phase phase-result-gated))]
    [ctx-left (extract-phase-result ctx-left)]))

(defn- process-phase-result
  "Process phase result: update response chain, record metrics/files/artifacts.

   Returns updated context."
  [ctx phase-name phase-result merge-metrics-fn]
  (-> ctx
      (update-response-chain phase-name phase-result)
      (assoc-in [:execution/phase-results phase-name] phase-result)
      (record-phase-metrics phase-result merge-metrics-fn)
      (record-phase-artifacts phase-result)
      (track-phase-files phase-result)))

(defn- determine-next-index
  "Determine next phase index based on result and config.

   Arguments:
   - pipeline: Vector of phase interceptors
   - current-index: Current phase index
   - phase-config: Current phase configuration
   - phase-result: Result from phase execution

   Returns next index or :done/:error."
  [pipeline current-index phase-config phase-result]
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
            :error))
        :error)

      ;; Default: move to next
      :else (inc current-index))))

(defn- apply-phase-transition
  "Apply phase transition based on next-index.

   Returns context with updated status/phase-index or terminal state."
  [ctx next-index pipeline transition-to-completed-fn transition-to-failed-fn]
  (cond
    (= :done next-index)
    (transition-to-completed-fn ctx)

    (= :error next-index)
    (transition-to-failed-fn ctx)

    (number? next-index)
    (if (< next-index (count pipeline))
      (assoc ctx :execution/phase-index next-index)
      (transition-to-completed-fn ctx))

    :else
    (-> ctx
        (update :execution/errors conj
                {:type :invalid-transition
                 :message (str "Invalid next index: " next-index)})
        (update :execution/response-chain
                response/add-failure :pipeline
                :anomalies.workflow/invalid-transition
                {:error (str "Invalid next index: " next-index)
                 :next-index next-index})
        (transition-to-failed-fn))))

;------------------------------------------------------------------------------ Layer 2: Phase step execution

(defn execute-phase-step
  "Execute a single phase step and return updated context.

   Arguments:
   - pipeline: Vector of interceptors
   - ctx: Current execution context
   - callbacks: Map with :on-phase-start, :on-phase-complete
   - merge-metrics-fn: Function to merge phase metrics
   - transition-to-completed-fn: Function to transition to completed state
   - transition-to-failed-fn: Function to transition to failed state

   Returns updated context."
  [pipeline ctx callbacks merge-metrics-fn transition-to-completed-fn transition-to-failed-fn]
  (let [phase-index (:execution/phase-index ctx)
        interceptor (get pipeline phase-index)
        phase-name (get-in interceptor [:config :phase])
        {:keys [on-phase-start on-phase-complete]} callbacks
        ctx-with-phase (assoc ctx :execution/current-phase phase-name)]

    ;; Notify phase start
    (when on-phase-start
      (on-phase-start ctx-with-phase interceptor))

    ;; Execute phase lifecycle: enter -> gates -> leave
    (let [[ctx-after-lifecycle phase-result] (execute-phase-lifecycle interceptor ctx-with-phase)
          ;; Process result: response chain + metrics + files + artifacts
          ctx-processed (process-phase-result ctx-after-lifecycle phase-name phase-result merge-metrics-fn)]

      ;; Notify phase complete
      (when on-phase-complete
        (on-phase-complete ctx-processed interceptor phase-result))

      ;; Determine and apply next transition
      (let [next-index (determine-next-index pipeline phase-index
                                             (:config interceptor)
                                             phase-result)]
        (apply-phase-transition ctx-processed next-index pipeline
                                transition-to-completed-fn
                                transition-to-failed-fn)))))
