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

(ns ai.miniforge.gate.interface
  "Gate registry interface.

   Gates are validation checkpoints with check and repair functions.
   Each gate type registers via defmethod, providing:
   - :check - (fn [artifact ctx] -> {:passed? bool :errors [...]}
   - :repair - (fn [artifact errors ctx] -> {:success? bool :artifact ...})

   Usage:
     (get-gate :syntax)
     (check-gate :lint artifact ctx)
     (repair-gate :lint artifact errors ctx)"
  (:require
   ;; Require implementations for side effects
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]
   [ai.miniforge.gate.precommit-discipline]
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.schema.interface :as schema]))

;;------------------------------------------------------------------------------ Layer 0
;; Re-export registry functions

(def get-gate
  "Get gate implementation for a keyword.

   Arguments:
     gate-kw - Gate keyword like :syntax, :lint, :tests-pass

   Returns:
     Gate map with :name, :check, :repair"
  registry/get-gate)

(def list-gates
  "List all registered gate types.

   Returns:
     Set of gate keywords"
  registry/list-gates)

;;------------------------------------------------------------------------------ Layer 0.5
;; Gate result predicates

(defn passed?
  "Check if a gate result passed.

   Arguments:
     result - Gate result map from check-gate

   Returns: boolean"
  [result]
  (boolean (:passed? result)))

(defn failed?
  "Check if a gate result failed.

   Arguments:
     result - Gate result map from check-gate

   Returns: boolean"
  [result]
  (not (:passed? result)))

;;------------------------------------------------------------------------------ Layer 1
;; Gate operations

(defn emit-gate-event!
  "Emit a gate lifecycle event via requiring-resolve (no hard dep on event-stream)."
  [ctx gate-kw event-type & [extra]]
  (try
    (when-let [stream (get ctx :event-stream)]
      (when-let [publish-fn (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (let [constructor (case event-type
                            :started (requiring-resolve 'ai.miniforge.event-stream.interface/gate-started)
                            :passed (requiring-resolve 'ai.miniforge.event-stream.interface/gate-passed)
                            :failed (requiring-resolve 'ai.miniforge.event-stream.interface/gate-failed))]
          (publish-fn stream (constructor stream (:workflow/id ctx) gate-kw extra)))))
    (catch Exception _ nil)))

(defn check-gate
  "Run gate check on an artifact.

   Arguments:
     gate-kw  - Gate keyword
     artifact - Artifact to validate
     ctx      - Execution context

   Returns:
     {:passed? bool :errors [...] :gate gate-kw}"
  [gate-kw artifact ctx]
  (let [{:keys [check]} (get-gate gate-kw)
        start-ms (System/currentTimeMillis)]
    (emit-gate-event! ctx gate-kw :started)
    (try
      (let [result (check artifact ctx)
            result (assoc result :gate gate-kw)]
        (if (passed? result)
          (emit-gate-event! ctx gate-kw :passed (- (System/currentTimeMillis) start-ms))
          (emit-gate-event! ctx gate-kw :failed (:errors result)))
        result)
      (catch Exception ex
        (let [result {:passed? false
                      :gate gate-kw
                      :errors [{:type :check-error
                                :message (ex-message ex)
                                :data (ex-data ex)}]}]
          (emit-gate-event! ctx gate-kw :failed (:errors result))
          result)))))

(defn repair-gate
  "Attempt to repair gate failures.

   Arguments:
     gate-kw  - Gate keyword
     artifact - Artifact to repair
     errors   - Errors from check
     ctx      - Execution context

   Returns:
     {:success? bool :artifact ... :gate gate-kw}"
  [gate-kw artifact errors ctx]
  (let [{:keys [repair]} (get-gate gate-kw)]
    (if repair
      (try
        (let [result (repair artifact errors ctx)
              result (assoc result :gate gate-kw)]
          (if (:success? result)
            (emit-gate-event! ctx gate-kw :passed)
            (emit-gate-event! ctx gate-kw :failed (:errors result)))
          result)
        (catch Exception ex
          (emit-gate-event! ctx gate-kw :failed [{:type :repair-error :message (ex-message ex)}])
          (schema/exception-failure :artifact ex {:gate gate-kw :artifact artifact})))
      (schema/failure :artifact {:message "No repair function for gate"}
                      {:gate gate-kw :artifact artifact}))))

(defn check-gates
  "Run multiple gates on an artifact.

   Arguments:
     gate-kws - Vector of gate keywords
     artifact - Artifact to validate
     ctx      - Execution context

   Returns:
     {:passed? bool :results [...]}"
  [gate-kws artifact ctx]
  (let [results (mapv #(check-gate % artifact ctx) gate-kws)
        passed? (every? :passed? results)]
    {:passed? passed?
     :results results
     :failed-gates (filterv failed? results)}))

;;------------------------------------------------------------------------------ Layer 2
;; Response chain support

(defn check-gates-chain
  "Run multiple gates on an artifact, returning a response chain.

   Arguments:
     gate-kws - Vector of gate keywords
     artifact - Artifact to validate
     ctx      - Execution context

   Returns:
     Response chain with each gate as an operation.

   Example:
     (check-gates-chain [:syntax :lint] artifact ctx)
     ;; => {:operation :gates
     ;;     :succeeded? true
     ;;     :response-chain [{:operation :syntax :succeeded? true ...}
     ;;                      {:operation :lint :succeeded? true ...}]}"
  [gate-kws artifact ctx]
  (reduce
   (fn [chain gate-kw]
     (let [{:keys [check]} (get-gate gate-kw)]
       (response/execute-with-handling
        chain
        gate-kw
        (fn [ex]
          ;; Extract anomaly from exception data, or default to check-failed
          (or (:anomaly (ex-data ex))
              :anomalies.gate/check-failed))
        (fn []
          (let [result (check artifact ctx)]
            (if (passed? result)
              result
              (throw (ex-info "Gate validation failed"
                              {:anomaly :anomalies.gate/validation-failed
                               :errors (:errors result)}))))))))
   (response/create :gates)
   gate-kws))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; List available gates
  (list-gates)
  ;; => #{:syntax :lint :tests-pass :coverage :no-secrets :precommit-discipline ...}

  ;; Get a gate
  (get-gate :syntax)

  ;; Check a gate
  (check-gate :syntax {:content "(defn foo [])"} {})

  ;; Check multiple gates
  (check-gates [:syntax :lint] {:content "(defn foo [])"} {})

  ;; Check gates with response chain
  (def chain (check-gates-chain [:syntax :lint] {:content "(defn foo [])"} {}))
  (response/succeeded? chain)
  (response/operations chain)
  (response/first-failure chain)

  :leave-this-here)
