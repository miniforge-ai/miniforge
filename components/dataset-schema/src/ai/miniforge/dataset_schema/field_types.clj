(ns ai.miniforge.dataset-schema.field-types)

(def field-types
  "Enumerated field types per N1 §4.2"
  #{:string :int :long :decimal :double :boolean :date :instant :uuid :json :bytes})

(defn valid-field-type?
  "Returns true if type is a valid field type."
  [t]
  (contains? field-types t))

(def ^:private type-widening-graph
  "Map from narrow type to set of wider types it can promote to without data loss."
  {:int    #{:long :decimal :double}
   :long   #{:decimal :double}
   :double #{:decimal}})

(defn type-widening-compatible?
  "Returns true if widening from `from-type` to `to-type` is safe (backward compatible)."
  [from-type to-type]
  (contains? (get type-widening-graph from-type #{}) to-type))
