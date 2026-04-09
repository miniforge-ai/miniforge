(ns ai.miniforge.data-foundry.dataset-schema.interface
  (:require [ai.miniforge.data-foundry.dataset-schema.core :as core]
            [ai.miniforge.data-foundry.dataset-schema.evolution :as evolution]
            [ai.miniforge.data-foundry.dataset-schema.field-types :as field-types]))

;; -- Dataset Types --
(def dataset-types core/dataset-types)

;; -- Field Types --
(def field-types field-types/field-types)
(def valid-field-type? field-types/valid-field-type?)
(def type-widening-compatible? field-types/type-widening-compatible?)

;; -- Schema CRUD --
(defn create-schema
  "Create a new schema definition. Returns {:success? true :schema ...} or {:success? false :error ...}"
  [opts]
  (core/create-schema opts))

(defn validate-schema
  "Validate a schema definition against N1 §4. Returns {:success? true} or {:success? false :errors [...]}"
  [schema]
  (core/validate-schema schema))

(defn validate-field
  "Validate a single field definition. Returns {:success? true} or {:success? false :errors [...]}"
  [field]
  (core/validate-field field))

;; -- Schema Evolution --
(defn classify-evolution
  "Classify changes between two schema versions. Returns one of: :backward-compatible, :requires-migration, :breaking"
  [old-schema new-schema]
  (evolution/classify-evolution old-schema new-schema))

(defn diff-schemas
  "Return detailed diff between two schemas. Returns {:added [...] :removed [...] :changed [...]}"
  [old-schema new-schema]
  (evolution/diff-schemas old-schema new-schema))
