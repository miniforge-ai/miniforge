(ns ai.miniforge.data-foundry.dataset.core
  (:require [ai.miniforge.data-foundry.dataset-schema.interface :as ds-schema]
            [ai.miniforge.data-foundry.dataset.artifact-bridge :as bridge]
            [ai.miniforge.data-foundry.dataset.messages :as msg]
            [ai.miniforge.artifact.interface :as artifact])
  (:import [java.util UUID]
           [java.time Instant]))

(def default-config
  "Default dataset validation configuration."
  {:dataset/name-min-length 3
   :dataset/name-max-length 128
   :dataset/hash-length     64})

(def ^:private name-pattern
  (let [{:dataset/keys [name-min-length name-max-length]} default-config]
    (re-pattern (str "^[a-z0-9_-]{" name-min-length "," name-max-length "}$"))))

(def ^:private sha256-pattern
  (re-pattern (str "^[a-f0-9]{" (:dataset/hash-length default-config) "}$")))

(defn validate-dataset
  "Validate a dataset map against N1 §3."
  [{:dataset/keys [id name type schema storage-location version lineage content-hash] :as dataset}]
  (let [errors
        (cond-> []
          (nil? id)
          (conj (msg/t :dataset/id-required))

          (not (string? name))
          (conj (msg/t :dataset/name-must-be-string))

          (and (string? name) (not (re-matches name-pattern name)))
          (conj (msg/t :dataset/name-pattern-mismatch {:pattern name-pattern}))

          (not (contains? ds-schema/dataset-types type))
          (conj (msg/t :dataset/type-invalid {:allowed ds-schema/dataset-types}))

          (nil? schema)
          (conj (msg/t :dataset/schema-required))

          (nil? storage-location)
          (conj (msg/t :dataset/storage-location-required))

          (nil? version)
          (conj (msg/t :dataset/version-required))

          (nil? lineage)
          (conj (msg/t :dataset/lineage-required))

          (and content-hash (not (re-matches sha256-pattern content-hash)))
          (conj (msg/t :dataset/content-hash-invalid)))]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-dataset
  "Create a new dataset with defaults."
  [opts]
  (let [now (Instant/now)
        dataset (cond-> opts
                  (nil? (:dataset/id opts))
                  (assoc :dataset/id (UUID/randomUUID))

                  (nil? (:dataset/created-at opts))
                  (assoc :dataset/created-at now)

                  (nil? (:dataset/lineage opts))
                  (assoc :dataset/lineage {:lineage/source-datasets []
                                           :lineage/transformations []})

                  (nil? (:dataset/content-hash opts))
                  (assoc :dataset/content-hash
                         (apply str (repeat (:dataset/hash-length default-config) "0"))))
        validation (validate-dataset dataset)]
    (if (:success? validation)
      {:success? true :dataset dataset}
      {:success? false :errors (:errors validation)})))

(defn get-dataset
  "Retrieve dataset from store by ID."
  [store dataset-id]
  ;; In MVP, we use the artifact store's load-artifact
  ;; and convert back via bridge
  (try
    (when-let [artifact (artifact/load-artifact store dataset-id)]
      (bridge/artifact->dataset-ref artifact))
    (catch Exception _e nil)))

(defn save-dataset!
  "Save dataset to artifact store."
  [store dataset]
  (try
    (let [artifact (bridge/dataset->artifact dataset)]
      (artifact/save! store artifact)
      {:success? true})
    (catch Exception e
      {:success? false :error (.getMessage e)})))

(defn query-datasets
  "Query datasets from store."
  [store {:dataset/keys [type name] :as criteria}]
  (try
    (let [artifacts (if type
                      (artifact/query-by-type store type)
                      (artifact/query store criteria))]
      (mapv bridge/artifact->dataset-ref artifacts))
    (catch Exception _e [])))
