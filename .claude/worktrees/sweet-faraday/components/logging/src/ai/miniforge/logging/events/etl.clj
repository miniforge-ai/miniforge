(ns ai.miniforge.logging.events.etl
  "ETL lifecycle event schemas and emission helpers.
   Implements N3 §3.4 event requirements for ETL workflows."
  (:require
   [ai.miniforge.logging.core :as logging-core]))

;------------------------------------------------------------------------------ Layer 0
;; Event schema and validation

(defn make-event-base
  "Create base event map with required fields."
  [event-type workflow-id]
  {:event/type event-type
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :event/sequence-number 0  ; TODO: Implement sequence number tracking
   :workflow/id workflow-id})

;------------------------------------------------------------------------------ Layer 1
;; ETL completed event

(defn etl-completed-event
  "Create an etl/completed event per N3 §3.4.

   Arguments:
   - workflow-id - UUID of the ETL workflow
   - duration-ms - Total ETL execution time in milliseconds
   - summary - Map containing:
     * :packs-generated - Number of packs generated
     * :packs-promoted - Number of packs promoted
     * :high-risk-findings - Number of high-risk findings
     * :sources-processed - Number of sources processed

   Returns event map conforming to N3 §3.4 schema.

   Example:
     (etl-completed-event
       workflow-id
       1500
       {:packs-generated 5
        :packs-promoted 3
        :high-risk-findings 2
        :sources-processed 10})"
  [workflow-id duration-ms summary]
  (merge (make-event-base :etl/completed workflow-id)
         {:etl/duration-ms duration-ms
          :etl/summary summary
          :message "ETL workflow completed successfully"}))

;------------------------------------------------------------------------------ Layer 2
;; ETL failed event

(defn etl-failed-event
  "Create an etl/failed event per N3 §3.4.

   Arguments:
   - workflow-id - UUID of the ETL workflow
   - failure-stage - Keyword indicating where failure occurred
                     (:classification | :scanning | :extraction | :validation)
   - failure-reason - Human-readable description of the failure
   - error-details - Optional map with structured error information

   Returns event map conforming to N3 §3.4 schema.

   Example:
     (etl-failed-event
       workflow-id
       :scanning
       \"Prompt injection detected in source file\"
       {:file \"untrusted/config.md\"
        :scanner :prompt-injection-tripwire
        :severity :critical})"
  [workflow-id failure-stage failure-reason & [error-details]]
  (cond-> (merge (make-event-base :etl/failed workflow-id)
                 {:etl/failure-stage failure-stage
                  :etl/failure-reason failure-reason
                  :message (str "ETL workflow failed: " failure-reason)})
    error-details (assoc :etl/error-details error-details)))

;------------------------------------------------------------------------------ Layer 3
;; Event emission helpers

(defn emit-etl-completed
  "Emit an etl/completed event to the logger.

   Arguments:
   - logger - Logger instance
   - workflow-id - UUID of the ETL workflow
   - duration-ms - Total ETL execution time in milliseconds
   - summary - Summary statistics map

   Logs at :info level to :etl category with :etl/completed event type.

   Example:
     (emit-etl-completed logger workflow-id 1500
       {:packs-generated 5
        :packs-promoted 3
        :high-risk-findings 2
        :sources-processed 10})"
  [logger workflow-id duration-ms summary]
  (let [event (etl-completed-event workflow-id duration-ms summary)]
    (logging-core/log* logger :info :etl :etl/completed
                       {:message (:message event)
                        :data (dissoc event :message)})))

(defn emit-etl-failed
  "Emit an etl/failed event to the logger.

   Arguments:
   - logger - Logger instance
   - workflow-id - UUID of the ETL workflow
   - failure-stage - Failure stage keyword
   - failure-reason - Human-readable failure description
   - error-details - Optional structured error details

   Logs at :error level to :etl category with :etl/failed event type.

   Example:
     (emit-etl-failed logger workflow-id :scanning
       \"Prompt injection detected\"
       {:file \"config.md\" :severity :critical})"
  [logger workflow-id failure-stage failure-reason & [error-details]]
  (let [event (etl-failed-event workflow-id failure-stage failure-reason error-details)]
    (logging-core/log* logger :error :etl :etl/failed
                       {:message (:message event)
                        :data (dissoc event :message)})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.logging.interface :as log])

  ;; Create a test logger
  (def logger (log/create-logger {:min-level :info :output :human}))

  ;; Emit completed event
  (emit-etl-completed logger (random-uuid) 1500
    {:packs-generated 5
     :packs-promoted 3
     :high-risk-findings 2
     :sources-processed 10})

  ;; Emit failed event
  (emit-etl-failed logger (random-uuid)
    :scanning
    "Prompt injection detected in source file"
    {:file "untrusted/config.md"
     :scanner :prompt-injection-tripwire
     :severity :critical})

  ;; Test event structure
  (etl-completed-event (random-uuid) 1500
    {:packs-generated 5
     :packs-promoted 3
     :high-risk-findings 2
     :sources-processed 10})

  (etl-failed-event (random-uuid)
    :validation
    "Missing required field in pack"
    {:field :pack/id
     :pack-file "output/mypack.edn"})

  :leave-this-here)
