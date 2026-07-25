(ns ai.miniforge.metric-registry.lookup
  "Pure query functions over metric registry data.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} all-metrics
  [registry]
  (mapcat :family/metrics (:registry/families registry)))

(defn ^{:stratum 0} find-metrics-by-family
  [registry family-id]
  (some (fn [f] (when (= family-id (:family/id f)) (:family/metrics f)))
        (:registry/families registry)))

(defn ^{:stratum 0} find-metrics-by-pipeline
  [registry pipeline-name]
  (get-in registry [:registry/pipeline-metric-map pipeline-name]))

(defn ^{:stratum 0} family-ids
  [registry]
  (mapv :family/id (:registry/families registry)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} find-metric
  [registry metric-id]
  (some (fn [m] (when (= metric-id (:metric/id m)) m))
        (all-metrics registry)))

(defn ^{:stratum 1} find-metrics-by-source-type
  [registry source-type]
  (filter #(= source-type (:metric/source-type %))
          (all-metrics registry)))

(defn ^{:stratum 1} metric-count
  [registry]
  (count (all-metrics registry)))
