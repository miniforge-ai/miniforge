(ns ai.miniforge.dataset.artifact-bridge)

(defn dataset->artifact
  "Convert a dataset to a Core artifact map per N1 §9.1.
   Maps :dataset/id -> :artifact/id, :dataset/type -> :artifact/type, etc."
  [{:dataset/keys [id type content-hash created-at metadata] :as dataset}]
  {:artifact/id id
   :artifact/type (or type :dataset)
   :artifact/content-hash content-hash
   :artifact/created-at created-at
   :artifact/metadata (merge metadata
                             {:dataset/name (:dataset/name dataset)
                              :dataset/schema (:dataset/schema dataset)
                              :dataset/storage-location (:dataset/storage-location dataset)
                              :dataset/version (:dataset/version dataset)
                              :dataset/lineage (:dataset/lineage dataset)
                              :dataset/partitioning (:dataset/partitioning dataset)})})

(defn artifact->dataset-ref
  "Extract dataset reference info from a Core artifact."
  [{:artifact/keys [id type content-hash created-at metadata]}]
  (merge
   {:dataset/id id
    :dataset/type type
    :dataset/content-hash content-hash
    :dataset/created-at created-at}
   (select-keys metadata [:dataset/name :dataset/schema :dataset/storage-location
                           :dataset/version :dataset/lineage :dataset/partitioning
                           :dataset/metadata])))
