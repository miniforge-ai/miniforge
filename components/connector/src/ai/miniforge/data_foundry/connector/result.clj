(ns ai.miniforge.data-foundry.connector.result
  "Factory functions for connector protocol result maps.
   Every protocol method returns a well-known shape — these factories
   centralize construction so implementations stay DRY."
  (:import [java.time Instant]
           [java.util UUID]))

;; -- Connector lifecycle --

(defn connect-result
  "Build the map returned by Connector/connect."
  [handle]
  {:connection/handle    handle
   :connector/status     :connected
   :connection/opened-at (Instant/now)})

(defn close-result
  "Build the map returned by Connector/close."
  []
  {:connector/status     :closed
   :connection/closed-at (Instant/now)})

;; -- SourceConnector --

(defn discover-result
  "Build the map returned by SourceConnector/discover."
  [schemas]
  {:schemas              schemas
   :discover/total-count (count schemas)})

(defn extract-result
  "Build the map returned by SourceConnector/extract."
  [records cursor has-more]
  {:records              records
   :extract/cursor       cursor
   :extract/has-more     has-more
   :extract/row-count    (count records)
   :extract/completed-at (Instant/now)})

(defn checkpoint-result
  "Build the map returned by SourceConnector/checkpoint."
  [cursor-state]
  {:checkpoint/id      (UUID/randomUUID)
   :checkpoint/created (Instant/now)
   :checkpoint/status  :committed
   :checkpoint/cursor  cursor-state})

;; -- SinkConnector --

(defn publish-result
  "Build the map returned by SinkConnector/publish."
  [records-written records-failed]
  {:publish/records-written records-written
   :publish/records-failed  records-failed})
