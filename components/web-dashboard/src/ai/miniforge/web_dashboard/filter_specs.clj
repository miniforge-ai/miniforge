(ns ai.miniforge.web-dashboard.filter-specs
  "Filter specification loading and management."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.web-dashboard.filter-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Derived field registry

(def derived-fields
  "Registry of derived field extractors.
   Maps derived-id -> extraction function."
  {:derived/has-testing
   (fn [item] (boolean (some #(= :test (:phase/id %))
                             (get-in item [:workflow/phases] []))))

   :derived/has-review
   (fn [item] (boolean (some #(= :verify (:phase/id %))
                             (get-in item [:workflow/phases] []))))

   :derived/phase-count
   (fn [item] (count (get-in item [:workflow/phases] [])))

   :derived/has-evidence
   (fn [item] (boolean (:train/evidence-bundle-id item)))

   :derived/is-completed
   (fn [item] (= :completed (get-in item [:execution/status])))

   :derived/has-dependencies
   (fn [item] (seq (:dependencies item)))})

;------------------------------------------------------------------------------ Layer 1
;; Spec loading

(defn load-filter-specs
  "Load filter specifications from EDN file.

   Arguments:
   - resource-path: Path to EDN file in resources

   Returns: Vector of filter specs"
  [resource-path]
  (try
    (when-let [resource (io/resource resource-path)]
      (let [specs (edn/read-string (slurp resource))]
        (if (schema/valid-filter-specs? specs)
          specs
          (do
            (println "Invalid filter specs:" (schema/explain-filter-spec specs))
            []))))
    (catch Exception e
      (println "Error loading filter specs:" (.getMessage e))
      [])))

;; Atom holding loaded filter specifications
(defonce filter-specs-atom
  (atom (load-filter-specs "filters/default-filters.edn")))

(defn get-filter-specs
  "Get currently loaded filter specifications."
  []
  @filter-specs-atom)

(defn reload-filter-specs!
  "Reload filter specifications from file."
  []
  (reset! filter-specs-atom (load-filter-specs "filters/default-filters.edn")))

(defn add-custom-filter-spec!
  "Add a custom filter specification at runtime.

   Arguments:
   - spec: Filter specification map

   Returns: true if added, false if invalid"
  [spec]
  (if (schema/valid-filter-spec? spec)
    (do
      (swap! filter-specs-atom conj spec)
      true)
    (do
      (println "Invalid filter spec:" (schema/explain-filter-spec spec))
      false)))

;------------------------------------------------------------------------------ Layer 2
;; Spec queries

(defn get-applicable-filters
  "Get all filters applicable to a pane, grouped by scope.

   For global filters: Returns ALL global filters (they apply everywhere).
   For local filters: Returns only filters applicable to the specific pane.

   Returns:
   {:global [...all global filter specs...]
    :local  [...local filter specs for this pane...]}"
  [pane]
  (let [specs (get-filter-specs)]
    {:global (filter #(= :global (:filter/scope %)) specs)
     :local (filter #(and (= :local (:filter/scope %))
                         (contains? (:filter/applicable-to %) pane))
                   specs)}))

(defn get-filter-spec-by-id
  "Get a filter spec by its ID."
  [filter-id]
  (first (filter #(= filter-id (:filter/id %)) (get-filter-specs))))
