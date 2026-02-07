(ns ai.miniforge.response.translate
  "Boundary translators for anomaly maps.

   These functions convert canonical anomaly maps to external representations.
   They are the ONLY places where anomaly maps should be converted to
   HTTP responses, user messages, log entries, events, or evidence data.

   Internal code passes anomaly maps unchanged. Translation happens at:
   - HTTP boundary: anomaly->http-response
   - User display:  anomaly->user-message
   - Logging:       anomaly->log-data
   - Events:        anomaly->event-data
   - Evidence:      anomaly->outcome-evidence
   - Inbound:       coerce (legacy error -> anomaly map)"
  (:require
   [ai.miniforge.response.anomaly :as anomaly]))

;------------------------------------------------------------------------------ Layer 0
;; User-facing message translation (defined first — used by HTTP translator)

(def category->user-message
  "Map anomaly categories to user-friendly messages.
   These messages are safe to show to end users — no internal details."
  {;; General
   :anomalies/unavailable    "The service is temporarily unavailable. Please try again."
   :anomalies/interrupted    "The operation was interrupted. Please try again."
   :anomalies/incorrect      "The request contains invalid data. Please check your input."
   :anomalies/forbidden      "You do not have permission to perform this action."
   :anomalies/not-found      "The requested resource was not found."
   :anomalies/conflict       "The operation conflicts with the current state. Please retry."
   :anomalies/fault          "An internal error occurred."
   :anomalies/unsupported    "This operation is not supported."
   :anomalies/busy           "The system is currently busy. Please try again shortly."
   :anomalies/timeout        "The operation timed out. Please try again."
   ;; Phase
   :anomalies.phase/unknown-phase     "An unknown workflow phase was encountered."
   :anomalies.phase/enter-failed      "Failed to start a workflow phase."
   :anomalies.phase/leave-failed      "Failed to complete a workflow phase."
   :anomalies.phase/budget-exceeded   "The operation exceeded its resource budget."
   :anomalies.phase/no-agent          "No agent is configured for this workflow phase."
   :anomalies.phase/agent-failed      "The agent failed during this workflow phase."
   ;; Gate
   :anomalies.gate/unknown-gate       "An unknown validation gate was encountered."
   :anomalies.gate/check-failed       "A validation check encountered an error."
   :anomalies.gate/validation-failed  "Code validation failed. The agent will attempt repair."
   :anomalies.gate/repair-failed      "Automatic repair was attempted but failed."
   :anomalies.gate/no-repair          "No automatic repair is available for this error."
   ;; Agent
   :anomalies.agent/unknown-agent     "An unknown agent type was encountered."
   :anomalies.agent/invoke-failed     "The agent encountered an error during execution."
   :anomalies.agent/parse-failed      "Failed to parse the agent's output."
   :anomalies.agent/llm-error         "The AI model encountered an error. Retrying may help."
   ;; Workflow
   :anomalies.workflow/empty-pipeline     "The workflow has no phases configured."
   :anomalies.workflow/invalid-config     "The workflow configuration is invalid."
   :anomalies.workflow/max-phases         "The workflow exceeded the maximum number of phase iterations."
   :anomalies.workflow/invalid-transition "An invalid workflow state transition was attempted."
   :anomalies.workflow/rollback-limit     "The workflow exceeded the maximum number of rollbacks."})

(defn anomaly->user-message
  "Get a user-facing message for an anomaly map.

   Falls back to a generic message — never exposes internal details.

   Arguments:
     anomaly-map - Canonical anomaly map

   Returns: Human-friendly string."
  [anomaly-map]
  (let [category (:anomaly/category anomaly-map)]
    (or (get category->user-message category)
        (str "An error occurred: " (name category)))))

;------------------------------------------------------------------------------ Layer 1
;; HTTP boundary translation

