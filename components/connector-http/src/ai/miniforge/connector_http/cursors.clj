(ns ai.miniforge.connector-http.cursors
  "Shared timestamp-watermark cursor utilities for REST API connectors.

   Layer 0: Timestamp parsing
   Layer 1: Per-record cursor predicates and builders
   Layer 2: Collection-level cursor operations"
  (:require [clojure.string :as str])
  (:import [java.time Instant]))

;;------------------------------------------------------------------------------ Layer 0
;; Timestamp parsing

(defn parse-timestamp
  "Parse an ISO-8601 timestamp string to Instant, or nil if blank/nil."
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (Instant/parse value)))

;;------------------------------------------------------------------------------ Layer 1
;; Per-record cursor predicates and builders

(defn after-cursor?
  "True if record's timestamp is strictly after the cursor timestamp.
   timestamp-fn: (fn [record] iso-string-or-nil)
   Always returns true when cursor is nil (no prior watermark)."
  [timestamp-fn cursor record]
  (if-let [cursor-ts (some-> cursor :cursor/value parse-timestamp)]
    (some-> (timestamp-fn record)
            parse-timestamp
            (.isAfter cursor-ts))
    true))

(defn last-record-cursor
  "Compute a :timestamp-watermark cursor from the last record's :updated_at or :created_at.
   Returns nil if records is empty or resource-def :cursor-type is not :timestamp-watermark."
  [resource-def records]
  (when-let [last-record (last records)]
    (when (= :timestamp-watermark (:cursor-type resource-def))
      {:cursor/type  :timestamp-watermark
       :cursor/value (or (:updated_at last-record)
                         (:created_at last-record))})))

;;------------------------------------------------------------------------------ Layer 2
;; Collection-level cursor operations

(defn sort-by-timestamp
  "Sort records ascending by timestamp. Records with nil timestamps sort first.
   timestamp-fn: (fn [record] iso-string-or-nil)"
  [timestamp-fn records]
  (sort-by #(or (timestamp-fn %) "") records))

(defn max-timestamp-cursor
  "Compute a :timestamp-watermark cursor from the maximum timestamp across records.
   Returns nil if records is empty or none have a timestamp.
   timestamp-fn: (fn [record] iso-string-or-nil)"
  [timestamp-fn records]
  (when-let [latest-ts (some->> records (keep timestamp-fn) sort last)]
    {:cursor/type  :timestamp-watermark
     :cursor/value latest-ts}))
