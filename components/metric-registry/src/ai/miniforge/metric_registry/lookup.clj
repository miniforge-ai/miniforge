(ns ai.miniforge.metric-registry.lookup
  "Pure query functions over metric registry data.")

(defn all-metrics
  [registry]
  (mapcat :family/metrics (:registry/families registry)))

(defn find-metric
  [registry metric-id]
  (some (fn [m] (when (= metric-id (:metric/id m)) m))
        (all-metrics registry)))

(defn find-metrics-by-family
  [registry family-id]
  (some (fn [f] (when (= family-id (:family/id f)) (:family/metrics f)))
        (:registry/families registry)))

(defn find-metrics-by-source-type
  [registry source-type]
  (filter #(= source-type (:metric/source-type %))
          (all-metrics registry)))

(defn find-metrics-by-pipeline
  [registry pipeline-name]
  (get-in registry [:registry/pipeline-metric-map pipeline-name]))

(defn family-ids
  [registry]
  (mapv :family/id (:registry/families registry)))

(defn metric-count
  [registry]
  (count (all-metrics registry)))
