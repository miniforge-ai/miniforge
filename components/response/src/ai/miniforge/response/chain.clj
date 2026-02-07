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

(ns ai.miniforge.response.chain
  "Response chain for tracking operation sequences.

   A response chain is an immutable data structure that accumulates
   the results of sequential operations. Each operation adds an entry
   to the chain with its result, and the overall success status is
   calculated as all-operations-succeeded.

   Response chain structure:
   {:operation keyword        ; Root operation name
    :succeeded? boolean       ; True if ALL operations succeeded
    :response-chain vector}   ; Vector of wrapped responses

   Each chain entry structure:
   {:operation keyword        ; Operation name
    :anomaly keyword|nil      ; Anomaly code if failed, nil if succeeded
    :anomaly-map map|nil      ; Full anomaly map when available
    :succeeded? boolean       ; True if this operation succeeded
    :response any}            ; Operation result data

   Usage:
     (-> (create :my-workflow)
         (add-response :step-1 nil {:result \"ok\"})
         (add-response :step-2 nil {:result \"done\"})
         (succeeded?))
     ;; => true"
  (:require
   [ai.miniforge.response.anomaly :as anomaly]))

;------------------------------------------------------------------------------ Layer 0
;; Chain creation

(defn create
  "Create a new response chain.

   Arguments:
     operation - Keyword identifying the root operation

   Returns:
     {:operation operation
      :succeeded? true        ; Starts as true, becomes false on first failure
      :response-chain []}"
  [operation]
  {:operation operation
   :succeeded? true
   :response-chain []})

;------------------------------------------------------------------------------ Layer 1
;; Wrapping responses

(defn wrap-response
  "Wrap an operation result with metadata.

   Arguments:
     operation - Keyword identifying the operation
     anomaly   - Anomaly keyword, anomaly map, or nil if succeeded
     response  - Operation result data

   When anomaly is a map (anomaly map), both :anomaly (keyword) and
   :anomaly-map (full map) are set. When anomaly is a keyword (legacy),
   only :anomaly is set. This provides backward compatibility.

   Returns:
     {:operation operation
      :anomaly keyword|nil       ; Always a keyword for backward compat
      :anomaly-map map|nil       ; Full anomaly map when available
      :succeeded? (nil? anomaly)
      :response response}"
  [operation anomaly response]
  (let [anomaly-map (when (map? anomaly) anomaly)
        anomaly-kw (cond
                     (nil? anomaly) nil
                     (keyword? anomaly) anomaly
                     (map? anomaly) (:anomaly/category anomaly)
                     :else anomaly)]
    {:operation operation
     :anomaly anomaly-kw
     :anomaly-map anomaly-map
     :succeeded? (nil? anomaly)
     :response response}))

;------------------------------------------------------------------------------ Layer 2
;; Chain operations

(defn add-response
  "Add an operation result to the response chain.

   Arguments:
     chain     - Response chain
     operation - Keyword identifying the operation
     anomaly   - Anomaly keyword if failed, nil if succeeded
     response  - Operation result data

   Returns:
     Updated chain with new entry and recalculated succeeded? status"
  [chain operation anomaly response]
  (let [entry (wrap-response operation anomaly response)
        chain' (update chain :response-chain conj entry)
        all-succeeded? (every? :succeeded? (:response-chain chain'))]
    (assoc chain' :succeeded? all-succeeded?)))

(defn add-success
  "Add a successful operation result to the chain.

   Shorthand for (add-response chain op nil response)"
  [chain operation response]
  (add-response chain operation nil response))

(defn add-failure
  "Add a failed operation result to the chain.

   Arguments:
     chain     - Response chain
     operation - Keyword identifying the operation
     anomaly   - Anomaly keyword (required)
     response  - Error details or partial result"
  [chain operation anomaly response]
  {:pre [(some? anomaly)]}
  (add-response chain operation anomaly response))

;------------------------------------------------------------------------------ Layer 3
;; Chain queries

(defn succeeded?
  "Check if all operations in the chain succeeded."
  [chain]
  (:succeeded? chain))

(defn failed?
  "Check if any operation in the chain failed."
  [chain]
  (not (:succeeded? chain)))

(defn last-entry
  "Get the most recent entry in the chain."
  [chain]
  (peek (:response-chain chain)))

(defn last-response
  "Get the response from the most recent entry."
  [chain]
  (:response (last-entry chain)))

(defn last-anomaly
  "Get the anomaly from the most recent entry (nil if succeeded)."
  [chain]
  (:anomaly (last-entry chain)))

(defn last-operation
  "Get the operation name from the most recent entry."
  [chain]
  (:operation (last-entry chain)))

(defn last-response-or-default
  "Get the last response if succeeded, otherwise return default."
  [chain default]
  (if (succeeded? chain)
    (last-response chain)
    default))

(defn first-failure
  "Get the first failed entry in the chain, or nil if all succeeded."
  [chain]
  (first (filter (complement :succeeded?) (:response-chain chain))))

(defn all-failures
  "Get all failed entries in the chain."
  [chain]
  (filterv (complement :succeeded?) (:response-chain chain)))

(defn errors
  "Get errors from the chain in a flat format.

   Returns a vector of error maps with:
   - :type - The operation name as a keyword prefixed with 'error-'
   - :operation - The original operation name
   - :anomaly - The anomaly code (keyword)
   - :anomaly-map - Full anomaly map when available (nil for legacy entries)
   - :message - Error message from response or anomaly map
   - :data - Additional error data from response

   This provides a migration path from :execution/errors to response chains."
  [chain]
  (->> (all-failures chain)
       (mapv (fn [{:keys [operation anomaly anomaly-map response]}]
               (cond-> {:type (keyword (str "error-" (name operation)))
                        :operation operation
                        :anomaly anomaly
                        :message (or (:anomaly/message anomaly-map)
                                     (:error response)
                                     (:message response)
                                     (str "Operation " operation " failed"))
                        :data (dissoc response :error :message)}
                 anomaly-map (assoc :anomaly-map anomaly-map))))))

(defn operations
  "Get the sequence of operation names in order."
  [chain]
  (mapv :operation (:response-chain chain)))

(defn responses
  "Get all responses in order."
  [chain]
  (mapv :response (:response-chain chain)))

(defn entry-count
  "Get the number of entries in the chain."
  [chain]
  (count (:response-chain chain)))

;------------------------------------------------------------------------------ Layer 4
;; Chain transformations

(defn merge-metrics
  "Merge metrics from all responses in the chain.

   Assumes each response has a :metrics map with numeric values."
  [chain]
  (reduce (fn [acc entry]
            (if-let [metrics (get-in entry [:response :metrics])]
              (merge-with + acc metrics)
              acc))
          {:tokens 0 :cost-usd 0.0 :duration-ms 0}
          (:response-chain chain)))

(defn collect-artifacts
  "Collect all artifacts from responses in the chain.

   Assumes each response may have an :artifacts vector."
  [chain]
  (into []
        (comp (map :response)
              (mapcat :artifacts)
              (filter some?))
        (:response-chain chain)))

(defn summarize
  "Create a summary of the chain execution.

   Returns:
     {:operation root-operation
      :succeeded? boolean
      :entry-count number
      :operations [keywords]
      :first-failure entry|nil
      :metrics merged-metrics
      :artifacts collected-artifacts}"
  [chain]
  {:operation (:operation chain)
   :succeeded? (succeeded? chain)
   :entry-count (entry-count chain)
   :operations (operations chain)
   :first-failure (first-failure chain)
   :metrics (merge-metrics chain)
   :artifacts (collect-artifacts chain)})

;------------------------------------------------------------------------------ Layer 5
;; Exception handling helper

(defn execute-with-handling
  "Execute a function and add its result to the chain.

   On success: adds (operation, nil, result) to chain
   On exception: adds (operation, anomaly-map, {:error message :data ex-data})

   Arguments:
     chain      - Response chain
     operation  - Keyword identifying the operation
     anomaly-fn - Function (Exception -> anomaly-keyword) to classify errors
     f          - Function to execute (no args)

   Returns:
     Updated chain"
  [chain operation anomaly-fn f]
  (try
    (let [result (f)]
      (add-success chain operation result))
    (catch Exception ex
      (let [category (or (anomaly-fn ex) :anomalies/fault)
            anom (anomaly/from-exception ex (constantly category))]
        (add-failure chain operation anom
                     {:error (ex-message ex)
                      :data (ex-data ex)})))))

(defn default-anomaly-classifier
  "Default function to classify exceptions into anomalies."
  [ex]
  (let [data (ex-data ex)]
    (cond
      ;; If ex-data contains an anomaly, use it
      (:anomaly data) (:anomaly data)

      ;; Classify by exception type
      (instance? java.util.concurrent.TimeoutException ex)
      :anomalies/timeout

      (instance? InterruptedException ex)
      :anomalies/interrupted

      ;; Default to fault
      :else :anomalies/fault)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a chain and add operations
  (def chain
    (-> (create :my-workflow)
        (add-success :plan {:artifacts [{:type :plan}]
                           :metrics {:tokens 100}})
        (add-success :implement {:artifacts [{:type :code}]
                                :metrics {:tokens 500}})
        (add-failure :verify :anomalies.gate/validation-failed
                     {:errors ["test failed"]})))

  (succeeded? chain)
  ;; => false

  (first-failure chain)
  ;; => {:operation :verify, :anomaly :anomalies.gate/validation-failed, ...}

  (merge-metrics chain)
  ;; => {:tokens 600, :cost-usd 0.0, :duration-ms 0}

  (collect-artifacts chain)
  ;; => [{:type :plan} {:type :code}]

  (operations chain)
  ;; => [:plan :implement :verify]

  (summarize chain)

  ;; With exception handling
  (-> (create :safe-operation)
      (execute-with-handling :step-1 default-anomaly-classifier
                             #(do "success"))
      (execute-with-handling :step-2 default-anomaly-classifier
                             #(throw (ex-info "oops" {:code 123})))
      summarize)

  :leave-this-here)
