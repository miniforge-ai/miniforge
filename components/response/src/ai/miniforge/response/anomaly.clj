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

(ns ai.miniforge.response.anomaly
  "Anomaly taxonomy, constructors, and slingshot throw integration.

   Anomalies are the single internal error representation. They are plain data
   maps that flow through the system as return values OR as thrown objects via
   slingshot throw+. Conversion to HTTP responses, user messages, log entries,
   or events happens only at system boundaries via the translate namespace.

   An anomaly map has required keys:
     :anomaly/category - keyword from the taxonomy below
     :anomaly/message  - programmer-facing diagnostic string

   Optional keys carry domain context via namespaced keys:
     :anomaly/id, :anomaly/timestamp, :anomaly/phase, :anomaly/operation
     :anomaly.gate/errors, :anomaly.agent/role, :anomaly.llm/model, etc.

   throw-anomaly! bridges return-value anomalies with exception control flow.
   Callers use slingshot try+ with key-value selectors to catch and destructure:

     (try+
       (do-work)
       (catch [:anomaly/category :anomalies/busy] {:keys [anomaly.llm/backend]}
         (backoff! backend)))

   Categories:
   - :anomalies/... - General errors (Cognitect-compatible)
   - :anomalies.phase/... - Phase execution errors
   - :anomalies.gate/... - Gate validation errors
   - :anomalies.agent/... - Agent errors
   - :anomalies.llm/... - LLM backend errors
   - :anomalies.executor/... - Execution environment errors
   - :anomalies.workflow/... - Workflow orchestration errors
   - :anomalies.dashboard/... - Dashboard control errors"
  (:require [slingshot.slingshot :refer [throw+]]))

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
    :anomalies.agent/validation-failed ; Agent output failed validation
    :anomalies.agent/llm-error})       ; LLM backend error

(def llm-anomalies
  "LLM backend anomalies."
  #{:anomalies.llm/rate-limited       ; API rate limit / 429
    :anomalies.llm/context-exceeded   ; Token limit exceeded
    :anomalies.llm/timeout            ; Backend call timed out
    :anomalies.llm/unavailable})      ; Backend unreachable

(def executor-anomalies
  "Execution environment anomalies."
  #{:anomalies.executor/unavailable   ; No executor for mode
    :anomalies.executor/timeout       ; Capsule timeout expired
    :anomalies.executor/acquisition-failed}) ; Environment acquisition failed

(def dashboard-anomalies
  "Dashboard control anomalies."
  #{:anomalies.dashboard/stop})       ; Dashboard issued stop command

(def workflow-anomalies
  "Workflow orchestration anomalies."
  #{:anomalies.workflow/empty-pipeline       ; No phases in pipeline
    :anomalies.workflow/invalid-config       ; Invalid workflow configuration
    :anomalies.workflow/max-phases           ; Exceeded max phase iterations
    :anomalies.workflow/invalid-transition   ; Invalid phase transition
    :anomalies.workflow/no-capsule-executor  ; Governed mode requested without pre-acquired capsule (N11 §7.4)
    :anomalies.workflow/rollback-limit})     ; Exceeded max rollbacks

;------------------------------------------------------------------------------ Layer 2
;; Anomaly utilities

(def all-anomalies
  "Set of all known anomaly keywords."
  (into #{}
        (concat general-anomalies
                phase-anomalies
                gate-anomalies
                agent-anomalies
                llm-anomalies
                executor-anomalies
                dashboard-anomalies
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
               :anomalies.agent/llm-error
               :anomalies.llm/rate-limited
               :anomalies.llm/timeout
               :anomalies.llm/unavailable}
             anomaly))

(defn anomaly-category
  "Get the category of an anomaly.

   Returns :general, :phase, :gate, :agent, :llm, :executor, :dashboard,
   :workflow, or nil."
  [anomaly]
  (cond
    (contains? general-anomalies anomaly)   :general
    (contains? phase-anomalies anomaly)     :phase
    (contains? gate-anomalies anomaly)      :gate
    (contains? agent-anomalies anomaly)     :agent
    (contains? llm-anomalies anomaly)       :llm
    (contains? executor-anomalies anomaly)  :executor
    (contains? dashboard-anomalies anomaly) :dashboard
    (contains? workflow-anomalies anomaly)  :workflow
    :else nil))

;------------------------------------------------------------------------------ Layer 3
;; Anomaly map constructors

(defn anomaly
  "Create a canonical anomaly map.

   Arguments:
     category - Keyword from the anomaly taxonomy (e.g. :anomalies/fault)
     message  - Programmer-facing diagnostic string
     context  - Optional map of namespaced domain context keys

   Returns:
     {:anomaly/category category
      :anomaly/message  message
      :anomaly/id       uuid
      :anomaly/timestamp inst
      ...context}"
  ([category message]
   (anomaly category message {}))
  ([category message context]
   (merge context
          {:anomaly/category category
           :anomaly/message  message
           :anomaly/id       (random-uuid)
           :anomaly/timestamp (java.time.Instant/now)})))

