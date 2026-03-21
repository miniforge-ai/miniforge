(ns ai.miniforge.web-dashboard.filter-facets
  "Facet computation for filter options."
  (:require
   [ai.miniforge.web-dashboard.filter-specs :as specs]))

;------------------------------------------------------------------------------ Layer 0
;; Value extraction (duplicated from filter_eval for now, could be shared)

(defn extract-value
  "Extract value from item using filter spec."
  [item {:keys [filter/value]}]
  (case (:kind value)
    :path
    (get-in item (:path value))

    :derived
    (if-let [extractor (get specs/derived-fields (:derived/id value))]
      (extractor item)
      nil)

    :multi-path
    (keep #(get-in item %) (:paths value))

    nil))

;------------------------------------------------------------------------------ Layer 1
;; Facet computation

(defn compute-facets
  "Compute faceted counts for filter options.

   Arguments:
   - items: Collection of items
   - filter-id: Filter to compute facets for
   - pane: Current pane

   Returns: Map of value -> count"
  [items filter-id pane]
  (let [spec (specs/get-filter-spec-by-id filter-id)]
    (when (and spec (contains? (:filter/applicable-to spec) pane))
      (->> items
           (map #(extract-value % spec))
           (filter some?)
           frequencies
           (sort-by val >)))))

(defn compute-all-facets
  "Compute facets for all applicable filters in a pane.

   Arguments:
   - items: Collection of items
   - pane: Current pane keyword

   Returns: Map of filter-id -> {value count}"
  [items pane]
  (let [applicable-specs (filter #(contains? (:filter/applicable-to %) pane)
                                (specs/get-filter-specs))]
    (into {}
          (map (fn [spec]
                 [(:filter/id spec)
                  (compute-facets items (:filter/id spec) pane)])
               applicable-specs))))