(def category->http-status
  "Map anomaly categories to HTTP status codes."
  {;; General anomalies
   :anomalies/unavailable    503
   :anomalies/interrupted    503
   :anomalies/incorrect      400
   :anomalies/forbidden      403
   :anomalies/not-found      404
   :anomalies/conflict       409
   :anomalies/fault          500
   :anomalies/unsupported    501
   :anomalies/busy           429
   :anomalies/timeout        504
   ;; Phase anomalies
   :anomalies.phase/unknown-phase     500
   :anomalies.phase/enter-failed      500
   :anomalies.phase/leave-failed      500
   :anomalies.phase/budget-exceeded   429
   :anomalies.phase/no-agent          500
   :anomalies.phase/agent-failed      502
   ;; Gate anomalies -> 422 Unprocessable Entity
   :anomalies.gate/unknown-gate       500
   :anomalies.gate/check-failed       422
   :anomalies.gate/validation-failed  422
   :anomalies.gate/repair-failed      422
   :anomalies.gate/no-repair          422
   ;; Agent anomalies
   :anomalies.agent/unknown-agent     500
   :anomalies.agent/invoke-failed     502
   :anomalies.agent/parse-failed      422
   :anomalies.agent/llm-error         502
   ;; Workflow anomalies
   :anomalies.workflow/empty-pipeline     400
   :anomalies.workflow/invalid-config     400
   :anomalies.workflow/max-phases         500
   :anomalies.workflow/invalid-transition 500
   :anomalies.workflow/rollback-limit     500})

(defn anomaly->http-status
  "Get the HTTP status code for an anomaly category keyword.
   Returns 500 as default for unknown categories."
  [category]
  (get category->http-status category 500))

