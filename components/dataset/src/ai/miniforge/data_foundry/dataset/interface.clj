(ns ai.miniforge.data-foundry.dataset.interface
  (:require [ai.miniforge.data-foundry.dataset.core :as core]
            [ai.miniforge.data-foundry.dataset.version :as version]
            [ai.miniforge.data-foundry.dataset.partition :as partition]
            [ai.miniforge.data-foundry.dataset.artifact-bridge :as bridge]))

;; -- Dataset CRUD --
(defn create-dataset
  "Create a new dataset. Registers as Core artifact.
   Required: :dataset/name, :dataset/type, :dataset/schema, :dataset/storage-location
   Returns {:success? true :dataset ...} or {:success? false :errors [...]}"
  [opts]
  (core/create-dataset opts))

(defn validate-dataset
  "Validate a dataset map against N1 §3. Returns {:success? true} or {:success? false :errors [...]}"
  [dataset]
  (core/validate-dataset dataset))

(defn get-dataset
  "Retrieve a dataset by ID from a store. Returns dataset or nil."
  [store dataset-id]
  (core/get-dataset store dataset-id))

(defn save-dataset!
  "Persist dataset to artifact store. Returns {:success? true} or {:success? false :error ...}"
  [store dataset]
  (core/save-dataset! store dataset))

(defn query-datasets
  "Query datasets by criteria. Supports :dataset/type, :dataset/name, :dataset/tags."
  [store criteria]
  (core/query-datasets store criteria))

;; -- Versioning --
(defn create-version
  "Create a new immutable dataset version.
   Required: :dataset-version/dataset-id, :dataset-version/content-hash,
             :dataset-version/row-count, :dataset-version/schema-version,
             :dataset-version/workflow-run-id
   Returns {:success? true :version ...}"
  [opts]
  (version/create-version opts))

(defn validate-version
  "Validate a dataset version against N1 §6. Returns {:success? true} or {:success? false :errors [...]}"
  [version]
  (version/validate-version version))

;; -- Partitioning --
(defn create-partition-strategy
  "Create a partition strategy.
   Required: :partition/strategy (:time, :entity, :composite, :hash), :partition/keys
   Optional: :partition/retention-days"
  [opts]
  (partition/create-partition-strategy opts))

(defn validate-partition-strategy
  "Validate a partition strategy against N1 §7."
  [strategy]
  (partition/validate-partition-strategy strategy))

;; -- Artifact Bridge --
(defn dataset->artifact
  "Convert a dataset to a Core artifact map per N1 §9.1."
  [dataset]
  (bridge/dataset->artifact dataset))

(defn artifact->dataset-ref
  "Extract dataset reference info from a Core artifact."
  [artifact]
  (bridge/artifact->dataset-ref artifact))
