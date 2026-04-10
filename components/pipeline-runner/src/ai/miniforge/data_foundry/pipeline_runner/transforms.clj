(ns ai.miniforge.data-foundry.pipeline-runner.transforms
  "Built-in transform functions for pipeline stages.
   Each transform fn takes [input-records stage-config] and returns output records.")

(defn- parse-numeric-value
  "Parse a record's :value to a double, skipping FRED dot-values."
  [record]
  (let [v (:value record)]
    (cond
      (number? v) v
      (= v ".")   nil
      (string? v) (parse-double v)
      :else       nil)))

(defn- series-values-by-date
  "Build a date→numeric-value lookup map for a given series-id in the records."
  [records series-id]
  (into {}
        (for [r records
              :when (= (str series-id) (str (:series_id r)))
              :let [v (parse-numeric-value r)]
              :when v]
          [(:date r) v])))

(defn- compute-ratios
  "Compute ratio records by joining numerator and denominator date maps."
  [num-map denom-map num-scale out-scale output-series]
  (vec (for [[date num-val] num-map
             :let [denom-val (get denom-map date)]
             :when (and denom-val (not (zero? denom-val)))]
         {:date      date
          :series_id (or output-series "DERIVED_RATIO")
          :value     (format "%.2f" (* (/ (* num-val num-scale)
                                          denom-val)
                                       out-scale))})))

(defn derived-ratio
  "Compute a derived ratio from two series in the input records.
   Config keys (under :stage/transform):
     :transform/numerator-series   — series_id of the numerator
     :transform/denominator-series — series_id of the denominator
     :transform/numerator-scale    — multiply numerator by this (default 1.0)
     :transform/output-scale       — multiply ratio by this (default 1.0)
     :transform/output-series      — series_id for the output records
   Joins numerator and denominator by :date, emits one record per matched date.
   Passes through all input records unchanged plus the derived records."
  [input-records stage-config]
  (let [{:transform/keys [numerator-series denominator-series
                           numerator-scale output-scale output-series]}
        (:stage/transform stage-config)

        num-scale (or numerator-scale 1.0)
        out-scale (or output-scale 1.0)
        num-map   (series-values-by-date input-records numerator-series)
        denom-map (series-values-by-date input-records denominator-series)
        derived   (compute-ratios num-map denom-map num-scale out-scale output-series)]
    (into (vec input-records) derived)))
