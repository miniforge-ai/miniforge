(ns ai.miniforge.data-foundry.connector.cursor
  (:require [ai.miniforge.data-foundry.connector.messages :as msg])
  (:import [java.time Instant]))

(def cursor-types
  "N2 §2.2.1 cursor types"
  #{:timestamp-watermark :offset :sequence-id :version-id})

(defn validate-cursor
  "Validate cursor structure."
  [{:cursor/keys [type] :as cursor}]
  (let [errors
        (cond-> []
          (nil? type)
          (conj (msg/t :cursor/type-required))

          (and type (not (contains? cursor-types type)))
          (conj (msg/t :cursor/type-invalid {:allowed cursor-types})))]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-cursor
  "Create a cursor of the given type."
  [cursor-type initial-value]
  {:cursor/type cursor-type
   :cursor/value initial-value
   :cursor/last-retrieved-at (Instant/now)})

(defn advance-cursor
  "Advance cursor to new position."
  [cursor new-value]
  (assoc cursor
         :cursor/value new-value
         :cursor/last-retrieved-at (Instant/now)))
