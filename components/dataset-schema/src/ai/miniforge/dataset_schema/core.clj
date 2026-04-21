(ns ai.miniforge.dataset-schema.core
  (:require [ai.miniforge.dataset-schema.field-types :as ft]
            [ai.miniforge.dataset-schema.messages :as msg])
  (:import [java.util UUID]
           [java.time Instant]))

(def dataset-types
  "Enumerated dataset types per N1 §2.2"
  #{:table :time-series :document-collection :graph :feature-set :report})

(def default-config
  "Default schema validation configuration."
  {:schema/name-min-length 3
   :schema/name-max-length 128})

(def ^:private name-pattern
  (let [{:schema/keys [name-min-length name-max-length]} default-config]
    (re-pattern (str "^[a-z0-9_-]{" name-min-length "," name-max-length "}$"))))

(defn- validate-field-impl
  "Validate a single field definition. Returns vector of error strings."
  [{:field/keys [name type nullable?] :as field}]
  (cond-> []
    (not (string? name))
    (conj (msg/t :field/name-must-be-string))

    (and (string? name) (empty? name))
    (conj (msg/t :field/name-must-not-be-empty))

    (not (ft/valid-field-type? type))
    (conj (msg/t :field/type-invalid {:type type :allowed ft/field-types}))

    (not (boolean? nullable?))
    (conj (msg/t :field/nullable-must-be-bool))))

(defn validate-field
  "Validate a single field definition."
  [field]
  (let [errors (validate-field-impl field)]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn- validate-schema-impl
  "Returns vector of error strings for a schema."
  [{:schema/keys [id name version fields] :as schema}]
  (let [base-errors
        (cond-> []
          (nil? id)
          (conj (msg/t :schema/id-required))

          (not (string? name))
          (conj (msg/t :schema/name-must-be-string))

          (and (string? name) (not (re-matches name-pattern name)))
          (conj (msg/t :schema/name-pattern-mismatch {:pattern name-pattern}))

          (not (string? version))
          (conj (msg/t :schema/version-must-be-string))

          (not (vector? fields))
          (conj (msg/t :schema/fields-must-be-vector))

          (and (vector? fields) (empty? fields))
          (conj (msg/t :schema/fields-must-not-be-empty)))

        field-errors
        (when (and (vector? fields) (seq fields))
          (mapcat
           (fn [f]
             (map #(msg/t :schema/field-error {:name (:field/name f) :error %})
                  (validate-field-impl f)))
           fields))

        dup-errors
        (when (and (vector? fields) (seq fields))
          (let [names (map :field/name fields)
                dupes (for [[n c] (frequencies names) :when (> c 1)] n)]
            (map #(msg/t :schema/duplicate-field {:name %}) dupes)))]

    (vec (concat base-errors field-errors dup-errors))))

(defn validate-schema
  "Validate a schema definition against N1 §4."
  [schema]
  (let [errors (validate-schema-impl schema)]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-schema
  "Create a new schema definition with defaults.
   Required keys: :schema/name, :schema/version, :schema/fields
   Optional: :schema/id (auto-generated), :schema/constraints, :schema/parent-version"
  [opts]
  (let [schema (cond-> opts
                 (nil? (:schema/id opts))
                 (assoc :schema/id (UUID/randomUUID))

                 (nil? (:schema/created-at opts))
                 (assoc :schema/created-at (Instant/now)))
        validation (validate-schema schema)]
    (if (:success? validation)
      {:success? true :schema schema}
      {:success? false :errors (:errors validation)})))
