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

(ns ai.miniforge.response.anomaly
  "Anomaly taxonomy for miniforge operations.

   Anomalies are keywords that categorize failures. They provide:
   - Consistent error classification across components
   - Mapping to appropriate responses (HTTP status, retry behavior, etc.)
   - Clear semantics for error handling

   Categories:
   - :anomalies/... - General errors
   - :anomalies.phase/... - Phase execution errors
   - :anomalies.gate/... - Gate validation errors
   - :anomalies.agent/... - Agent errors
   - :anomalies.workflow/... - Workflow orchestration errors")

;------------------------------------------------------------------------------ Layer 0
;; General anomalies (compatible with cognitect.anomalies)

(def general-anomalies
  "General anomaly categories."
  #{:anomalies/unavailable      ; Temporarily unavailable, retry may succeed
    :anomalies/interrupted      ; Operation was interrupted
    :anomalies/incorrect        ; Bad input or configuration
    :anomalies/forbidden        ; Not authorized
    :anomalies/not-found        ; Resource not found
    :anomalies/conflict         ; Conflicting state
    :anomalies/fault            ; Internal error
    :anomalies/unsupported      ; Operation not supported
    :anomalies/busy             ; Rate limited or overloaded
    :anomalies/timeout})        ; Operation timed out

;------------------------------------------------------------------------------ Layer 1
;; Domain-specific anomalies

(def phase-anomalies
  "Phase execution anomalies."
  #{:anomalies.phase/unknown-phase     ; Phase type not registered
    :anomalies.phase/enter-failed      ; Phase :enter function failed
    :anomalies.phase/leave-failed      ; Phase :leave function failed
    :anomalies.phase/budget-exceeded   ; Token/time/iteration budget exceeded
    :anomalies.phase/no-agent          ; No agent configured for phase
    :anomalies.phase/agent-failed})    ; Agent invocation failed

(def gate-anomalies
  "Gate validation anomalies."
  #{:anomalies.gate/unknown-gate       ; Gate type not registered
    :anomalies.gate/check-failed       ; Gate check threw exception
    :anomalies.gate/validation-failed  ; Gate validation returned false
    :anomalies.gate/repair-failed      ; Gate repair failed
    :anomalies.gate/no-repair})        ; Gate has no repair function

(def agent-anomalies
  "Agent execution anomalies."
  #{:anomalies.agent/unknown-agent     ; Agent type not registered
    :anomalies.agent/invoke-failed     ; Agent invocation failed
    :anomalies.agent/parse-failed      ; Failed to parse agent output
    :anomalies.agent/llm-error})       ; LLM backend error

(def workflow-anomalies
  "Workflow orchestration anomalies."
  #{:anomalies.workflow/empty-pipeline   ; No phases in pipeline
    :anomalies.workflow/invalid-config   ; Invalid workflow configuration
    :anomalies.workflow/max-phases       ; Exceeded max phase iterations
    :anomalies.workflow/invalid-transition  ; Invalid phase transition
    :anomalies.workflow/rollback-limit}) ; Exceeded max rollbacks

;------------------------------------------------------------------------------ Layer 2
;; Anomaly utilities

(def all-anomalies
  "Set of all known anomaly keywords."
  (into #{}
        (concat general-anomalies
                phase-anomalies
                gate-anomalies
                agent-anomalies
                workflow-anomalies)))

(defn anomaly?
  "Check if a keyword is a known anomaly."
  [kw]
  (contains? all-anomalies kw))

(defn retryable?
  "Check if an anomaly indicates the operation may succeed on retry."
  [anomaly]
  (contains? #{:anomalies/unavailable
               :anomalies/interrupted
               :anomalies/busy
               :anomalies/timeout
               :anomalies.agent/llm-error}
             anomaly))

(defn anomaly-category
  "Get the category of an anomaly.

   Returns :general, :phase, :gate, :agent, :workflow, or nil."
  [anomaly]
  (cond
    (contains? general-anomalies anomaly) :general
    (contains? phase-anomalies anomaly) :phase
    (contains? gate-anomalies anomaly) :gate
    (contains? agent-anomalies anomaly) :agent
    (contains? workflow-anomalies anomaly) :workflow
    :else nil))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (anomaly? :anomalies/fault)
  ;; => true

  (anomaly? :not-an-anomaly)
  ;; => false

  (retryable? :anomalies/unavailable)
  ;; => true

  (retryable? :anomalies/forbidden)
  ;; => false

  (anomaly-category :anomalies.phase/enter-failed)
  ;; => :phase

  :leave-this-here)
