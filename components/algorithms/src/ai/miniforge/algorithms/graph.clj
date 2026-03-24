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

(defn dfs-collect
  "Collect all values from DFS traversal where collect-fn returns non-nil.

  Accumulates results during traversal using an atom.

  Arguments:
  - graph        - Map of node-id -> node
  - start-ids    - Collection of starting node IDs
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - collect-fn   - Function (node-id path visited visiting) -> value or nil
                   Called on each event (visit/cycle/missing). Return value to collect.
  - collect-on   - Keyword indicating when to collect:
                   :visit or :pre - pre-order (parent before children)
                   :post          - post-order (children before parents)
                   :cycle         - cycle back-edges only
                   :missing       - missing nodes only
                   :all           - all visit events (pre-order visits, cycles, missing)

  Returns vector of all collected non-nil values.

  Example:
    ;; Collect all cycles
    (dfs-collect graph (keys graph)
                 (fn [node] (:deps node))
                 (fn [id path _v _vis] {:cycle path})
                 :cycle)"
  [graph start-ids get-deps-fn collect-fn collect-on]
  (let [collected (atom [])]
    (if (= collect-on :post)
      ;; Post-order requires custom traversal: collect after children
      (let [start-ids (if (coll? start-ids) start-ids [start-ids])]
        (letfn [(traverse [node-id path visited visiting]
                  (cond
                    (contains? visited node-id) visited
                    (contains? visiting node-id) visited
                    :else
                    (if-let [node (get graph node-id)]
                      (let [deps (get-deps-fn node)
                            visiting' (conj visiting node-id)
                            visited' (reduce (fn [v dep]
                                              (traverse dep (conj path node-id) v visiting'))
                                            visited
                                            deps)]
                        (let [value (collect-fn node-id path visited' visiting')]
                          (when (some? value)
                            (swap! collected conj value)))
                        (conj visited' node-id))
                      ;; Missing node - skip for post
                      visited))]
          (reduce (fn [visited start]
                    (traverse start [] visited #{}))
                  #{}
                  start-ids)))
      ;; Pre-order and other modes: use core dfs
      (let [pre-visit? (or (= collect-on :visit) (= collect-on :pre) (= collect-on :all))]
        (dfs graph
             start-ids
             get-deps-fn
             ;; on-visit
             (fn [node-id _node path visited visiting]
               (when pre-visit?
                 (let [value (collect-fn node-id path visited visiting)]
                   (when (some? value)
                     (swap! collected conj value))))
               nil)  ; Continue traversal
             ;; on-cycle
             (fn [node-id path visited visiting]
               (when (or (= collect-on :all) (= collect-on :cycle))
                 (let [value (collect-fn node-id path visited visiting)]
                   (when (some? value)
                     (swap! collected conj value))))
               nil)  ; Continue traversal
             ;; on-missing
             (fn [node-id visited visiting]
               (when (or (= collect-on :all) (= collect-on :missing))
                 (let [value (collect-fn node-id [] visited visiting)]
                   (when (some? value)
                     (swap! collected conj value))))
               nil)))  ; Continue traversal
    @collected))

(defn dfs-collect-reduce
  "Like dfs-collect, but reduces collected values with a custom function.

  Arguments:
  - graph        - Map of node-id -> node
  - start-ids    - Collection of starting node IDs
  - get-deps-fn  - Function (node) -> [dependency-ids]
  - collect-fn   - Function (node-id path visited visiting) -> value or nil
  - collect-on   - Keyword: :visit, :pre, :post, :cycle, :missing, or :all
  - reduce-fn    - Function (accumulator value) -> new-accumulator
  - init         - Initial accumulator value

  Returns the final reduced value.

  Example:
    ;; Count all visited nodes
    (dfs-collect-reduce graph (keys graph)
                        (fn [node] (:deps node))
                        (fn [_id _p _v _vis] 1)
                        :visit
                        + 0)"
  [graph start-ids get-deps-fn collect-fn collect-on reduce-fn init]
  (reduce reduce-fn init
          (dfs-collect graph start-ids get-deps-fn collect-fn collect-on)))

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

  ;; Collect all visited nodes
  (dfs-collect graph ["a"]
               (fn [node] (:deps node))
               (fn [id _path _v _vis] id)
               :visit)
  ;; => ["a" "b" "d" "c"]

  ;; Count visited nodes using reduce
  (dfs-collect-reduce graph ["a"]
                      (fn [node] (:deps node))
                      (fn [_id _p _v _vis] 1)
                      :visit
                      + 0)
  ;; => 4

  :leave-this-here)
