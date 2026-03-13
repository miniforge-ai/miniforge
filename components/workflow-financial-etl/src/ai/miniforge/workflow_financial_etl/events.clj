(ns ai.miniforge.workflow-financial-etl.events
  "ETL lifecycle event schemas and emission helpers."
  (:require
   [ai.miniforge.logging.core :as logging-core]))

(defn make-event-base
  [event-type workflow-id]
  {:event/type event-type
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :event/sequence-number 0
   :workflow/id workflow-id})

(defn etl-completed-event
  [workflow-id duration-ms summary]
  (merge (make-event-base :etl/completed workflow-id)
         {:etl/duration-ms duration-ms
          :etl/summary summary
          :message "ETL workflow completed successfully"}))

(defn etl-failed-event
  [workflow-id failure-stage failure-reason & [error-details]]
  (cond-> (merge (make-event-base :etl/failed workflow-id)
                 {:etl/failure-stage failure-stage
                  :etl/failure-reason failure-reason
                  :message (str "ETL workflow failed: " failure-reason)})
    error-details (assoc :etl/error-details error-details)))

(defn emit-etl-completed
  [logger workflow-id duration-ms summary]
  (let [event (etl-completed-event workflow-id duration-ms summary)]
    (logging-core/log* logger :info :etl :etl/completed
                       {:message (:message event)
                        :data (dissoc event :message)})))

(defn emit-etl-failed
  [logger workflow-id failure-stage failure-reason & [error-details]]
  (let [event (etl-failed-event workflow-id failure-stage failure-reason error-details)]
    (logging-core/log* logger :error :etl :etl/failed
                       {:message (:message event)
                        :data (dissoc event :message)})))
