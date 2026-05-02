(ns ai.miniforge.connector-file.impl
  "Implementation functions for the file connector.
   Pure logic separated from protocol wiring."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-file.reader :as reader]
            [ai.miniforge.connector-file.writer :as writer]
            [ai.miniforge.connector-file.messages :as msg]
            [clojure.java.io :as io])
  (:import [java.util UUID]))

(def supported-formats #{:csv :json :edn})

(def ^:private handles (connector/create-handle-registry))

(defn get-handle [handle] (connector/get-handle handles handle))
(defn store-handle! [handle state] (connector/store-handle! handles handle state))
(defn remove-handle! [handle] (connector/remove-handle! handles handle))

;; -- Lifecycle --

(defn do-connect
  "Validate config and register a handle. Returns connect-result map."
  [config]
  (let [path (:file/path config)
        fmt  (:file/format config)]
    (cond
      (nil? path) (throw (ex-info (msg/t :file/path-required) {:config config}))
      (nil? fmt)  (throw (ex-info (msg/t :file/format-required) {:config config}))
      (not (contains? supported-formats fmt))
      (throw (ex-info (msg/t :file/format-unsupported {:format fmt}) {:format fmt}))

      :else
      (let [handle (str (UUID/randomUUID))]
        (store-handle! handle {:file/path path :file/format fmt})
        (connector/connect-result handle)))))

(defn do-close
  "Remove handle and return close-result map."
  [handle]
  (remove-handle! handle)
  (connector/close-result))

;; -- Source --

(defn- require-handle!
  "Retrieve handle state or throw, delegating to the shared helper."
  [handle]
  (connector/require-handle! handles handle
                             {:message (msg/t :file/handle-not-found {:handle handle})}))

(defn do-discover
  "Return schema metadata for the connected file."
  [handle]
  (let [{:file/keys [path]} (require-handle! handle)]
    (connector/discover-result [{:schema/name (.getName (io/file path))
                                 :schema/path path}])))

(defn do-extract
  "Read records from file with offset-based cursor pagination."
  [handle opts]
  (let [{:file/keys [path format]} (require-handle! handle)
        file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (msg/t :file/not-found {:path path}) {:path path})))
    (let [all-records (reader/read-file path format)
          batch-size  (or (:extract/batch-size opts) (count all-records))
          offset      (or (get-in opts [:extract/cursor :cursor/value]) 0)
          batch       (vec (take batch-size (drop offset all-records)))
          new-offset  (+ offset (count batch))
          has-more    (< new-offset (count all-records))]
      (connector/extract-result
       batch
       {:cursor/type :offset :cursor/value new-offset}
       has-more))))

(defn do-checkpoint
  "Persist cursor state (no-op for files, returns committed)."
  [cursor-state]
  (connector/checkpoint-result cursor-state))

;; -- Sink --

(defn do-publish
  "Write records to file. Returns publish-result map."
  [handle records opts]
  (let [{:file/keys [path format]} (require-handle! handle)
        mode    (or (:publish/mode opts) :overwrite)
        written (writer/write-file path records format mode)]
    (connector/publish-result written 0)))
