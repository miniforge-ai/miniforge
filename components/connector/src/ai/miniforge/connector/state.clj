(ns ai.miniforge.connector.state
  (:import [java.util UUID]
           [java.time Instant]))

(defn create-connector-state
  "Create initial ConnectorState per N2 §1.5."
  [connector-id]
  {:connector-state/id (UUID/randomUUID)
   :connector-state/connector-id connector-id
   :connector-state/cursor {}
   :connector-state/last-run (Instant/now)
   :connector-state/records-processed 0
   :connector-state/records-failed 0
   :connector-state/status :pending
   :connector-state/checkpoints []})

(defn update-state
  "Merge updates into connector state."
  [state updates]
  (merge state updates))

(defn record-batch
  "Record a batch of processed/failed records."
  [state records-processed records-failed]
  (-> state
      (update :connector-state/records-processed + records-processed)
      (update :connector-state/records-failed + records-failed)
      (assoc :connector-state/last-run (Instant/now))))
