(ns ai.miniforge.dataset.version
  (:require [ai.miniforge.dataset.messages :as msg])
  (:import [java.util UUID]
           [java.time Instant]))

(def ^:private sha256-pattern #"^[a-f0-9]{64}$")

(defn validate-version
  "Validate a dataset version against N1 §6."
  [{:dataset-version/keys [id dataset-id timestamp workflow-run-id
                            content-hash row-count schema-version]}]
  (let [errors
        (cond-> []
          (nil? id)
          (conj (msg/t :version/id-required))

          (nil? dataset-id)
          (conj (msg/t :version/dataset-id-required))

          (nil? timestamp)
          (conj (msg/t :version/timestamp-required))

          (nil? workflow-run-id)
          (conj (msg/t :version/workflow-run-id-required))

          (and content-hash (not (re-matches sha256-pattern content-hash)))
          (conj (msg/t :version/content-hash-invalid))

          (nil? content-hash)
          (conj (msg/t :version/content-hash-required))

          (nil? row-count)
          (conj (msg/t :version/row-count-required))

          (and row-count (neg? row-count))
          (conj (msg/t :version/row-count-non-negative))

          (nil? schema-version)
          (conj (msg/t :version/schema-version-required)))]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-version
  "Create a new immutable dataset version."
  [opts]
  (let [version (cond-> opts
                  (nil? (:dataset-version/id opts))
                  (assoc :dataset-version/id (UUID/randomUUID))

                  (nil? (:dataset-version/timestamp opts))
                  (assoc :dataset-version/timestamp (Instant/now)))
        validation (validate-version version)]
    (if (:success? validation)
      {:success? true :version version}
      {:success? false :errors (:errors validation)})))
