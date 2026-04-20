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

(ns ai.miniforge.response.interface
  "Response chain public interface.

   Response chains track sequences of operations with their results,
   providing consistent error handling and operation history.

   Core workflow:
   1. Create a chain: (create :my-workflow)
   2. Add results:    (add-success chain :step-1 result)
                      (add-failure chain :step-2 :anomalies/fault error)
   3. Query status:   (succeeded? chain)
   4. Get summary:    (summarize chain)"
  (:require
   [ai.miniforge.response.chain :as chain]
   [ai.miniforge.response.anomaly :as anomaly]
   [ai.miniforge.response.builder :as builder]
   [ai.miniforge.response.translate :as translate]))

;------------------------------------------------------------------------------ Layer 0
;; Chain creation

(def create
  "Create a new response chain.

   Arguments:
     operation - Keyword identifying the root operation

   Returns:
     {:operation operation
      :succeeded? true
      :response-chain []}

   Example:
     (create :workflow-execution)"
  chain/create)

;------------------------------------------------------------------------------ Layer 1
;; Adding responses

(def add-response
  "Add an operation result to the response chain.

   Arguments:
     chain     - Response chain
     operation - Keyword identifying the operation
     anomaly   - Anomaly keyword if failed, nil if succeeded
     response  - Operation result data

   Returns:
     Updated chain with new entry and recalculated succeeded? status

   Example:
     (add-response chain :plan nil {:artifacts [...]})
     (add-response chain :verify :anomalies.gate/validation-failed {:errors [...]})"
  chain/add-response)

