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

(ns ai.miniforge.connector-pipeline-output.impl
  "Implementation functions for the pipeline output connector."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-pipeline-output.format :as fmt]
            [ai.miniforge.connector-pipeline-output.messages :as msg]
            [ai.miniforge.connector-pipeline-output.schema :as schema]
            [ai.miniforge.response.interface :as response])
  (:import [java.time Instant]
           [java.util UUID]))

;;------------------------------------------------------------------------------ Layer 0
;; Handle state

(def ^:private handles (atom {}))

(defn- get-handle [handle] (get @handles handle))
(defn- store-handle! [handle state] (swap! handles assoc handle state))
(defn- remove-handle! [handle] (swap! handles dissoc handle))

(defn- require-handle!
  "Retrieve handle state or return an anomaly."
  [handle]
  (or (get-handle handle)
      (response/make-anomaly :anomalies/not-found
                             (msg/t :output/handle-not-found {:handle handle})
                             {:handle handle})))

(defn- schema-validation-anomaly
  "Return an anomaly for a failed schema validation result."
  [value validation]
  (when-not (:valid? validation)
    (response/make-anomaly :anomalies/incorrect
                           "Schema validation failed"
                           {:errors (:errors validation)
                            :value  value})))

;;------------------------------------------------------------------------------ Layer 1
;; Lifecycle

(defn do-connect
  "Validate config at boundary and register handle."
  [config]
  (let [config-with-defaults (cond-> config
                               (not (:output/format config))
                               (assoc :output/format :edn))
        validation (schema/validate schema/OutputConfig config-with-defaults)]
    (if-let [anomaly (schema-validation-anomaly config-with-defaults validation)]
      anomaly
      (let [handle (str (UUID/randomUUID))]
        (store-handle! handle {:output/dir           (:output/dir config-with-defaults)
                               :output/format        (:output/format config-with-defaults)
                               :output/run-id        (get config :output/run-id (str (UUID/randomUUID)))
                               :output/pipeline-name (:output/pipeline-name config)})
        (connector/connect-result handle)))))

(defn do-close [handle]
  (remove-handle! handle)
  (connector/close-result))

;; Manifest factory

(defn- build-manifest
  "Build a manifest map."
  [handle-state schema-name records-file record-count]
  {:manifest/version       "1.0"
   :manifest/run-id        (:output/run-id handle-state)
   :manifest/pipeline-name (:output/pipeline-name handle-state)
   :manifest/format        (:output/format handle-state)
   :manifest/record-count  record-count
   :manifest/schema-name   schema-name
   :manifest/created-at    (str (Instant/now))
   :manifest/records-file  records-file})

;; Write helpers

(defn- write-and-validate-manifest!
  "Build, validate, and write the manifest + JSON Schema, or return an anomaly."
  [handle-state dir run-id schema-name records-file record-count datasets]
  (let [manifest (cond-> (build-manifest handle-state schema-name records-file record-count)
                   (seq datasets) (assoc :manifest/datasets datasets))
        manifest-contract (dissoc manifest :manifest/datasets)
        validation (schema/validate schema/Manifest manifest-contract)]
    (if-let [anomaly (schema-validation-anomaly manifest-contract validation)]
      anomaly
      (do
        (fmt/write-manifest dir run-id manifest)
        (fmt/write-json-schema dir run-id (schema/manifest-json-schema))))))

;;------------------------------------------------------------------------------ Layer 2
;; Sink operations

(defn- publish-combined!
  "Write all records as a single file."
  [handle-state records opts]
  (let [{:output/keys [dir format run-id]} handle-state
        records-file (fmt/write-records format dir run-id records)
        manifest-result (write-and-validate-manifest! handle-state dir run-id
                                                       (:publish/schema-name opts) records-file (count records) nil)]
    (if (response/anomaly-map? manifest-result)
      manifest-result
      (connector/publish-result (count records) 0))))

(defn- publish-per-dataset!
  "Write separate record files per dataset, plus a combined file."
  [handle-state records datasets opts]
  (let [{:output/keys [dir format run-id]} handle-state
        dataset-files (fmt/write-dataset-records format dir run-id datasets)
        combined-file (fmt/write-records format dir run-id records)
        manifest-result (write-and-validate-manifest! handle-state dir run-id
                                                       (:publish/schema-name opts) combined-file (count records) dataset-files)]
    (if (response/anomaly-map? manifest-result)
      manifest-result
      (connector/publish-result (count records) 0))))

(defn do-publish
  "Write records to the pipeline output store.
   When :publish/datasets is present in opts with >1 dataset, writes
   separate files per dataset alongside the combined records file."
  [handle records opts]
  (let [handle-state (require-handle! handle)
        datasets     (:publish/datasets opts)]
    (cond
      (response/anomaly-map? handle-state) handle-state
      (and datasets (> (count datasets) 1)) (publish-per-dataset! handle-state records datasets opts)
      :else (publish-combined! handle-state records opts))))

(comment
  ;; (do-connect {:output/dir "output/test" :output/format :edn})
  )
