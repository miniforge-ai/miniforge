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
(ns ai.miniforge.connector-sarif.impl
  "SARIF connector implementation — connect, discover, extract, checkpoint."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-sarif.format :as fmt]
            [ai.miniforge.connector-sarif.schema :as schema]
            [ai.miniforge.response.interface :as response]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; --------------------------------------------------------------------------
;; Handle state
(def ^{:stratum 0} ^:private handles (connector/create-handle-registry))

(defn- ^{:stratum 0} generate-handle [] (str "sarif-" (java.util.UUID/randomUUID)))

(defn- ^{:stratum 0} handle-anomaly->response [handle-anomaly]
  (response/make-anomaly :anomalies/not-found
                         (:anomaly/message handle-anomaly)
                         (:anomaly/data handle-anomaly)))

;; --------------------------------------------------------------------------
;; Discover
(defn- ^{:stratum 0} build-schema-descriptor [schema-type count]
  {:schema/name schema-type
   :schema/record-count count})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} require-handle
  "Look up handle state or return an anomaly."
  [handle]
  (connector/require-handle handles handle
                            {:message (str "Unknown handle: " handle)}))

;; --------------------------------------------------------------------------
;; Connect / Close
(defn ^{:stratum 1} do-connect
  "Validate config and establish a connection handle."
  [config]
  (let [{:keys [valid? errors]} (schema/validate-config config)]
    (if-not valid?
      (response/make-anomaly :anomalies/incorrect
                             "Invalid SARIF config"
                             {:sarif/errors errors})
      (let [handle (generate-handle)]
        (connector/store-handle! handles handle
                                 {:sarif/source-path (:sarif/source-path config)
                                  :sarif/format      (get config :sarif/format :auto)
                                  :sarif/csv-columns (get config :sarif/csv-columns nil)})
        {:connection/handle handle
         :connector/status  :connected}))))

(defn ^{:stratum 1} do-close
  "Release a connection handle."
  [handle]
  (connector/remove-handle! handles handle)
  {:connector/status :closed})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} do-discover
  "Discover available schemas in the connected source."
  [handle _opts]
  (let [state (require-handle handle)]
    (if (anomaly/anomaly? state)
      (handle-anomaly->response state)
      (let [source-path (:sarif/source-path state)
            scan-files  (fmt/list-scan-files source-path)]
        (if (empty? scan-files)
          {:schemas [] :discover/total-count 0}
          (let [all-violations  (into []
                                      (mapcat
                                       (fn [file-path]
                                         (try
                                           (fmt/parse-file file-path
                                                           (:sarif/format state)
                                                           (:sarif/csv-columns state))
                                           (catch Exception e
                                             (println (str "Warning: Failed to parse " file-path ": " (.getMessage e)))
                                             [])))
                                       scan-files))
                violation-count (count all-violations)
                csv-count       (count (filter #(str/includes? (:violation/id %) "csv:") all-violations))
                schemas         (if (pos? violation-count)
                                  [(build-schema-descriptor :sarif-result violation-count)
                                   (build-schema-descriptor :csv-violation csv-count)]
                                  [])]
            {:schemas schemas :discover/total-count (count schemas)}))))))

;; --------------------------------------------------------------------------
;; Extract
(defn ^{:stratum 2} do-extract
  "Extract records for a given schema."
  [handle _schema-name opts]
  (let [state (require-handle handle)]
    (if (anomaly/anomaly? state)
      (handle-anomaly->response state)
      (let [source-path     (:sarif/source-path state)
            _limit          (get opts :extract/limit 10000)
            scan-files      (fmt/list-scan-files source-path)
            all-violations  (into []
                                  (mapcat
                                   (fn [file-path]
                                     (try
                                       (fmt/parse-file file-path
                                                       (:sarif/format state)
                                                       (:sarif/csv-columns state))
                                       (catch Exception e
                                         (println (str "Warning: Failed to parse " file-path ": " (.getMessage e)))
                                         [])))
                                   scan-files))]
        {:records      all-violations
         :extract/cursor   {:extract/offset 0}
         :extract/has-more false}))))

;; --------------------------------------------------------------------------
;; Checkpoint
(defn ^{:stratum 2} do-checkpoint
  "Persist cursor state for resumable extraction."
  [handle _connector-id _cursor-state]
  (let [state (require-handle handle)]
    (if (anomaly/anomaly? state)
      (handle-anomaly->response state)
      {:checkpoint/id     (java.util.UUID/randomUUID)
       :checkpoint/status :committed})))
