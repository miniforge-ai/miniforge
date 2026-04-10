(ns ai.miniforge.data-foundry.metric-registry.lookup
  "Pure query functions over metric registry data.")

(defn all-metrics
  "Return flat sequence of all metrics across all families."
  [registry]
  (mapcat :family/metrics (:registry/families registry)))

(defn find-metric
  "Find a single metric by id. Returns nil if not found."
  [registry metric-id]
  (some (fn [m] (when (= metric-id (:metric/id m)) m))
        (all-metrics registry)))

(defn find-metrics-by-family
  "Return all metrics in a given family."
  [registry family-id]
  (some (fn [f] (when (= family-id (:family/id f)) (:family/metrics f)))
        (:registry/families registry)))

(defn find-metrics-by-source-type
  "Return all metrics with a given acquisition class."
  [registry source-type]
  (filter #(= source-type (:metric/source-type %))
          (all-metrics registry)))

(defn find-metrics-by-pipeline
  "Return metric ids mapped to a given pipeline name."
  [registry pipeline-name]
  (get-in registry [:registry/pipeline-metric-map pipeline-name]))

(defn family-ids
  "Return all family ids in the registry."
  [registry]
  (mapv :family/id (:registry/families registry)))

(defn metric-count
  "Return total number of metrics across all families."
  [registry]
  (count (all-metrics registry)))
