;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.algorithms.graph
  "Graph algorithms and utilities.

   Provides canonical implementations of common graph traversal algorithms
   used across miniforge components.

   Layer 0: Core DFS traversal
   Layer 1: DFS-based helper functions")

;------------------------------------------------------------------------------ Layer 0
;; Core DFS traversal

(defn dfs
  "Simple canonical depth-first search traversal.

  A general-purpose DFS implementation with pluggable behavior hooks.
  Used for dependency graphs, trust graphs, and other directed graphs.

  Arguments:
  - graph       - Map of node-id -> node
  - start-ids   - Collection of starting node IDs (or single ID)
  - get-deps-fn - Function (node) -> [dependency-node-ids]
  - on-visit-fn - Function (node-id node path visited visiting) -> result or nil
                  Called when visiting a node (not in visited yet)
                  Return non-nil to halt and return that value
  - on-cycle-fn - Function (node-id path visited visiting) -> result or nil
                  Called when cycle detected (node in visiting set)
                  Return non-nil to halt and return that value
  - on-missing-fn - Function (node-id visited visiting) -> result or nil
                    Called when node not found in graph
                    Return non-nil to halt and return that value

  Returns [final-visited final-result] where result is:
  - nil if traversal completed without halting
  - value returned by on-visit-fn/on-cycle-fn/on-missing-fn if halted

  Example:
    (dfs graph
         [\"start-node\"]
         (fn [node] (:deps node))
         (fn [id node path visited visiting] nil)  ; on-visit
         (fn [id path visited visiting]            ; on-cycle
           {:error \"Cycle detected\" :path path})
         (fn [id visited visiting] nil))           ; on-missing"
  [graph start-ids get-deps-fn on-visit-fn on-cycle-fn on-missing-fn]
  (let [start-ids (if (coll? start-ids) start-ids [start-ids])]
    (letfn [(traverse [node-id path visited visiting]
              (cond
                ;; Already visited - continue
                (contains? visited node-id)
                [visited nil]

                ;; Currently visiting - cycle detected
                (contains? visiting node-id)
                (let [result (on-cycle-fn node-id (conj path node-id) visited visiting)]
                  [visited result])

                ;; Visit this node
                :else
                (let [node (get graph node-id)]
                  (if-not node
                    ;; Node not in graph
                    (let [result (on-missing-fn node-id visited visiting)]
                      [visited result])
                    ;; Process node
                    (let [result (on-visit-fn node-id node path visited visiting)]
                      (if result
                        ;; Halt with result
                        [visited result]
                        ;; Continue with dependencies
                        (let [deps (get-deps-fn node)
                              visiting' (conj visiting node-id)]
                          (loop [remaining-deps deps
                                 v visited
                                 result nil]
                            (if (or result (empty? remaining-deps))
                              [(conj v node-id) result]
                              (let [[v' result'] (traverse (first remaining-deps)
                                                           (conj path node-id)
                                                           v
                                                           visiting')]
                                (recur (rest remaining-deps) v' result'))))))))))]

      ;; Process all start nodes
      (loop [remaining-starts start-ids
             visited #{}
             result nil]
        (if (or result (empty? remaining-starts))
          [visited result]
          (let [[visited' result'] (traverse (first remaining-starts) [] visited #{})]
            (recur (rest remaining-starts) visited' result')))))))

;------------------------------------------------------------------------------ Layer 1
;; DFS-based helper functions

(defn dfs-find
  "Find first node matching a predicate using DFS.

  Arguments:
  - graph        - Map of node-id -> node
  - start-id     - Starting node ID
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - found-fn     - Function (node-id, node, path) -> truthy if found

  Returns:
  - nil if not found
  - {:found-id node-id :path [...]} if found

  Example:
    (dfs-find graph \"start\"
              (fn [node] (:deps node))
              (fn [id node path] (= :tainted (:trust-level node))))"
  [graph start-id get-deps-fn found-fn]
  (let [[_visited result]
        (dfs graph
             start-id
             get-deps-fn
             ;; on-visit: check if node matches predicate
             (fn [node-id node path _visited _visiting]
               (when (found-fn node-id node path)
                 {:found-id node-id :path (conj path node-id)}))
             ;; on-cycle: ignore cycles for find
             (fn [_node-id _path _visited _visiting] nil)
             ;; on-missing: ignore missing nodes for find
             (fn [_node-id _visited _visiting] nil))]
    result))

(defn dfs-validate-graph
  "Validate graph structure (cycles, missing nodes) using DFS.

  Arguments:
  - graph        - Map of node-id -> node
  - start-nodes  - Collection of starting node IDs
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - validate-fn  - Function (node-id, node, context) -> error-map or nil
                   where context is {:cycle? bool :missing? bool :path [...]}

  Returns:
  - {:valid? true :graph graph} if valid
  - {:valid? false :error ...} if invalid

  Example:
    (dfs-validate-graph graph (keys graph)
                        (fn [node] (:deps node))
                        (fn [id node context]
                          (when (:cycle? context)
                            {:valid? false :error \"Cycle detected\"})))"
  [graph start-nodes get-deps-fn validate-fn]
  (let [[_visited result]
        (dfs graph
             start-nodes
             get-deps-fn
             ;; on-visit: continue (no validation needed on normal visit)
             (fn [_node-id _node _path _visited _visiting] nil)
             ;; on-cycle: validate cycle
             (fn [node-id path _visited _visiting]
               (validate-fn node-id nil {:cycle? true :path path}))
             ;; on-missing: validate missing node
             (fn [node-id _visited _visiting]
               (validate-fn node-id nil {:missing? true})))]
    (if result
      result
      {:valid? true :graph graph})))

(defn- collect-on-visit?
  "Returns true if the given collect-on mode should collect on visit events."
  [collect-on]
  (or (= collect-on :all) (= collect-on :visit) (= collect-on :pre)))

(defn dfs-collect
  "Collect all values from DFS traversal where collect-fn returns non-nil.

  Supports pre-order and post-order collection, and custom reduce functions.

  Arguments:
  - graph        - Map of node-id -> node
  - start-ids    - Collection of starting node IDs
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - collect-fn   - Function (node-id path visited visiting) -> value or nil
                   Called on each event (visit/cycle/missing). Return value to collect.
  - collect-on   - Keyword indicating when to collect:
                   :visit or :pre - pre-order (collect before visiting children)
                   :post          - post-order (collect after visiting children)
                   :cycle         - collect on cycle detection
                   :missing       - collect on missing nodes
                   :all           - collect on all visit events (pre-order)
  - reduce-fn    - (optional) Binary function (accumulator, value) -> accumulator.
                   Defaults to conj.
  - init         - (optional) Initial accumulator value. Defaults to [].

  Returns the accumulated result (vector by default, or custom accumulator).

  Examples:
    ;; Collect all visited node ids (pre-order)
    (dfs-collect graph [\"a\"] deps-fn (fn [id _ _ _] id) :pre)

    ;; Collect in post-order (children before parents)
    (dfs-collect graph [\"a\"] deps-fn (fn [id _ _ _] id) :post)

    ;; Count visited nodes with custom reduce
    (dfs-collect graph [\"a\"] deps-fn (fn [_ _ _ _] 1) :pre + 0)

    ;; Collect into a set
    (dfs-collect graph [\"a\"] deps-fn (fn [id _ _ _] id) :pre conj #{})"
  ([graph start-ids get-deps-fn collect-fn collect-on]
   (dfs-collect graph start-ids get-deps-fn collect-fn collect-on conj []))
  ([graph start-ids get-deps-fn collect-fn collect-on reduce-fn init]
   (if (= collect-on :post)
     ;; Post-order: collect after all children have been visited.
     ;; We use a custom DFS that accumulates post-order results.
     (let [start-ids (if (coll? start-ids) start-ids [start-ids])
           acc (volatile! init)]
       (letfn [(traverse [node-id path visited visiting]
                 (cond
                   (contains? visited node-id)
                   visited

                   (contains? visiting node-id)
                   ;; Cycle - skip for post-order
                   visited

                   :else
                   (let [node (get graph node-id)]
                     (if-not node
                       ;; Missing node - skip for post-order
                       visited
                       ;; Visit children first, then collect this node
                       (let [deps (get-deps-fn node)
                             visiting' (conj visiting node-id)
                             visited' (reduce (fn [v dep-id]
                                                (traverse dep-id
                                                          (conj path node-id)
                                                          v
                                                          visiting'))
                                              visited
                                              deps)]
                         ;; Post-order: collect after children
                         (when-let [value (collect-fn node-id path visited' visiting)]
                           (vswap! acc reduce-fn value))
                         (conj visited' node-id))))))]
         (reduce (fn [visited start-id]
                   (traverse start-id [] visited #{}))
                 #{}
                 start-ids))
       @acc)
     ;; Pre-order and other modes: collect during traversal events.
     (let [collected (atom init)]
       (dfs graph
            start-ids
            get-deps-fn
            ;; on-visit
            (fn [node-id _node path visited visiting]
              (when (collect-on-visit? collect-on)
                (when-let [value (collect-fn node-id path visited visiting)]
                  (swap! collected reduce-fn value)))
              nil)  ; Continue traversal
            ;; on-cycle
            (fn [node-id path visited visiting]
              (when (or (= collect-on :all) (= collect-on :cycle))
                (when-let [value (collect-fn node-id path visited visiting)]
                  (swap! collected reduce-fn value)))
              nil)  ; Continue traversal
            ;; on-missing
            (fn [node-id visited visiting]
              (when (or (= collect-on :all) (= collect-on :missing))
                (when-let [value (collect-fn node-id [] visited visiting)]
                  (swap! collected reduce-fn value)))
              nil))  ; Continue traversal
       @collected))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example graph
  (def graph
    {"a" {:id "a" :deps ["b" "c"]}
     "b" {:id "b" :deps ["d"]}
     "c" {:id "c" :deps ["d"]}
     "d" {:id "d" :deps []}})

  ;; Find node with specific property
  (dfs-find graph "a"
            (fn [node] (:deps node))
            (fn [id _node _path] (= id "d")))
  ;; => {:found-id "d" :path ["a" "b" "d"]}

  ;; Validate graph structure
  (dfs-validate-graph graph (keys graph)
                      (fn [node] (:deps node))
                      (fn [_id _node context]
                        (when (:cycle? context)
                          {:valid? false :error "Cycle detected"})))
  ;; => {:valid? true :graph {...}}

  ;; Collect all visited nodes (pre-order)
  (dfs-collect graph ["a"]
               (fn [node] (:deps node))
               (fn [id _path _v _vis] id)
               :visit)
  ;; => ["a" "b" "d" "c"]

  ;; Collect in post-order (children before parents)
  (dfs-collect graph ["a"]
               (fn [node] (:deps node))
               (fn [id _path _v _vis] id)
               :post)
  ;; => ["d" "b" "c" "a"]

  ;; Count nodes with custom reduce
  (dfs-collect graph ["a"]
               (fn [node] (:deps node))
               (fn [_ _ _ _] 1)
               :pre + 0)
  ;; => 4

  :leave-this-here)
