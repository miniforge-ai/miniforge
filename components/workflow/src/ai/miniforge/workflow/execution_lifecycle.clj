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
(ns ai.miniforge.workflow.execution-lifecycle
  "One phase through enter -> gates -> leave, and its result recorded into
   the execution context: response chain, phase results, metrics and
   artifacts.

   Split out of `execution` (rule 210). The gates themselves run in
   `execution-transition`."
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution-transition :as transition]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Atomic operations
(defn- ^{:stratum 0} enter-error-record
  "Build the canonical entry recorded against `:execution/errors` for a
   phase-enter exception. Used uniformly whether or not the interceptor
   defines its own `:error` handler, so the `workflow/failed` event
   always carries the underlying exception message + ex-data."
  [phase-name ex anom]
  {:type :phase-error
   :phase phase-name
   :message (ex-message ex)
   :data (ex-data ex)
   :anomaly anom})

(defn ^{:stratum 0} execute-leave
  "Execute the :leave function of an interceptor.

   Returns updated context."
  [interceptor ctx]
  (let [phase-name (get-in interceptor [:config :phase])]
    (if-let [leave-fn (:leave interceptor)]
      (try
        (leave-fn ctx)
        (catch Exception ex
          (let [anom (response/from-exception ex)]
            (-> ctx
                (update :execution/errors conj
                        {:type :leave-error
                         :phase phase-name
                         :message (ex-message ex)
                         :anomaly anom})
                (update :execution/response-chain
                        response/add-failure phase-name
                        (assoc anom :anomaly/category :anomalies.phase/leave-failed
                                    :anomaly/phase phase-name)
                        {:error (ex-message ex)})))))
      ctx)))

(defn- ^{:stratum 0} entered-phase-context?
  "True when the phase enter step established the standard phase context.

   Enter-path exceptions are normalized by the interceptor error handler into
   `{:phase {:status :failed :error ...}}`, but that path does not populate the
   normal enter-context keys like `:started-at` or `:result`. In that state the
   corresponding `:leave` function has no valid phase context to finalize."
  [phase-result]
  (and (map? phase-result)
       (contains? phase-result :started-at)
       (contains? phase-result :result)))

(defn ^{:stratum 0} extract-phase-result
  "Extract phase result from context."
  [ctx]
  (get-in ctx [:phase]))

(defn ^{:stratum 0} record-phase-metrics
  "Record phase metrics in execution context."
  [ctx phase-result merge-metrics-fn]
  (update ctx :execution/metrics merge-metrics-fn
          (get phase-result :metrics {})))

(defn ^{:stratum 0} track-phase-files
  "Track files written by phase for workflow supervision.

   In the new environment model, code changes live in the execution
   environment's git working tree (:execution/worktree-path) rather than
   being serialized into phase results. File tracking via :code/files in
   phase output is therefore a no-op; actual file discovery happens at
   release time via git diff."
  [ctx _phase-result]
  ;; Phase results no longer carry :code/files.
  ;; File changes are in the environment's worktree, captured at release time.
  ctx)

(defn ^{:stratum 0} record-phase-artifacts
  "Record phase artifacts in execution context.

   In the new environment model, phase results carry provenance metadata
   (:environment-id, :summary, :metrics) rather than serialized :code/files.
   The recorded artifact captures lightweight provenance metadata for the
   evidence bundle."
  [ctx phase-result]
  (let [result   (get phase-result :result)
        artifact (when (map? result)
                   (not-empty (select-keys result [:status :environment-id
                                                   :summary :metrics])))]
    (update ctx :execution/artifacts into (if artifact [artifact] []))))

(defn ^{:stratum 0} update-response-chain
  "Update response chain with phase result."
  [ctx phase-name phase-result]
  (if (transition/phase-succeeded? phase-result)
    (update ctx :execution/response-chain
            response/add-success phase-name phase-result)
    (let [gate-errors (:phase/gate-errors phase-result)
          envelope (:phase/decision-envelope phase-result)
          anomaly (if gate-errors
                    (response/gate-anomaly
                     :anomalies.gate/validation-failed
                     (messages/t :phase/gate-failed-phase {:phase (name phase-name)})
                     gate-errors
                     (cond-> {:anomaly/phase phase-name}
                       envelope (assoc :anomaly/envelope-id (:envelope/id envelope))))
                    (response/make-anomaly
                     :anomalies.phase/agent-failed
                     (messages/t :phase/agent-failed-phase {:phase (name phase-name)})
                     {:anomaly/phase phase-name}))]
      (update ctx :execution/response-chain
              response/add-failure phase-name
              anomaly
              phase-result))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-enter
  "Execute the :enter function of an interceptor.

   Returns updated context. When the enter function throws, the
   exception's message + ex-data are appended to `:execution/errors`
   so the eventual `workflow/failed` event surfaces the failure. This
   accumulator is populated even when the interceptor defines its own
   `:error` handler (the handler still runs to set `[:phase :error]`,
   but the workflow-level error list is the authoritative source for
   downstream consumers such as the CLI and bridge surfaces)."
  [interceptor ctx]
  (let [phase-name (get-in interceptor [:config :phase])]
    (if-let [enter-fn (:enter interceptor)]
      (try
        (enter-fn ctx)
        (catch Exception ex
          (let [anom (response/from-exception ex)
                error-record (enter-error-record phase-name ex anom)
                ctx-with-error (-> ctx
                                   (update :execution/errors conj error-record)
                                   (update :execution/response-chain
                                           response/add-failure phase-name
                                           (assoc anom
                                                  :anomaly/category :anomalies.phase/enter-failed
                                                  :anomaly/phase phase-name)
                                           {:error (ex-message ex)
                                            :data (ex-data ex)}))]
            (if-let [error-fn (:error interceptor)]
              (error-fn ctx-with-error ex)
              (context/transition-to-failed ctx-with-error)))))
      ctx)))

(defn ^{:stratum 1} process-phase-result
  "Process phase result: update response chain, record metrics/files/artifacts.

   Returns updated context."
  [ctx phase-name phase-result merge-metrics-fn]
  (-> ctx
      (update-response-chain phase-name phase-result)
      (assoc-in [:execution/phase-results phase-name] phase-result)
      (record-phase-metrics phase-result merge-metrics-fn)
      (record-phase-artifacts phase-result)
      (track-phase-files phase-result)))

;------------------------------------------------------------------------------ Layer 2

;; Composition
(defn ^{:stratum 2} execute-phase-lifecycle
  "Execute phase enter -> gates -> leave lifecycle.

   Returns [ctx phase-result]."
  [interceptor ctx]
  ;; Clear :phase map before each phase to prevent stale transition requests
  ;; from leaking across phase boundaries.
  (let [ctx-clean (dissoc ctx :phase)
        ctx-entered (execute-enter interceptor ctx-clean)
        phase-result (extract-phase-result ctx-entered)
        phase-result-gated (transition/apply-gate-validation interceptor phase-result ctx-entered)
        ctx-left (if (entered-phase-context? phase-result-gated)
                   (execute-leave interceptor (assoc ctx-entered :phase phase-result-gated))
                   (assoc ctx-entered :phase phase-result-gated))]
    [ctx-left (extract-phase-result ctx-left)]))
