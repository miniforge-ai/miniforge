(ns ai.miniforge.data-foundry.connector-file.impl
  "Implementation functions for the file connector.
   Pure logic separated from protocol wiring."
  (:require [ai.miniforge.data-foundry.connector.handles :as h]
            [ai.miniforge.data-foundry.connector.result :as result]
            [ai.miniforge.data-foundry.connector-file.reader :as reader]
            [ai.miniforge.data-foundry.connector-file.writer :as writer]
            [ai.miniforge.data-foundry.connector-file.messages :as msg]
            [clojure.java.io :as io])
  (:import [java.util UUID]))

(def supported-formats #{:csv :json :edn})

(def ^:private handles (h/create))

(defn get-handle [handle] (h/get-handle handles handle))
(defn store-handle! [handle state] (h/store-handle! handles handle state))
(defn remove-handle! [handle] (h/remove-handle! handles handle))

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
        (result/connect-result handle)))))

(defn do-close
  "Remove handle and return close-result map."
  [handle]
  (remove-handle! handle)
  (result/close-result))

;; -- Source --

(defn do-discover
  "Return schema metadata for the connected file."
  [handle]
  (if-let [{:file/keys [path]} (get-handle handle)]
    (result/discover-result [{:schema/name (.getName (io/file path))
                              :schema/path path}])
    (throw (ex-info (msg/t :file/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-extract
  "Read records from file with offset-based cursor pagination."
  [handle opts]
  (if-let [{:file/keys [path format]} (get-handle handle)]
    (let [file (io/file path)]
      (when-not (.exists file)
        (throw (ex-info (msg/t :file/not-found {:path path}) {:path path})))
      (let [all-records (reader/read-file path format)
            batch-size  (or (:extract/batch-size opts) (count all-records))
            offset      (or (get-in opts [:extract/cursor :cursor/value]) 0)
            batch       (vec (take batch-size (drop offset all-records)))
            new-offset  (+ offset (count batch))
            has-more    (< new-offset (count all-records))]
        (result/extract-result
         batch
         {:cursor/type :offset :cursor/value new-offset}
         has-more)))
    (throw (ex-info (msg/t :file/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-checkpoint
  "Persist cursor state (no-op for files, returns committed)."
  [cursor-state]
  (result/checkpoint-result cursor-state))

;; -- Sink --

(defn do-publish
  "Write records to file. Returns publish-result map."
  [handle records opts]
  (if-let [{:file/keys [path format]} (get-handle handle)]
    (let [mode    (or (:publish/mode opts) :overwrite)
          written (writer/write-file path records format mode)]
      (result/publish-result written 0))
    (throw (ex-info (msg/t :file/handle-not-found {:handle handle}) {:handle handle}))))
