(ns ai.miniforge.data-foundry.dataset-schema.evolution
  (:require [ai.miniforge.data-foundry.dataset-schema.field-types :as ft]
            [clojure.set]))

(defn- field-map
  "Convert field vector to map keyed by :field/name."
  [fields]
  (into {} (map (juxt :field/name identity)) fields))

(defn diff-schemas
  "Compute structural diff between two schemas.
   Returns {:added [field-names] :removed [field-names] :changed [{:field field-name :changes [...]}]}"
  [old-schema new-schema]
  (let [old-fields (field-map (:schema/fields old-schema))
        new-fields (field-map (:schema/fields new-schema))
        old-names  (set (keys old-fields))
        new-names  (set (keys new-fields))
        added      (vec (sort (clojure.set/difference new-names old-names)))
        removed    (vec (sort (clojure.set/difference old-names new-names)))
        common     (clojure.set/intersection old-names new-names)
        changed    (vec
                    (for [n (sort common)
                          :let [old-f (get old-fields n)
                                new-f (get new-fields n)]
                          :when (not= old-f new-f)]
                      {:field n
                       :old old-f
                       :new new-f}))]
    {:added added :removed removed :changed changed}))

(defn- classify-field-change
  "Classify a single field change. Returns :backward-compatible, :requires-migration, or :breaking."
  [{:keys [old new]}]
  (let [old-type (:field/type old)
        new-type (:field/type new)
        old-nullable (:field/nullable? old)
        new-nullable (:field/nullable? new)
        old-enum (:field/enum old)
        new-enum (:field/enum new)]
    (cond
      ;; Type changed
      (and (not= old-type new-type)
           (ft/type-widening-compatible? old-type new-type))
      :backward-compatible

      (and (not= old-type new-type)
           (not (ft/type-widening-compatible? old-type new-type))
           (ft/type-widening-compatible? new-type old-type))
      :requires-migration

      (and (not= old-type new-type)
           (not (ft/type-widening-compatible? old-type new-type))
           (not (ft/type-widening-compatible? new-type old-type)))
      :breaking

      ;; Relaxing nullability: backward compatible
      (and (not old-nullable) new-nullable)
      :backward-compatible

      ;; Tightening nullability: requires migration
      (and old-nullable (not new-nullable))
      :requires-migration

      ;; Expanding enum: backward compatible
      (and old-enum new-enum
           (every? (set new-enum) old-enum)
           (> (count new-enum) (count old-enum)))
      :backward-compatible

      ;; Reducing enum: requires migration
      (and old-enum new-enum
           (not (every? (set new-enum) old-enum)))
      :requires-migration

      ;; Precision/scale changes: requires migration
      (or (not= (:field/precision old) (:field/precision new))
          (not= (:field/scale old) (:field/scale new)))
      :requires-migration

      ;; Other changes
      :else :backward-compatible)))

(defn- classify-added-field
  "Classify an added field."
  [new-schema field-name]
  (let [field (first (filter #(= (:field/name %) field-name)
                             (:schema/fields new-schema)))]
    (if (:field/nullable? field)
      :backward-compatible
      :requires-migration)))

(def ^:private severity-order
  {:backward-compatible 0
   :requires-migration 1
   :breaking 2})

(defn classify-evolution
  "Classify the overall evolution between two schema versions.
   Returns the most severe classification among all changes:
   :backward-compatible, :requires-migration, or :breaking"
  [old-schema new-schema]
  (let [{:keys [added removed changed]} (diff-schemas old-schema new-schema)]
    (if (and (empty? added) (empty? removed) (empty? changed))
      :backward-compatible
      (let [classifications
            (concat
             ;; Removed fields require migration
             (when (seq removed) [:requires-migration])
             ;; Added fields
             (map #(classify-added-field new-schema %) added)
             ;; Changed fields
             (map classify-field-change changed))]
        (reduce
         (fn [worst c]
           (if (> (severity-order c) (severity-order worst)) c worst))
         :backward-compatible
         classifications)))))
