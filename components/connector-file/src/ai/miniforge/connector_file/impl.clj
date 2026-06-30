;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
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

(ns ai.miniforge.connector-file.impl
  "Implementation functions for the file connector.
   Pure logic separated from protocol wiring."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-file.reader :as reader]
            [ai.miniforge.connector-file.writer :as writer]
            [ai.miniforge.connector-file.messages :as msg]
            [ai.miniforge.response.interface :as response]
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
      (nil? path) (response/throw-anomaly! :anomalies/incorrect
                                           (msg/t :file/path-required)
                                           {:config config})
      (nil? fmt)  (response/throw-anomaly! :anomalies/incorrect
                                           (msg/t :file/format-required)
                                           {:config config})
      (not (contains? supported-formats fmt))
      (response/throw-anomaly! :anomalies/unsupported
                               (msg/t :file/format-unsupported {:format fmt})
                               {:format fmt})

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

(defn- require-handle
  "Retrieve handle state or return an anomaly."
  [handle]
  (connector/require-handle handles handle
                            {:message (msg/t :file/handle-not-found {:handle handle})}))

(defn- handle-anomaly->response [handle-anomaly]
  (response/make-anomaly :anomalies/not-found
                         (:anomaly/message handle-anomaly)
                         (:anomaly/data handle-anomaly)))

(defn do-discover
  "Return schema metadata for the connected file."
  [handle]
  (let [handle-state (require-handle handle)]
    (if (anomaly/anomaly? handle-state)
      (handle-anomaly->response handle-state)
      (let [{:file/keys [path]} handle-state]
        (connector/discover-result [{:schema/name (.getName (io/file path))
                                     :schema/path path}])))))

(defn do-extract
  "Read records from file with offset-based cursor pagination."
  [handle opts]
  (let [handle-state (require-handle handle)]
    (if (anomaly/anomaly? handle-state)
      (handle-anomaly->response handle-state)
      (let [{:file/keys [path format]} handle-state
            file (io/file path)]
        (if-not (.exists file)
          (response/make-anomaly :anomalies/not-found
                                 (msg/t :file/not-found {:path path})
                                 {:path path})
          (let [all-records (reader/read-file path format)
                batch-size  (or (:extract/batch-size opts) (count all-records))
                offset      (or (get-in opts [:extract/cursor :cursor/value]) 0)
                batch       (vec (take batch-size (drop offset all-records)))
                new-offset  (+ offset (count batch))
                has-more    (< new-offset (count all-records))]
            (connector/extract-result
             batch
             {:cursor/type :offset :cursor/value new-offset}
             has-more)))))))

(defn do-checkpoint
  "Persist cursor state (no-op for files, returns committed)."
  [cursor-state]
  (connector/checkpoint-result cursor-state))

;; -- Sink --

(defn do-publish
  "Write records to file. Returns publish-result map."
  [handle records opts]
  (let [handle-state (require-handle handle)]
    (if (anomaly/anomaly? handle-state)
      (handle-anomaly->response handle-state)
      (let [{:file/keys [path format]} handle-state
            mode    (or (:publish/mode opts) :overwrite)
            written (writer/write-file path records format mode)]
        (connector/publish-result written 0)))))