(defn from-exception
  "Convert an exception to a canonical anomaly map.

   Uses classifier-fn to determine the anomaly category. Falls back to
   :anomaly key in ex-data, then to :anomalies/fault.

   Arguments:
     ex            - Exception to convert
     classifier-fn - Optional (Exception -> anomaly-keyword)

   Returns: Anomaly map with exception provenance keys."
  ([ex]
   (from-exception ex nil))
  ([ex classifier-fn]
   (let [category (or (when classifier-fn (classifier-fn ex))
                      (:anomaly (ex-data ex))
                      :anomalies/fault)]
     (anomaly category
              (or (ex-message ex) (str (type ex)))
              (cond-> {:anomaly/ex-message (ex-message ex)
                       :anomaly/ex-class   (str (type ex))}
                (ex-data ex) (assoc :anomaly/ex-data (ex-data ex)))))))

;------------------------------------------------------------------------------ Layer 4
;; Anomaly map predicates

(defn anomaly-map?
  "Check if a value is a canonical anomaly map (has :anomaly/category)."
  [x]
  (and (map? x) (contains? x :anomaly/category)))

;------------------------------------------------------------------------------ Layer 5
;; Domain-specific anomaly constructors

(defn gate-anomaly
  "Create a gate-specific anomaly preserving gate error richness.

   Arguments:
     category    - Gate anomaly keyword (e.g. :anomalies.gate/validation-failed)
     message     - Diagnostic message
     gate-errors - Vector of gate error maps [{:code :message :location}]
     context     - Optional additional context map

   Returns: Anomaly map with :anomaly.gate/errors attached."
  ([category message gate-errors]
   (gate-anomaly category message gate-errors {}))
  ([category message gate-errors context]
   (anomaly category message
            (merge {:anomaly.gate/errors gate-errors} context))))

(defn agent-anomaly
  "Create an agent-specific anomaly.

   Arguments:
     category   - Agent anomaly keyword (e.g. :anomalies.agent/invoke-failed)
     message    - Diagnostic message
     agent-role - Agent role keyword (:planner, :implementer, etc.)
     context    - Optional additional context map

   Returns: Anomaly map with :anomaly.agent/role attached."
  ([category message agent-role]
   (agent-anomaly category message agent-role {}))
  ([category message agent-role context]
   (anomaly category message
            (merge {:anomaly.agent/role agent-role} context))))

;------------------------------------------------------------------------------ Layer 6
;; LLM anomaly constructors

(defn llm-anomaly
  "Create an LLM-specific anomaly.
   Common keys: :anomaly.llm/backend, :anomaly.llm/model, :anomaly.llm/status."
  ([category message backend]
   (llm-anomaly category message backend {}))
  ([category message backend context]
   (anomaly category message
            (merge {:anomaly.llm/backend backend} context))))

(defn executor-anomaly
  "Create an executor-specific anomaly.
   Common keys: :anomaly.executor/mode, :anomaly.executor/environment-id."
  ([category message mode]
   (executor-anomaly category message mode {}))
  ([category message mode context]
   (anomaly category message
            (merge {:anomaly.executor/mode mode} context))))

;------------------------------------------------------------------------------ Layer 7
;; Slingshot throw integration

(defn throw-anomaly!
  "Throw a structured anomaly via slingshot throw+.

   Builds a canonical anomaly map and throws it. Catch sites use
   slingshot try+ with key-value selectors to match and destructure:

     (try+
       (throw-anomaly! :anomalies.llm/rate-limited \"Rate limit hit\"
                       {:anomaly.llm/backend :anthropic :status 429})
       (catch [:anomaly/category :anomalies.llm/rate-limited]
              {:keys [anomaly.llm/backend]}
         (backoff! backend)))

   Arguments:
     category - Anomaly category keyword from the taxonomy
     message  - Programmer-facing diagnostic string
     context  - Optional map of domain context (namespaced keys)"
  ([category message]
   (throw-anomaly! category message {}))
  ([category message context]
   (throw+ (anomaly category message context))))

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

  ;; Anomaly maps
  (anomaly :anomalies/fault "Something broke")
  ;; => {:anomaly/category :anomalies/fault
  ;;     :anomaly/message "Something broke"
  ;;     :anomaly/id #uuid "..."
  ;;     :anomaly/timestamp #inst "..."}

  (anomaly :anomalies.gate/validation-failed "Test failed"
           {:anomaly/phase :verify
            :anomaly/operation :validate})

  (anomaly-map? (anomaly :anomalies/fault "broken"))
  ;; => true

  (anomaly-map? {:not "an anomaly"})
  ;; => false

  ;; From exception
  (from-exception (ex-info "Timeout" {:phase :verify}))
  ;; => {:anomaly/category :anomalies/fault
  ;;     :anomaly/message "Timeout"
  ;;     :anomaly/ex-data {:phase :verify}
  ;;     ...}

  (from-exception (ex-info "oops" {:anomaly :anomalies/timeout}))
  ;; => {:anomaly/category :anomalies/timeout ...}

  ;; Gate anomaly
  (gate-anomaly :anomalies.gate/validation-failed
                "Syntax gate failed"
                [{:code :syntax-error :message "Parse error" :location {:file "foo.clj" :line 10}}])

  ;; Agent anomaly
  (agent-anomaly :anomalies.agent/invoke-failed "Agent timed out" :implementer)

  :leave-this-here)
