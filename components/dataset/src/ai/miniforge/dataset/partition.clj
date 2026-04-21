(ns ai.miniforge.dataset.partition
  (:require [ai.miniforge.dataset.messages :as msg]))

(def partition-strategies
  "N1 §7.2 supported partition strategies"
  #{:time :entity :composite :hash})

(def default-config
  "Default partition configuration."
  {:partition/retention-days-min 1
   :partition/retention-days-max 36500})

(defn validate-partition-strategy
  "Validate a partition strategy against N1 §7."
  [{:partition/keys [strategy keys retention-days]}]
  (let [{:partition/keys [retention-days-min retention-days-max]} default-config
        errors
        (cond-> []
          (not (contains? partition-strategies strategy))
          (conj (msg/t :partition/strategy-invalid {:allowed partition-strategies}))

          (not (vector? keys))
          (conj (msg/t :partition/keys-must-be-vector))

          (and (vector? keys) (empty? keys))
          (conj (msg/t :partition/keys-must-not-be-empty))

          (and retention-days
               (or (< retention-days retention-days-min)
                   (> retention-days retention-days-max)))
          (conj (msg/t :partition/retention-days-range
                       {:min retention-days-min :max retention-days-max})))]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-partition-strategy
  "Create a validated partition strategy."
  [opts]
  (let [validation (validate-partition-strategy opts)]
    (if (:success? validation)
      {:success? true :partition-strategy opts}
      {:success? false :errors (:errors validation)})))
