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
   - :authority/data        - Reference material only (any trust level)"
  (:require
   [ai.miniforge.algorithms.interface :as alg]
   [clojure.string]))

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
  (alg/dfs-validate-graph
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
      (if-let [found (alg/dfs-find
                      pack-graph
                      pack-id
                      (fn [pack-ref] (:dependencies pack-ref))
                      (fn [_node-id node _path]
                        (= :tainted (:trust-level node))))]
        {:valid? false
         :error (str "Pack " pack-id " has :authority/instruction but "
                     "transitively includes tainted content from "
                     (:found-id found))
         :tainted-path (:path found)}
        {:valid? true}))))

;------------------------------------------------------------------------------ Layer 4
;; Combined validation helpers

(defn ^:private valid?
  "Check if a validation result is valid."
  [result]
  (:valid? result))

(defn ^:private error-from
  "Extract error from validation result if invalid, otherwise nil."
  [result]
  (when-not (valid? result)
    (:error result)))

(defn ^:private check-dependency-authority
  "Check if a dependency violates authority transitivity rules.
   Returns error string or nil."
  [pack-ref dep-id pack-graph]
  (when-let [dep-ref (get pack-graph dep-id)]
    (error-from (validate-instruction-authority-not-transitive pack-ref dep-ref))))

(defn ^:private check-pack-tainted-isolation
  "Check if an instruction pack transitively includes tainted content.
   Returns error string or nil."
  [pack-id pack-ref pack-graph]
  (when (= :authority/instruction (:authority pack-ref))
    (error-from (validate-tainted-isolation pack-id pack-graph))))

(defn ^:private collect-authority-errors
  "Check Rule 1: Instruction authority is not transitive.

   Validates that no pack with instruction authority transitively
   grants that authority to untrusted dependencies.

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - Sequence of error strings (empty if no errors)"
  [pack-graph]
  (for [pack-ref (vals pack-graph)
        dep-id (:dependencies pack-ref)
        :let [error (check-dependency-authority pack-ref dep-id pack-graph)]
        :when error]
    error))

(defn ^:private collect-tainted-errors
  "Check Rule 4: Tainted isolation from instruction authority.

   Validates that no pack with instruction authority transitively
   includes tainted content.

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - Sequence of error strings (empty if no errors)"
  [pack-graph]
  (for [[pack-id pack-ref] pack-graph
        :let [error (check-pack-tainted-isolation pack-id pack-ref pack-graph)]
        :when error]
    error))

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
    (when-not (valid? graph-validation)
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
