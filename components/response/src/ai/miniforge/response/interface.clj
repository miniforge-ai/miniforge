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
   [ai.miniforge.response.anomaly :as anomaly]))

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
;; Anomaly utilities

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
