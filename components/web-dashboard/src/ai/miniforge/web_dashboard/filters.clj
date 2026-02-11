(ns ai.miniforge.web-dashboard.filters
  "Filter specification and application for web dashboard.

   Implements hybrid filtering: global context filters + pane-local analysis filters.
   Filters are portable (no embedded functions) and support persistence."
  (:require
   [clojure.string :as str]))

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
   (fn [item] (= :completed (get-in item [:execution/status])))})

;------------------------------------------------------------------------------ Layer 1
;; Filter specifications

(def filter-specs
  "Portable filter specifications.

   Each spec defines:
   - :filter/id - Unique identifier
   - :filter/label - Display label
   - :filter/type - :enum | :bool | :text | :date-range | :number-range
   - :filter/scope - :global | :local
   - :filter/applicable-to - Set of panes where filter applies
   - :filter/value - {:kind :path :path [...]} or {:kind :derived :derived/id :...}
   - :filter/values - For enum types, available options"

  ;; Global context filters
  [{:filter/id :repository
    :filter/label "Repository"
    :filter/type :enum
    :filter/scope :global
    :filter/applicable-to #{:task-status :fleet :evidence :workflows}
    :filter/value {:kind :path :path [:repo]}
    :filter/values :dynamic} ; Computed from data

   {:filter/id :train-name
    :filter/label "Train"
    :filter/type :enum
    :filter/scope :global
    :filter/applicable-to #{:task-status :fleet :evidence}
    :filter/value {:kind :path :path [:train/name]}
    :filter/values :dynamic}

   {:filter/id :current-phase
    :filter/label "Current Phase"
    :filter/type :enum
    :filter/scope :global
    :filter/applicable-to #{:task-status :workflows}
    :filter/value {:kind :path :path [:execution/current-phase]}
    :filter/values [:plan :implement :test :verify :release]}

   {:filter/id :has-review
    :filter/label "Has Review"
    :filter/type :bool
    :filter/scope :global
    :filter/applicable-to #{:task-status :workflows}
    :filter/value {:kind :derived :derived/id :derived/has-review}}

   {:filter/id :has-testing
    :filter/label "Has Testing"
    :filter/type :bool
    :filter/scope :global
    :filter/applicable-to #{:task-status :workflows}
    :filter/value {:kind :derived :derived/id :derived/has-testing}}

   {:filter/id :text-search
    :filter/label "Search"
    :filter/type :text
    :filter/scope :global
    :filter/applicable-to #{:task-status :fleet :evidence :workflows}
    :filter/value {:kind :multi-path
                   :paths [[:train/name]
                           [:repo]
                           [:pr/title]
                           [:title]]}}

   ;; Pane-local filters: Task Status
   {:filter/id :task-status
    :filter/label "Status"
    :filter/type :enum
    :filter/scope :local
    :filter/applicable-to #{:task-status}
    :filter/value {:kind :path :path [:status]}
    :filter/values [:ready :running :done :blocked]}

   {:filter/id :has-dependencies
    :filter/label "Has Dependencies"
    :filter/type :bool
    :filter/scope :local
    :filter/applicable-to #{:task-status}
    :filter/value {:kind :derived
                   :derived/id (fn [item] (seq (:dependencies item)))}}

   ;; Pane-local filters: Workflows
   {:filter/id :workflow-complexity
    :filter/label "Complexity"
    :filter/type :enum
    :filter/scope :local
    :filter/applicable-to #{:workflows}
    :filter/value {:kind :path :path [:workflow/complexity]}
    :filter/values [:simple :medium :complex]}

   {:filter/id :phase-count
    :filter/label "Phase Count"
    :filter/type :number-range
    :filter/scope :local
    :filter/applicable-to #{:workflows}
    :filter/value {:kind :derived :derived/id :derived/phase-count}}

   ;; Pane-local filters: Evidence
   {:filter/id :has-evidence
    :filter/label "Has Evidence"
    :filter/type :bool
    :filter/scope :local
    :filter/applicable-to #{:evidence}
    :filter/value {:kind :derived :derived/id :derived/has-evidence}}

   {:filter/id :is-completed
    :filter/label "Completed"
    :filter/type :bool
    :filter/scope :local
    :filter/applicable-to #{:evidence}
    :filter/value {:kind :derived :derived/id :derived/is-completed}}])