(def add-success
  "Add a successful operation result to the chain.

   Shorthand for (add-response chain op nil response)

   Example:
     (add-success chain :implement {:code \"...\"})"
  chain/add-success)

(def add-failure
  "Add a failed operation result to the chain.

   Arguments:
     chain     - Response chain
     operation - Keyword identifying the operation
     anomaly   - Anomaly keyword (required)
     response  - Error details or partial result

   Example:
     (add-failure chain :verify :anomalies.gate/validation-failed
                  {:errors [\"test failed\"]})"
  chain/add-failure)

;------------------------------------------------------------------------------ Layer 2
;; Chain queries

(def succeeded?
  "Check if all operations in the chain succeeded.

   Example:
     (if (succeeded? chain)
       (last-response chain)
       (first-failure chain))"
  chain/succeeded?)

(def failed?
  "Check if any operation in the chain failed."
  chain/failed?)

(def last-entry
  "Get the most recent entry in the chain.

   Returns:
     {:operation :step-name
      :anomaly nil|keyword
      :succeeded? boolean
      :response result-data}"
  chain/last-entry)

(def last-response
  "Get the response from the most recent entry."
  chain/last-response)

(def last-anomaly
  "Get the anomaly from the most recent entry (nil if succeeded)."
  chain/last-anomaly)

(def last-operation
  "Get the operation name from the most recent entry."
  chain/last-operation)

(def last-response-or-default
  "Get the last response if succeeded, otherwise return default.

   Example:
     (last-response-or-default chain {:status :unknown})"
  chain/last-response-or-default)

(def first-failure
  "Get the first failed entry in the chain, or nil if all succeeded."
  chain/first-failure)

(def all-failures
  "Get all failed entries in the chain."
  chain/all-failures)

(def errors
  "Get errors from the chain in a flat format.

   Returns a vector of error maps with:
   - :type - The operation name as a keyword prefixed with 'error-'
   - :operation - The original operation name
   - :anomaly - The anomaly code
   - :message - Error message from response
   - :data - Additional error data from response

   This provides a migration path from :execution/errors to response chains.

   Example:
     (errors chain)
     ;; => [{:type :error-verify
     ;;      :operation :verify
     ;;      :anomaly :anomalies.gate/validation-failed
     ;;      :message \"test failed\"
     ;;      :data {:errors [...]}}]"
  chain/errors)

(def operations
  "Get the sequence of operation names in order.

   Example:
     (operations chain)
     ;; => [:plan :implement :verify]"
  chain/operations)

(def responses
  "Get all responses in order."
  chain/responses)

(def entry-count
  "Get the number of entries in the chain."
  chain/entry-count)

;------------------------------------------------------------------------------ Layer 3
;; Chain transformations

(def merge-metrics
  "Merge metrics from all responses in the chain.

   Assumes each response has a :metrics map with numeric values.
   Returns merged {:tokens :cost-usd :duration-ms} map."
  chain/merge-metrics)

(def collect-artifacts
  "Collect all artifacts from responses in the chain.

   Assumes each response may have an :artifacts vector."
  chain/collect-artifacts)

(def summarize
  "Create a summary of the chain execution.

   Returns:
     {:operation root-operation
      :succeeded? boolean
      :entry-count number
      :operations [keywords]
      :first-failure entry|nil
      :metrics merged-metrics
      :artifacts collected-artifacts}"
  chain/summarize)

;------------------------------------------------------------------------------ Layer 4
;; Exception handling

(def execute-with-handling
  "Execute a function and add its result to the chain.

   On success: adds (operation, nil, result) to chain
   On exception: adds (operation, anomaly, {:error message :data ex-data})

   Arguments:
     chain      - Response chain
     operation  - Keyword identifying the operation
     anomaly-fn - Function (Exception -> anomaly-keyword) to classify errors
     f          - Function to execute (no args)

   Returns:
     Updated chain

   Example:
     (execute-with-handling chain :step-1 default-anomaly-classifier
                            #(risky-operation))"
  chain/execute-with-handling)

(def default-anomaly-classifier
  "Default function to classify exceptions into anomalies."
  chain/default-anomaly-classifier)

;------------------------------------------------------------------------------ Layer 5
;; Anomaly utilities (keyword-based)

(def anomaly?
  "Check if a keyword is a known anomaly."
  anomaly/anomaly?)

(def retryable?
  "Check if an anomaly indicates the operation may succeed on retry."
  anomaly/retryable?)

(def anomaly-category
  "Get the category of an anomaly.

   Returns :general, :phase, :gate, :agent, :workflow, or nil."
  anomaly/anomaly-category)

;------------------------------------------------------------------------------ Layer 5.1
;; Anomaly map constructors

(def make-anomaly
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
      ...context}

   Example:
     (make-anomaly :anomalies/fault \"Something broke\")
     (make-anomaly :anomalies.gate/validation-failed \"Test failed\"
                   {:anomaly/phase :verify})"
  anomaly/anomaly)

(def from-exception
  "Convert an exception to a canonical anomaly map.

   Uses classifier-fn to determine the anomaly category. Falls back to
   :anomaly key in ex-data, then to :anomalies/fault.

   Arguments:
     ex            - Exception to convert
     classifier-fn - Optional (Exception -> anomaly-keyword)

   Returns: Anomaly map with exception provenance keys.

   Example:
     (from-exception (ex-info \"Timeout\" {:phase :verify}))
     (from-exception ex my-classifier)"
  anomaly/from-exception)

(def anomaly-map?
  "Check if a value is a canonical anomaly map (has :anomaly/category).

   Example:
     (anomaly-map? (make-anomaly :anomalies/fault \"broken\")) ;; => true
     (anomaly-map? {:not \"an anomaly\"})                      ;; => false"
  anomaly/anomaly-map?)

(def gate-anomaly
  "Create a gate-specific anomaly preserving gate error richness.

   Arguments:
     category    - Gate anomaly keyword (e.g. :anomalies.gate/validation-failed)
     message     - Diagnostic message
     gate-errors - Vector of gate error maps [{:code :message :location}]
     context     - Optional additional context map

   Example:
     (gate-anomaly :anomalies.gate/validation-failed \"Syntax check failed\"
                   [{:code :syntax-error :message \"Parse error\" :location {:file \"foo.clj\" :line 10}}])"
  anomaly/gate-anomaly)

(def agent-anomaly
  "Create an agent-specific anomaly.

   Arguments:
     category   - Agent anomaly keyword (e.g. :anomalies.agent/invoke-failed)
     message    - Diagnostic message
     agent-role - Agent role keyword (:planner, :implementer, etc.)
     context    - Optional additional context map

   Example:
     (agent-anomaly :anomalies.agent/invoke-failed \"Agent timed out\" :implementer)"
  anomaly/agent-anomaly)

(def llm-anomaly
  "Create an LLM-specific anomaly.
   Common keys: :anomaly.llm/backend, :anomaly.llm/model, :anomaly.llm/status.

   Example:
     (llm-anomaly :anomalies.llm/rate-limited \"Rate limit hit\" :anthropic
                  {:anomaly.llm/status 429 :anomaly.llm/retry-after-ms 30000})"
  anomaly/llm-anomaly)

(def executor-anomaly
  "Create an executor-specific anomaly.
   Common keys: :anomaly.executor/mode, :anomaly.executor/environment-id.

   Example:
     (executor-anomaly :anomalies.executor/unavailable \"No Docker\" :governed)"
  anomaly/executor-anomaly)

(def throw-anomaly!
  "Throw a structured anomaly via slingshot throw+.

   Builds a canonical anomaly map and throws it. Catch sites use
   slingshot try+ with key-value selectors to match and destructure:

     (try+
       (throw-anomaly! :anomalies.llm/rate-limited \"Rate limit hit\"
                       {:anomaly.llm/backend :anthropic})
       (catch [:anomaly/category :anomalies.llm/rate-limited]
              {:keys [anomaly.llm/backend]}
         (backoff! backend)))

   Arguments:
     category - Anomaly category keyword from the taxonomy
     message  - Programmer-facing diagnostic string
     context  - Optional map of domain context (namespaced keys)"
  anomaly/throw-anomaly!)

(def retryable?
  "Check if an anomaly category indicates the operation may succeed on retry.

   Example:
     (retryable? :anomalies.llm/rate-limited) ;; => true
     (retryable? :anomalies/forbidden)        ;; => false"
  anomaly/retryable?)

;------------------------------------------------------------------------------ Layer 6
;; Response Builders

(def success
  "Create a canonical success response.

   Arguments:
   - output: The main result data
   - opts: Optional map with :metrics, :artifact, :tokens, :duration-ms

   Example:
     (success test-artifact {:tokens 100 :duration-ms 500})"
  builder/success)

(def success?
  "True for response maps produced by `success` (have :status :success).

   Use on single result maps; for response chains, use `succeeded?`."
  builder/success?)

(def error?
  "True for response maps produced by `error` or `failure`.

   Matches :status :error, :status :failed, or :success false.
   Use on single result maps; for response chains, use `failed?`."
  builder/error?)

(def error
  "Create a canonical error response.

   Arguments:
   - message: Error message string or Exception
   - opts: Optional map with :data, :metrics, :output, :tokens, :duration-ms

   Example:
     (error \"Test failed\" {:tokens 50 :duration-ms 200})
     (error ex {:data (ex-data ex)})"
  builder/error)

(def failure
  "Create a canonical failure response (alias for error with :success false).

   Example:
     (failure \"Agent timeout\" {:tokens 0 :duration-ms 5000})"
  builder/failure)

(def validation-result
  "Create validation result map.

   Arguments:
   - errors: Vector of error strings (empty = valid)

   Example:
     (validation-result [])
     (validation-result [\"Missing field\" \"Invalid format\"])"
  builder/validation-result)

(def status-check
  "Create a timestamped status check response.

   Use for point-in-time observations like health checks, monitoring, polling.
   Always includes :checked-at timestamp. For immediate operation results without
   timestamps, use success/error instead.

   Arguments:
   - status - Status keyword (:healthy, :warning, :halt, or custom)
   - opts - Optional map with :message, :data, :agent/id, or other context

   Example:
     (status-check :healthy {:agent/id :progress-monitor
                             :message \"Making progress\"
                             :data {:chunks 5}})"
  builder/status-check)

;------------------------------------------------------------------------------ Layer 8
;; Boundary translators

(def anomaly->http-status
  "Get the HTTP status code for an anomaly category keyword."
  translate/anomaly->http-status)

(def anomaly->http-response
  "Translate an anomaly map to a Ring HTTP response.
   BOUNDARY function — only call at HTTP edges.

   Returns:
     {:status int
      :headers {\"Content-Type\" \"application/json\"}
      :body {:error {:code string :message string}}}"
  translate/anomaly->http-response)

(def anomaly->user-message
  "Get a user-facing message for an anomaly map.
   Falls back to a generic message — never exposes internal details."
  translate/anomaly->user-message)

(def anomaly->log-data
  "Translate an anomaly map to structured log entry data.
   Returns a map suitable for the :data field in logging."
  translate/anomaly->log-data)

(def anomaly->event-data
  "Translate an anomaly map to event-stream compatible failure data.
   Returns {:message :anomaly-code :retryable? :phase}."
  translate/anomaly->event-data)

(def anomaly->outcome-evidence
  "Translate an anomaly map to evidence-bundle outcome fields."
  translate/anomaly->outcome-evidence)

(def coerce
  "Coerce any error shape to a canonical anomaly map.
   Handles all known shapes. Passes through existing anomaly maps."
  translate/coerce)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create and build a chain
  (def chain
    (-> (create :my-workflow)
        (add-success :plan {:artifacts [{:type :plan}]
                           :metrics {:tokens 100}})
        (add-success :implement {:artifacts [{:type :code}]
                                :metrics {:tokens 500}})
        (add-failure :verify :anomalies.gate/validation-failed
                     {:errors ["test failed"]})))

  (succeeded? chain)        ;; => false
  (operations chain)        ;; => [:plan :implement :verify]
  (first-failure chain)     ;; => {:operation :verify, :anomaly ..., ...}
  (merge-metrics chain)     ;; => {:tokens 600, ...}
  (collect-artifacts chain) ;; => [{:type :plan} {:type :code}]
  (summarize chain)

  ;; Check anomalies
  (anomaly? :anomalies.gate/validation-failed) ;; => true
  (retryable? :anomalies/unavailable)          ;; => true
  (anomaly-category :anomalies.phase/enter-failed) ;; => :phase

  :leave-this-here)
