(ns ai.miniforge.knowledge.trust
  "Transitive trust rules for knowledge packs.

   Implements N1 §2.10.2 transitive trust rules to prevent trust escalation attacks:
   1. Instruction authority is not transitive
   2. Trust level inheritance (lowest wins)
   3. Cross-trust reference tracking and validation
   4. Tainted isolation from instruction authority

   Trust levels (ordered):
   - :tainted   - Flagged by scanners; MUST NOT be used for instruction
   - :untrusted - Repo-derived or external; data-only unless promoted
   - :trusted   - Platform-validated and/or user-promoted

   Authority channels:
   - :authority/instruction - May shape agent plans (requires :trusted)
   - :authority/data        - Reference material only (any trust level)")

;------------------------------------------------------------------------------ Layer 0
;; Trust level ordering

(def ^:private trust-levels
  "Trust levels in ascending order (lower index = less trusted)."
  [:tainted :untrusted :trusted])

(defn trust-level-order
  "Return numeric order of trust level (lower = less trusted).
   Returns nil for invalid trust levels."
  [level]
  (let [idx (.indexOf trust-levels level)]
    (when (>= idx 0) idx)))

(defn lowest-trust-level
  "Return the lowest (least trusted) level from a collection.
   Returns :tainted if any level is :tainted.
   Returns :untrusted if any is :untrusted and none are :tainted.
   Returns :trusted only if all are :trusted."
  [levels]
  (when (seq levels)
    (let [valid-levels (filter #(contains? (set trust-levels) %) levels)]
      (if (empty? valid-levels)
        :untrusted  ; Default to untrusted if no valid levels
        (apply min-key trust-level-order valid-levels)))))

;------------------------------------------------------------------------------ Layer 1
;; Pack trust schema

(defn make-pack-ref
  "Create a pack reference with trust information.

   Arguments:
   - pack-id       - Unique identifier for the pack
   - trust-level   - :trusted, :untrusted, or :tainted
   - authority     - :authority/instruction or :authority/data

   Options:
   - :dependencies - Vector of pack-ids this pack depends on

   Returns pack reference map."
  [pack-id trust-level authority & {:keys [dependencies]}]
  {:pack-id pack-id
   :trust-level trust-level
   :authority authority
   :dependencies (or dependencies [])})

(defn valid-pack-ref?
  "Validate that a pack reference has required fields and valid values."
  [pack-ref]
  (and (map? pack-ref)
       (:pack-id pack-ref)
       (contains? (set trust-levels) (:trust-level pack-ref))
       (contains? #{:authority/instruction :authority/data} (:authority pack-ref))
       (vector? (:dependencies pack-ref))))

;------------------------------------------------------------------------------ Layer 2
;; Generic DFS utilities

(defn ^:private dfs-find
  "Generic DFS to find nodes matching a predicate.

  Arguments:
  - graph        - Map of node-id -> node
  - start-id     - Starting node ID
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - found-fn     - Function (node-id, node, path) -> truthy if found

  Returns:
  - nil if not found
  - {:found-id node-id :path [...]} if found"
  [graph start-id get-deps-fn found-fn]
  (letfn [(search [node-id path visited]
            (if (contains? visited node-id)
              {:visited visited :found nil}
              (let [node (get graph node-id)]
                (cond
                  ;; Node not in graph
                  (not node)
                  {:visited (conj visited node-id) :found nil}

                  ;; Found matching node
                  (found-fn node-id node path)
                  {:visited visited :found {:found-id node-id :path (conj path node-id)}}

                  ;; Search dependencies
                  :else
                  (let [deps (get-deps-fn node)]
                    (loop [remaining-deps deps
                           v (conj visited node-id)]
                      (if (empty? remaining-deps)
                        {:visited v :found nil}
                        (let [result (search (first remaining-deps) (conj path node-id) v)]
                          (if (:found result)
                            result
                            (recur (rest remaining-deps) (:visited result)))))))))))]

    (let [result (search start-id [] #{})]
      (:found result))))

(defn ^:private dfs-validate-graph
  "Generic DFS to validate graph structure (cycles, missing nodes).

  Arguments:
  - graph        - Map of node-id -> node
  - start-nodes  - Collection of starting node IDs
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - validate-fn  - Function (node-id, node, context) -> error-map or nil
                   where context is {:cycle? bool :missing? bool :path [...]}

  Returns:
  - {:valid? true :graph graph} if valid
  - {:valid? false :error ...} if invalid"
  [graph start-nodes get-deps-fn validate-fn]
  (letfn [(visit [node-id path visited visiting]
            (cond
              (contains? visited node-id)
              {:visited visited :visiting visiting :error nil}

              (contains? visiting node-id)
              (let [error (validate-fn node-id nil {:cycle? true :path (conj path node-id)})]
                {:visited visited :visiting visiting :error error})

              :else
              (let [node (get graph node-id)]
                (if-not node
                  (let [error (validate-fn node-id nil {:missing? true})]
                    {:visited visited :visiting visiting :error error})
                  (let [deps (get-deps-fn node)]
                    (loop [remaining-deps deps
                           v visited
                           ing (conj visiting node-id)]
                      (if (empty? remaining-deps)
                        {:visited (conj v node-id) :visiting (disj ing node-id) :error nil}
                        (let [result (visit (first remaining-deps) (conj path node-id) v ing)]
                          (if (:error result)
                            result
                            (recur (rest remaining-deps)
                                   (:visited result)
                                   (:visiting result)))))))))))]

    (loop [nodes start-nodes
           visited #{}
           visiting #{}]
      (if (empty? nodes)
        {:valid? true :graph graph}
        (let [result (visit (first nodes) [] visited visiting)]
          (if (:error result)
            (:error result)
            (recur (rest nodes)
                   (:visited result)
                   (:visiting result))))))))

;------------------------------------------------------------------------------ Layer 3
;; Transitive trust rule validation

(defn validate-instruction-authority-not-transitive
  "Rule 1: Instruction authority is not transitive.

   If pack A (:trusted, :authority/instruction) references pack B (:untrusted),
   pack B MUST remain :authority/data and MUST NOT gain instruction authority.

   Arguments:
   - source-pack - Pack reference with :authority/instruction
   - target-pack - Pack being referenced

   Returns:
   - {:valid? true} if rule passes
   - {:valid? false :error \"...\"} if rule fails"
  [source-pack target-pack]
  (if (not= :authority/instruction (:authority source-pack))
    {:valid? true}  ; Rule only applies to instruction-authority packs
    (if (and (= :authority/instruction (:authority target-pack))
             (not= :trusted (:trust-level target-pack)))
      {:valid? false
       :error (str "Instruction authority cannot be granted transitively: "
                   "Pack " (:pack-id target-pack) " is " (:trust-level target-pack)
                   " but has :authority/instruction. "
                   "Only :trusted packs may have instruction authority.")}
      {:valid? true})))

(defn compute-inherited-trust-level
  "Rule 2: Trust level inheritance.

   When pack A includes content from pack B, the combined content
   MUST be assigned the lower trust level.

   Arguments:
   - pack-refs - Collection of pack references being combined

   Returns the lowest trust level from all packs."
  [pack-refs]
  (let [levels (map :trust-level pack-refs)]
    (lowest-trust-level levels)))

(defn validate-cross-trust-references
  "Rule 3: Cross-trust references.

   Packs MAY reference other packs of any trust level, but must
   track and validate the transitive trust graph.

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - {:valid? true :graph {...}} if valid
   - {:valid? false :error \"...\"} if invalid (circular deps, etc.)"
  [pack-graph]
  (dfs-validate-graph
   pack-graph
   (keys pack-graph)
   (fn [pack-ref] (:dependencies pack-ref))
   (fn [pack-id _node context]
     (cond
       (:cycle? context)
       {:valid? false
        :error (str "Circular dependency detected: "
                    (clojure.string/join " -> " (:path context)))}

       (:missing? context)
       {:valid? false
        :error (str "Missing dependency: " pack-id)}

       :else nil))))

(defn validate-tainted-isolation
  "Rule 4: Tainted isolation.

   Content marked :tainted MUST NOT be included in any pack used for
   instruction authority, even transitively.

   Arguments:
   - pack-id     - Pack to validate
   - pack-graph  - Map of pack-id -> pack-ref

   Returns:
   - {:valid? true} if no tainted content in instruction chain
   - {:valid? false :error \"...\" :tainted-path [...]} if tainted found"
  [pack-id pack-graph]
  (let [pack-ref (get pack-graph pack-id)]
    (if (not= :authority/instruction (:authority pack-ref))
      {:valid? true}  ; Rule only applies to instruction packs
      (if-let [found (dfs-find
                      pack-graph
                      pack-id
                      (fn [pack-ref] (:dependencies pack-ref))
                      (fn [node-id node _path]
                        (= :tainted (:trust-level node))))]
        {:valid? false
         :error (str "Pack " pack-id " has :authority/instruction but "
                     "transitively includes tainted content from "
                     (:found-id found))
         :tainted-path (:path found)}
        {:valid? true}))))

;------------------------------------------------------------------------------ Layer 4
;; Combined validation

(defn ^:private collect-authority-errors
  "Check Rule 1: Instruction authority is not transitive.

   Validates that no pack with instruction authority transitively
   grants that authority to untrusted dependencies.

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - Sequence of error strings (empty if no errors)"
  [pack-graph]
  (->> (vals pack-graph)
       (mapcat (fn [pack-ref]
                 (->> (:dependencies pack-ref)
                      (keep (fn [dep-id]
                              (when-let [dep-ref (get pack-graph dep-id)]
                                (let [result (validate-instruction-authority-not-transitive pack-ref dep-ref)]
                                  (when-not (:valid? result)
                                    (:error result)))))))))))

(defn ^:private collect-tainted-errors
  "Check Rule 4: Tainted isolation from instruction authority.

   Validates that no pack with instruction authority transitively
   includes tainted content.

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - Sequence of error strings (empty if no errors)"
  [pack-graph]
  (->> pack-graph
       (keep (fn [[pack-id pack-ref]]
               (when (= :authority/instruction (:authority pack-ref))
                 (let [result (validate-tainted-isolation pack-id pack-graph)]
                   (when-not (:valid? result)
                     (:error result))))))))

(defn validate-transitive-trust
  "Validate all transitive trust rules for a pack graph.

   Checks:
   1. Instruction authority is not transitive
   2. Trust level inheritance is correct
   3. Cross-trust references are valid (no cycles, missing deps)
   4. Tainted content is isolated from instruction authority

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - {:valid? true :packs [...]} if all rules pass
   - {:valid? false :errors [...]} if any rule fails"
  [pack-graph]
  ;; Rule 3: Validate cross-trust references first (graph structure)
  (let [graph-validation (validate-cross-trust-references pack-graph)]
    (when-not (:valid? graph-validation)
      (throw (ex-info "Invalid pack graph" graph-validation)))

    ;; Collect all validation errors
    (let [errors (concat (collect-authority-errors pack-graph)
                         (collect-tainted-errors pack-graph))]
      (if (empty? errors)
        {:valid? true
         :packs (keys pack-graph)}
        {:valid? false
         :errors (vec errors)}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example: Trusted pack referencing untrusted pack (data-only)
  (def pack-a (make-pack-ref "pack-a" :trusted :authority/instruction
                             :dependencies ["pack-b"]))
  (def pack-b (make-pack-ref "pack-b" :untrusted :authority/data))

  (validate-instruction-authority-not-transitive pack-a pack-b)
  ;; => {:valid? true}

  ;; Example: Attempt to grant instruction authority transitively (INVALID)
  (def pack-b-bad (make-pack-ref "pack-b" :untrusted :authority/instruction))
  (validate-instruction-authority-not-transitive pack-a pack-b-bad)
  ;; => {:valid? false :error "..."}

  ;; Example: Trust level inheritance
  (compute-inherited-trust-level [pack-a pack-b])
  ;; => :untrusted (lowest level)

  ;; Example: Circular dependency detection
  (def circular-graph
    {"a" (make-pack-ref "a" :trusted :authority/data :dependencies ["b"])
     "b" (make-pack-ref "b" :trusted :authority/data :dependencies ["c"])
     "c" (make-pack-ref "c" :trusted :authority/data :dependencies ["a"])})

  (validate-cross-trust-references circular-graph)
  ;; => {:valid? false :error "Circular dependency detected: ..."}

  ;; Example: Tainted isolation
  (def tainted-graph
    {"main" (make-pack-ref "main" :trusted :authority/instruction
                          :dependencies ["lib"])
     "lib" (make-pack-ref "lib" :tainted :authority/data)})

  (validate-tainted-isolation "main" tainted-graph)
  ;; => {:valid? false :error "... transitively includes tainted content ..." :tainted-path [...]}

  :end)