(defn anomaly->http-response
  "Translate an anomaly map to a Ring HTTP response.

   This is a BOUNDARY function — only call at HTTP edges.

   Returns:
     {:status int
      :headers {\"Content-Type\" \"application/json\"}
      :body {:error {:code string :message string}}}"
  [anomaly-map]
  (let [category (:anomaly/category anomaly-map)
        status (anomaly->http-status category)]
    {:status status
     :headers {"Content-Type" "application/json"}
     :body {:error {:code (name category)
                    :message (anomaly->user-message anomaly-map)}}}))

;------------------------------------------------------------------------------ Layer 2
;; Logging translation

(defn anomaly->log-data
  "Translate an anomaly map to structured log entry data.

   Returns a map suitable for passing as the :data field to logging/core.

   Arguments:
     anomaly-map - Canonical anomaly map

   Returns: Map with selected anomaly fields for structured logging."
  [anomaly-map]
  (let [category (:anomaly/category anomaly-map)]
    (cond-> {:anomaly/category category
             :anomaly/message  (:anomaly/message anomaly-map)}
      (anomaly/retryable? category)
      (assoc :anomaly/retryable? true)

      (:anomaly/phase anomaly-map)
      (assoc :anomaly/phase (:anomaly/phase anomaly-map))

      (:anomaly/operation anomaly-map)
      (assoc :anomaly/operation (:anomaly/operation anomaly-map))

      (:anomaly.gate/errors anomaly-map)
      (assoc :anomaly.gate/error-count (count (:anomaly.gate/errors anomaly-map)))

      (:anomaly.agent/role anomaly-map)
      (assoc :anomaly.agent/role (:anomaly.agent/role anomaly-map))

      (:anomaly/ex-class anomaly-map)
      (assoc :anomaly/ex-class (:anomaly/ex-class anomaly-map)))))

;------------------------------------------------------------------------------ Layer 3
;; Event stream translation

(defn anomaly->event-data
  "Translate an anomaly map to event-stream compatible failure data.

   Returns a map suitable for the workflow-failed event constructor.

   Arguments:
     anomaly-map - Canonical anomaly map

   Returns:
     {:message string
      :anomaly-code keyword
      :retryable? boolean
      :phase keyword|nil}"
  [anomaly-map]
  (let [category (:anomaly/category anomaly-map)]
    {:message (:anomaly/message anomaly-map)
     :anomaly-code category
     :retryable? (boolean (anomaly/retryable? category))
     :phase (:anomaly/phase anomaly-map)}))

;------------------------------------------------------------------------------ Layer 4
;; Evidence bundle translation

(defn anomaly->outcome-evidence
  "Translate an anomaly map to evidence-bundle outcome fields.

   Returns a map that can be merged into an evidence bundle's outcome section.

   Arguments:
     anomaly-map - Canonical anomaly map

   Returns:
     {:outcome/success false
      :outcome/anomaly-code keyword
      :outcome/error-message string
      :outcome/error-phase keyword|nil
      :outcome/error-details map}"
  [anomaly-map]
  {:outcome/success false
   :outcome/anomaly-code (:anomaly/category anomaly-map)
   :outcome/error-message (:anomaly/message anomaly-map)
   :outcome/error-phase (:anomaly/phase anomaly-map)
   :outcome/error-details (select-keys anomaly-map
                            [:anomaly/id
                             :anomaly/category
                             :anomaly.gate/errors
                             :anomaly.agent/role
                             :anomaly.llm/model
                             :anomaly.repair/strategy
                             :anomaly.repair/attempts])})

;------------------------------------------------------------------------------ Layer 5
;; Error coercion (any shape -> anomaly map)

(defn coerce
  "Coerce any error shape to a canonical anomaly map.

   Handles all known error shapes in the codebase:
   1. Builder {:status :error :error {:message ...}}
   2. Builder {:success false :error {:message ...}}
   3. Schema  {:success? false :error ...}
   4. Tool    {:success false :error {:type ... :message ...}}
   5. Gate    {:gate/passed? false :gate/errors [...]}
   6. Ad-hoc  {:code keyword :message string}

   Passes through values that are already anomaly maps unchanged.

   Arguments:
     error-data       - Any error shape
     default-category - Optional fallback category (default :anomalies/fault)

   Returns: Canonical anomaly map."
  ([error-data]
   (coerce error-data :anomalies/fault))
  ([error-data default-category]
   (cond
     ;; Already an anomaly map — pass through
     (anomaly/anomaly-map? error-data)
     error-data

     ;; Shape 1: {:status :error :error {:message ...}}
     (= :error (:status error-data))
     (anomaly/anomaly (or default-category :anomalies/fault)
                      (or (get-in error-data [:error :message]) "Unknown error")
                      {:anomaly/legacy-shape :builder-error})

     ;; Shape 5: gate result {:gate/passed? false}
     (false? (:gate/passed? error-data))
     (anomaly/gate-anomaly :anomalies.gate/validation-failed
                           (str "Gate " (or (:gate/id error-data) "unknown") " failed")
                           (or (:gate/errors error-data) []))

     ;; Shape 2/3/4: {:success false} or {:success? false}
     (or (false? (:success error-data))
         (false? (:success? error-data)))
     (let [msg (or (get-in error-data [:error :message])
                   (when (string? (:error error-data)) (:error error-data))
                   (first (:errors error-data))
                   "Operation failed")]
       (anomaly/anomaly (or default-category :anomalies/fault)
                        (if (string? msg) msg (pr-str msg))
                        {:anomaly/legacy-shape :success-false}))

     ;; Shape 6: ad-hoc {:code ... :message ...}
     (and (:code error-data) (:message error-data))
     (anomaly/anomaly (or default-category :anomalies/fault)
                      (:message error-data)
                      {:anomaly/legacy-code (:code error-data)})

     ;; Fallback — wrap anything else
     :else
     (anomaly/anomaly (or default-category :anomalies/fault)
                      (pr-str error-data)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; HTTP translation
  (anomaly->http-status :anomalies/not-found)
  ;; => 404

  (anomaly->http-response (anomaly/anomaly :anomalies/not-found "User 123 not found"))
  ;; => {:status 404
  ;;     :headers {"Content-Type" "application/json"}
  ;;     :body {:error {:code "not-found"
  ;;                    :message "The requested resource was not found."}}}

  ;; User message
  (anomaly->user-message (anomaly/anomaly :anomalies/timeout "LLM call timed out after 30s"))
  ;; => "The operation timed out. Please try again."

  ;; Log data
  (anomaly->log-data (anomaly/anomaly :anomalies.agent/llm-error "Model returned 500"
                                       {:anomaly/phase :implement
                                        :anomaly.agent/role :implementer}))

  ;; Event data
  (anomaly->event-data (anomaly/anomaly :anomalies/timeout "Phase timed out"
                                         {:anomaly/phase :verify}))

  ;; Coercion from legacy shapes
  (coerce {:status :error :error {:message "bad input"}})
  (coerce {:gate/passed? false :gate/id :syntax
           :gate/errors [{:code :syntax-error :message "parse error"}]})
  (coerce {:success false :error "timeout"})

  :leave-this-here)