;------------------------------------------------------------------------------ Layer 2
;; Value extraction

(defn- extract-value
  "Extract value from item using filter spec."
  [item {:keys [filter/value]}]
  (case (:kind value)
    :path
    (get-in item (:path value))

    :derived
    (if-let [extractor (get derived-fields (:derived/id value))]
      (extractor item)
      (when (fn? (:derived/id value))
        ((:derived/id value) item)))

    :multi-path
    (keep #(get-in item %) (:paths value))

    nil))

;------------------------------------------------------------------------------ Layer 3
;; Filter AST evaluation

(defn- eval-clause
  "Evaluate a single filter clause against an item."
  [item {:keys [filter/id op value]}]
  (let [spec (first (filter #(= id (:filter/id %)) filter-specs))
        item-value (extract-value item spec)]
    (case op
      := (= item-value value)
      :!= (not= item-value value)
      :in (contains? (set value) item-value)
      :contains (and (string? item-value)
                     (str/includes? (str/lower-case item-value)
                                   (str/lower-case value)))
      :< (and (number? item-value) (< item-value value))
      :> (and (number? item-value) (> item-value value))
      :<= (and (number? item-value) (<= item-value value))
      :>= (and (number? item-value) (>= item-value value))
      :between (and (number? item-value)
                    (>= item-value (:min value))
                    (<= item-value (:max value)))
      :text-search (some #(and (string? %)
                              (str/includes? (str/lower-case %)
                                           (str/lower-case value)))
                         item-value)
      true)))

(defn eval-filter-ast
  "Evaluate filter AST against an item.

   AST format:
   {:op :and | :or
    :clauses [...]}

   Returns true if item matches filter."
  [item ast]
  (case (:op ast)
    :and (every? #(eval-clause item %) (:clauses ast))
    :or (some #(eval-clause item %) (:clauses ast))
    :not (not (eval-filter-ast item (first (:clauses ast))))
    true))

;------------------------------------------------------------------------------ Layer 4
;; Filter application

(defn apply-filters
  "Apply filter AST to a collection of items.

   Arguments:
   - items: Collection to filter
   - ast: Filter AST
   - pane: Current pane keyword

   Returns: Filtered collection"
  [items ast pane]
  (if (or (nil? ast)
          (empty? (:clauses ast)))
    items
    (let [;; Split into global and local clauses
          clauses (:clauses ast)
          specs-by-id (into {} (map (juxt :filter/id identity) filter-specs))
          applicable-clauses (filter (fn [clause]
                                      (let [spec (get specs-by-id (:filter/id clause))]
                                        (contains? (:filter/applicable-to spec) pane)))
                                    clauses)
          ast' (assoc ast :clauses applicable-clauses)]
      (filter #(eval-filter-ast % ast') items))))

;------------------------------------------------------------------------------ Layer 5
;; Facet computation

(defn compute-facets
  "Compute faceted counts for filter options.

   Arguments:
   - items: Collection of items
   - filter-id: Filter to compute facets for
   - pane: Current pane

   Returns: Map of value -> count"
  [items filter-id pane]
  (let [spec (first (filter #(= filter-id (:filter/id %)) filter-specs))]
    (when (contains? (:filter/applicable-to spec) pane)
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
                                filter-specs)]
    (into {}
          (map (fn [spec]
                 [(:filter/id spec)
                  (compute-facets items (:filter/id spec) pane)])
               applicable-specs))))

;------------------------------------------------------------------------------ Layer 6
;; Filter state management

(defn get-applicable-filters
  "Get all filters applicable to a pane, grouped by scope.

   Returns:
   {:global [...global filter specs...]
    :local  [...local filter specs...]}"
  [pane]
  (let [applicable (filter #(contains? (:filter/applicable-to %) pane)
                          filter-specs)]
    {:global (filter #(= :global (:filter/scope %)) applicable)
     :local (filter #(= :local (:filter/scope %)) applicable)}))

(defn merge-filter-state
  "Merge global and pane-local filters into a single AST.

   Arguments:
   - global-filters: Global filter AST
   - pane-filters: Pane-local filter AST

   Returns: Combined AST"
  [global-filters pane-filters]
  (let [global-clauses (:clauses global-filters [])
        pane-clauses (:clauses pane-filters [])]
    {:op :and
     :clauses (concat global-clauses pane-clauses)}))
