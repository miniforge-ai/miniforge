(ns ai.miniforge.data-foundry.connector-pipeline-output.impl
  "Implementation functions for the pipeline output connector."
  (:require [ai.miniforge.data-foundry.connector.result :as result]
            [ai.miniforge.data-foundry.connector-pipeline-output.format :as fmt]
            [ai.miniforge.data-foundry.connector-pipeline-output.messages :as msg]
            [ai.miniforge.data-foundry.connector-pipeline-output.schema :as schema])
  (:import [java.time Instant]
           [java.util UUID]))

;;------------------------------------------------------------------------------ Layer 0
;; Handle state

(def ^:private handles (atom {}))

(defn- get-handle [handle] (get @handles handle))
(defn- store-handle! [handle state] (swap! handles assoc handle state))
(defn- remove-handle! [handle] (swap! handles dissoc handle))

(defn- require-handle!
  "Retrieve handle state or throw."
  [handle]
  (or (get-handle handle)
      (throw (ex-info (msg/t :output/handle-not-found {:handle handle})
                      {:handle handle}))))

;;------------------------------------------------------------------------------ Layer 1
;; Lifecycle

(defn do-connect
  "Validate config at boundary and register handle."
  [config]
  (let [config-with-defaults (cond-> config
                               (not (:output/format config))
                               (assoc :output/format :edn))]
    (schema/validate! schema/OutputConfig config-with-defaults)
    (let [handle (str (UUID/randomUUID))]
      (store-handle! handle {:output/dir           (:output/dir config-with-defaults)
                              :output/format        (:output/format config-with-defaults)
                              :output/run-id        (get config :output/run-id (str (UUID/randomUUID)))
                              :output/pipeline-name (:output/pipeline-name config)})
      (result/connect-result handle))))

(defn do-close [handle]
  (remove-handle! handle)
  (result/close-result))

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
  "Build, validate, and write the manifest + JSON Schema."
  [handle-state dir run-id schema-name records-file record-count datasets]
  (let [manifest (cond-> (build-manifest handle-state schema-name records-file record-count)
                   (seq datasets) (assoc :manifest/datasets datasets))]
    (schema/validate! schema/Manifest (dissoc manifest :manifest/datasets))
    (fmt/write-manifest dir run-id manifest)
    (fmt/write-json-schema dir run-id (schema/manifest-json-schema))))

;;------------------------------------------------------------------------------ Layer 2
;; Sink operations

(defn- publish-combined!
  "Write all records as a single file."
  [handle-state records opts]
  (let [{:output/keys [dir format run-id]} handle-state
        records-file (fmt/write-records format dir run-id records)]
    (write-and-validate-manifest! handle-state dir run-id
                                   (:publish/schema-name opts) records-file (count records) nil)
    (result/publish-result (count records) 0)))

(defn- publish-per-dataset!
  "Write separate record files per dataset, plus a combined file."
  [handle-state records datasets opts]
  (let [{:output/keys [dir format run-id]} handle-state
        dataset-files (fmt/write-dataset-records format dir run-id datasets)
        combined-file (fmt/write-records format dir run-id records)]
    (write-and-validate-manifest! handle-state dir run-id
                                   (:publish/schema-name opts) combined-file (count records) dataset-files)
    (result/publish-result (count records) 0)))

(defn do-publish
  "Write records to the pipeline output store.
   When :publish/datasets is present in opts with >1 dataset, writes
   separate files per dataset alongside the combined records file."
  [handle records opts]
  (let [handle-state (require-handle! handle)
        datasets     (:publish/datasets opts)]
    (if (and datasets (> (count datasets) 1))
      (publish-per-dataset! handle-state records datasets opts)
      (publish-combined! handle-state records opts))))

(comment
  ;; (do-connect {:output/dir "output/test" :output/format :edn})
  )
