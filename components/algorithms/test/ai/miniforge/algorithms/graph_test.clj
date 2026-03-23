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

(ns ai.miniforge.algorithms.graph-test
  "Tests for the core DFS traversal (Layer 0) in ai.miniforge.algorithms.graph.
   Exercises the raw `dfs` function directly, covering return value structure,
   visited-set accumulation, multiple start nodes, halting semantics,
   cycle/missing callbacks, and the visiting-set mechanics.

   Also tests Layer 1 helpers:
   - `dfs-validate-graph` for cycle detection, missing node detection, and
     custom validation hook behavior.
   - `dfs-find` for predicate-based node search with path tracking.
   - `dfs-collect` for accumulating values across traversal events,
     including :pre/:post ordering and custom reduce functions."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.algorithms.graph :as graph]))

;------------------------------------------------------------------------------ Test Fixtures

(def get-deps :deps)

(def empty-graph {})

(def single-node
  {"a" {:id "a" :deps []}})

(def linear-chain
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def diamond-dag
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def direct-cycle
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["a"]}})

(def self-cycle
  {"a" {:id "a" :deps ["a"]}})

(def indirect-cycle
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a"]}})

(def forest-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps []}
   "x" {:id "x" :deps ["y"]}
   "y" {:id "y" :deps []}})

(def missing-dep-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["missing"]}})

(defn noop-visit   [_id _node _path _visited _visiting] nil)
(defn noop-cycle   [_id _path _visited _visiting] nil)
(defn noop-missing [_id _visited _visiting] nil)

;------------------------------------------------------------------------------ Return value structure

(deftest dfs-return-value-structure-test
  (testing "returns a two-element vector [visited result]"
    (let [ret (graph/dfs single-node ["a"] get-deps
                         noop-visit noop-cycle noop-missing)]
      (is (vector? ret))
      (is (= 2 (count ret)))))

  (testing "visited is a set, result is nil when traversal completes"
    (let [[visited result] (graph/dfs single-node ["a"] get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (set? visited))
      (is (nil? result))))

  (testing "visited contains all reachable node IDs after full traversal"
    (let [[visited _] (graph/dfs diamond-dag ["a"] get-deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))))

  (testing "result is non-nil when a callback halts traversal"
    (let [[_visited result]
          (graph/dfs diamond-dag ["a"] get-deps
                     (fn [id _node _path _visited _visiting]
                       (when (= id "b") {:halt id}))
                     noop-cycle noop-missing)]
      (is (= {:halt "b"} result)))))

;------------------------------------------------------------------------------ Empty & trivial graphs

(deftest dfs-empty-graph-test
  (testing "empty graph with no start nodes returns empty visited and nil result"
    (let [[visited result] (graph/dfs empty-graph [] get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{} visited))
      (is (nil? result))))

  (testing "empty graph with start nodes invokes on-missing for each start"
    (let [missing-ids (atom [])
          [_ _] (graph/dfs empty-graph ["ghost"] get-deps
                           noop-visit noop-cycle
                           (fn [id _v _vis]
                             (swap! missing-ids conj id)
                             nil))]
      (is (= ["ghost"] @missing-ids)))))

;------------------------------------------------------------------------------ visited-set accumulation

(deftest dfs-visited-set-accumulation-test
  (testing "single node is added to visited"
    (let [[visited _] (graph/dfs single-node ["a"] get-deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))))

  (testing "all reachable nodes in linear chain are visited"
    (let [[visited _] (graph/dfs linear-chain ["a"] get-deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))))

  (testing "diamond dag does not double-visit shared dependency"
    (let [visit-count (atom 0)
          [visited _] (graph/dfs diamond-dag ["a"] get-deps
                                 (fn [_id _node _path _v _vis]
                                   (swap! visit-count inc)
                                   nil)
                                 noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (= 4 @visit-count)))))

;------------------------------------------------------------------------------ Multiple start nodes

(deftest dfs-multiple-start-nodes-test
  (testing "disjoint forests are fully traversed"
    (let [[visited _] (graph/dfs forest-graph ["a" "x"] get-deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "x" "y"} visited))))

  (testing "overlapping start nodes do not re-visit"
    (let [visit-count (atom 0)
          [_ _] (graph/dfs linear-chain ["a" "c"] get-deps
                           (fn [_id _node _path _v _vis]
                             (swap! visit-count inc)
                             nil)
                           noop-cycle noop-missing)]
      ;; 'a' traversal visits a,b,c,d. 'c' start finds c,d already visited.
      (is (= 4 @visit-count)))))

;------------------------------------------------------------------------------ Halting semantics

(deftest dfs-halting-semantics-test
  (testing "on-visit can halt traversal early"
    (let [[visited result]
          (graph/dfs linear-chain ["a"] get-deps
                     (fn [id _node _path _v _vis]
                       (when (= id "c") :found-c))
                     noop-cycle noop-missing)]
      (is (= :found-c result))
      ;; c halted on visit, so d was never reached
      (is (not (contains? visited "d")))))

  (testing "on-cycle can halt traversal"
    (let [[_ result]
          (graph/dfs direct-cycle ["a"] get-deps
                     noop-visit
                     (fn [id path _v _vis] {:cycle-at id :path path})
                     noop-missing)]
      (is (= "a" (:cycle-at result)))))

  (testing "on-missing can halt traversal"
    (let [[_ result]
          (graph/dfs missing-dep-graph ["a"] get-deps
                     noop-visit noop-cycle
                     (fn [id _v _vis] {:missing id}))]
      (is (= {:missing "missing"} result)))))

;------------------------------------------------------------------------------ Cycle detection

(deftest dfs-cycle-detection-test
  (testing "direct cycle triggers on-cycle callback"
    (let [cycle-ids (atom [])
          _ (graph/dfs direct-cycle ["a"] get-deps
                       noop-visit
                       (fn [id _path _v _vis]
                         (swap! cycle-ids conj id)
                         nil)
                       noop-missing)]
      (is (= ["a"] @cycle-ids))))

  (testing "self-cycle triggers on-cycle for same node"
    (let [cycle-ids (atom [])
          _ (graph/dfs self-cycle ["a"] get-deps
                       noop-visit
                       (fn [id _path _v _vis]
                         (swap! cycle-ids conj id)
                         nil)
                       noop-missing)]
      (is (= ["a"] @cycle-ids))))

  (testing "indirect cycle triggers on-cycle callback"
    (let [cycle-ids (atom [])
          _ (graph/dfs indirect-cycle ["a"] get-deps
                       noop-visit
                       (fn [id _path _v _vis]
                         (swap! cycle-ids conj id)
                         nil)
                       noop-missing)]
      (is (= ["a"] @cycle-ids)))))

;------------------------------------------------------------------------------ Missing node detection

(deftest dfs-missing-node-detection-test
  (testing "missing dependency triggers on-missing callback"
    (let [missing-ids (atom [])
          _ (graph/dfs missing-dep-graph ["a"] get-deps
                       noop-visit noop-cycle
                       (fn [id _v _vis]
                         (swap! missing-ids conj id)
                         nil))]
      (is (= ["missing"] @missing-ids))))

  (testing "missing start node triggers on-missing"
    (let [missing-ids (atom [])
          _ (graph/dfs empty-graph ["ghost"] get-deps
                       noop-visit noop-cycle
                       (fn [id _v _vis]
                         (swap! missing-ids conj id)
                         nil))]
      (is (= ["ghost"] @missing-ids)))))

;------------------------------------------------------------------------------ Visiting set mechanics

(deftest dfs-visiting-set-test
  (testing "visiting set contains ancestors on the current path"
    (let [captured-visiting (atom nil)
          _ (graph/dfs linear-chain ["a"] get-deps
                       (fn [id _node _path _visited visiting]
                         (when (= id "d")
                           (reset! captured-visiting visiting))
                         nil)
                       noop-cycle noop-missing)]
      ;; When visiting d, the visiting set should contain a, b, c
      (is (= #{"a" "b" "c"} @captured-visiting))))

  (testing "visiting set is reset between start nodes"
    (let [captured (atom [])
          _ (graph/dfs forest-graph ["a" "x"] get-deps
                       (fn [id _node _path _visited visiting]
                         (swap! captured conj {:id id :visiting visiting})
                         nil)
                       noop-cycle noop-missing)]
      ;; When visiting x, visiting should not contain a or b
      (let [x-entry (first (filter #(= "x" (:id %)) @captured))]
        (is (empty? (:visiting x-entry)))))))

;------------------------------------------------------------------------------ Path tracking

(deftest dfs-path-tracking-test
  (testing "path grows as traversal descends"
    (let [captured-paths (atom {})
          _ (graph/dfs linear-chain ["a"] get-deps
                       (fn [id _node path _v _vis]
                         (swap! captured-paths assoc id path)
                         nil)
                       noop-cycle noop-missing)]
      (is (= [] (get @captured-paths "a")))
      (is (= ["a"] (get @captured-paths "b")))
      (is (= ["a" "b"] (get @captured-paths "c")))
      (is (= ["a" "b" "c"] (get @captured-paths "d")))))

  (testing "cycle path includes the cycle-forming node"
    (let [captured-path (atom nil)
          _ (graph/dfs direct-cycle ["a"] get-deps
                       noop-visit
                       (fn [id path _v _vis]
                         (reset! captured-path path)
                         nil)
                       noop-missing)]
      ;; Path should show a -> b -> a (cycle back)
      (is (= ["a" "b" "a"] @captured-path)))))

;------------------------------------------------------------------------------ Scalar start-id coercion

(deftest dfs-scalar-start-id-test
  (testing "single scalar start-id is coerced to collection"
    (let [[visited result] (graph/dfs single-node "a" get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))
      (is (nil? result))))

  (testing "scalar start-id traverses full subgraph"
    (let [[visited _] (graph/dfs linear-chain "a" get-deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))))

  (testing "keyword scalar start-id works"
    (let [graph {:x {:id :x :deps [:y]}
                 :y {:id :y :deps []}}
          [visited _] (graph/dfs graph :x :deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{:x :y} visited)))))

;------------------------------------------------------------------------------ Halt on first node

(deftest dfs-halt-on-first-node-test
  (testing "halting on the very first visited node returns immediately"
    (let [[visited result]
          (graph/dfs linear-chain ["a"] get-deps
                     (fn [id _node _path _v _vis] {:immediate id})
                     noop-cycle noop-missing)]
      (is (= {:immediate "a"} result))
      ;; Only "a" should be in visited (added after halt)
      (is (not (contains? visited "b")))))

  (testing "halting on first start node skips subsequent start nodes"
    (let [visit-count (atom 0)
          [_ result]
          (graph/dfs forest-graph ["a" "x"] get-deps
                     (fn [id _node _path _v _vis]
                       (swap! visit-count inc)
                       {:stop id})
                     noop-cycle noop-missing)]
      (is (= {:stop "a"} result))
      (is (= 1 @visit-count)))))

;------------------------------------------------------------------------------ Halt propagation through deps

(deftest dfs-halt-propagation-test
  (testing "halt in deep dependency stops sibling deps from being visited"
    (let [graph {"root" {:id "root" :deps ["left" "right"]}
                 "left"  {:id "left"  :deps ["deep"]}
                 "deep"  {:id "deep"  :deps []}
                 "right" {:id "right" :deps []}}
          visited-ids (atom [])
          [_ result]
          (graph/dfs graph ["root"] :deps
                     (fn [id _node _path _v _vis]
                       (swap! visited-ids conj id)
                       (when (= id "deep") :halt-deep))
                     noop-cycle noop-missing)]
      (is (= :halt-deep result))
      ;; "right" should NOT have been visited
      (is (not (some #{"right"} @visited-ids))))))

;------------------------------------------------------------------------------ Non-string node IDs

(deftest dfs-non-string-node-ids-test
  (testing "integer node IDs work correctly"
    (let [graph {1 {:id 1 :deps [2 3]}
                 2 {:id 2 :deps []}
                 3 {:id 3 :deps []}}
          [visited _] (graph/dfs graph [1] :deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{1 2 3} visited))))

  (testing "keyword node IDs work correctly"
    (let [graph {:a {:id :a :deps [:b]}
                 :b {:id :b :deps []}}
          [visited _] (graph/dfs graph [:a] :deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{:a :b} visited))))

  (testing "mixed ID types (unusual but supported by map semantics)"
    (let [graph {"a" {:id "a" :deps [42]}
                 42  {:id 42  :deps []}}
          [visited _] (graph/dfs graph ["a"] :deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a" 42} visited)))))

;------------------------------------------------------------------------------ Wide graph (many deps)

(deftest dfs-wide-graph-test
  (testing "node with many children visits all of them"
    (let [child-ids (mapv #(str "child-" %) (range 20))
          graph (into {"root" {:id "root" :deps child-ids}}
                      (map (fn [id] [id {:id id :deps []}]) child-ids))
          [visited _] (graph/dfs graph ["root"] :deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= 21 (count visited)))
      (is (contains? visited "root"))
      (is (every? #(contains? visited %) child-ids)))))

;------------------------------------------------------------------------------ Deep graph

(deftest dfs-deep-graph-test
  (testing "deeply nested graph is fully traversed"
    (let [depth 50
          ids (mapv str (range depth))
          graph (into {}
                      (map-indexed
                       (fn [i id]
                         [id {:id id :deps (if (< i (dec depth))
                                            [(str (inc i))]
                                            [])}])
                       ids))
          [visited _] (graph/dfs graph ["0"] :deps
                                 noop-visit noop-cycle noop-missing)]
      (is (= (set ids) visited)))))

;------------------------------------------------------------------------------ Node with empty deps

(deftest dfs-node-empty-deps-test
  (testing "node with nil deps (get-deps returns nil) is treated as leaf"
    (let [graph {"a" {:id "a" :links nil}}
          [visited _] (graph/dfs graph ["a"] :links
                                 noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited)))))

;------------------------------------------------------------------------------ dfs-validate-graph tests

(def cycle-and-missing-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["a"]}
   "c" {:id "c" :deps ["gone"]}})

(def multiple-missing-graph
  {"a" {:id "a" :deps ["gone1" "gone2"]}})

(defn cycle-rejecting-validate-fn
  "Standard validate-fn that rejects cycles."
  [_id _node context]
  (when (:cycle? context)
    {:valid? false :error "Cycle detected" :path (:path context)}))

(defn missing-rejecting-validate-fn
  "Standard validate-fn that rejects missing nodes."
  [id _node context]
  (when (:missing? context)
    {:valid? false :error (str "Missing node: " id)}))

(defn strict-validate-fn
  "Rejects both cycles and missing nodes."
  [id _node context]
  (cond
    (:cycle? context)   {:valid? false :error "Cycle detected" :path (:path context)}
    (:missing? context) {:valid? false :error (str "Missing node: " id)}))

(deftest dfs-validate-graph-cycle-detection-test
  (testing "detects direct cycle"
    (let [result (graph/dfs-validate-graph direct-cycle (keys direct-cycle)
                                          get-deps cycle-rejecting-validate-fn)]
      (is (false? (:valid? result)))
      (is (= "Cycle detected" (:error result)))))

  (testing "detects indirect cycle"
    (let [result (graph/dfs-validate-graph indirect-cycle (keys indirect-cycle)
                                          get-deps cycle-rejecting-validate-fn)]
      (is (false? (:valid? result)))))

  (testing "no cycle in linear chain"
    (let [result (graph/dfs-validate-graph linear-chain (keys linear-chain)
                                          get-deps cycle-rejecting-validate-fn)]
      (is (true? (:valid? result)))
      (is (= linear-chain (:graph result))))))

(deftest dfs-validate-graph-missing-node-test
  (testing "detects missing dependency"
    (let [result (graph/dfs-validate-graph missing-dep-graph (keys missing-dep-graph)
                                          get-deps missing-rejecting-validate-fn)]
      (is (false? (:valid? result)))
      (is (re-find #"Missing node" (:error result)))))

  (testing "no missing nodes in complete graph"
    (let [result (graph/dfs-validate-graph diamond-dag (keys diamond-dag)
                                          get-deps missing-rejecting-validate-fn)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-combined-test
  (testing "strict validation catches first error (cycle or missing)"
    (let [result (graph/dfs-validate-graph cycle-and-missing-graph
                                          (keys cycle-and-missing-graph)
                                          get-deps strict-validate-fn)]
      (is (false? (:valid? result))))))

(deftest dfs-validate-graph-valid-graphs-test
  (testing "empty graph is valid"
    (let [result (graph/dfs-validate-graph empty-graph [] get-deps
                                          strict-validate-fn)]
      (is (true? (:valid? result)))))

  (testing "single node graph is valid"
    (let [result (graph/dfs-validate-graph single-node ["a"] get-deps
                                          strict-validate-fn)]
      (is (true? (:valid? result)))))

  (testing "diamond DAG is valid"
    (let [result (graph/dfs-validate-graph diamond-dag (keys diamond-dag)
                                          get-deps strict-validate-fn)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-custom-context-test
  (testing "validate-fn receives cycle context with path"
    (let [captured (atom nil)
          _ (graph/dfs-validate-graph direct-cycle (keys direct-cycle)
                                     get-deps
                                     (fn [_id _node context]
                                       (when (:cycle? context)
                                         (reset! captured context)
                                         nil)))]
      (is (true? (:cycle? @captured)))
      (is (vector? (:path @captured)))))

  (testing "validate-fn receives missing context"
    (let [captured (atom nil)
          _ (graph/dfs-validate-graph missing-dep-graph (keys missing-dep-graph)
                                     get-deps
                                     (fn [_id _node context]
                                       (when (:missing? context)
                                         (reset! captured context)
                                         nil)))]
      (is (true? (:missing? @captured))))))

(deftest dfs-validate-graph-self-cycle-test
  (testing "detects self-cycle"
    (let [result (graph/dfs-validate-graph self-cycle ["a"] get-deps
                                          cycle-rejecting-validate-fn)]
      (is (false? (:valid? result)))
      (is (= "Cycle detected" (:error result))))))

(deftest dfs-validate-graph-returns-graph-on-valid-test
  (testing "valid result includes the original graph"
    (let [result (graph/dfs-validate-graph diamond-dag (keys diamond-dag)
                                          get-deps strict-validate-fn)]
      (is (true? (:valid? result)))
      (is (identical? diamond-dag (:graph result))))))

(deftest dfs-validate-graph-lenient-validate-fn-test
  (testing "validate-fn that ignores all issues returns valid"
    (let [lenient-fn (fn [_id _node _ctx] nil)
          result (graph/dfs-validate-graph cycle-and-missing-graph
                                          (keys cycle-and-missing-graph)
                                          get-deps lenient-fn)]
      (is (true? (:valid? result))))))

;------------------------------------------------------------------------------ dfs-find tests

(deftest dfs-find-basic-test
  (testing "finds a node matching predicate"
    (let [result (graph/dfs-find linear-chain "a" get-deps
                                (fn [id _node _path] (= id "d")))]
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))))

  (testing "returns nil when no match"
    (let [result (graph/dfs-find linear-chain "a" get-deps
                                (fn [_id _node _path] false))]
      (is (nil? result))))

  (testing "returns nil for empty graph"
    (let [result (graph/dfs-find empty-graph "a" get-deps
                                (fn [_id _node _path] true))]
      (is (nil? result)))))

(deftest dfs-find-path-test
  (testing "path includes start and found node"
    (let [result (graph/dfs-find linear-chain "a" get-deps
                                (fn [id _node _path] (= id "c")))]
      (is (= "c" (:found-id result)))
      (is (= ["a" "b" "c"] (:path result)))))

  (testing "path for start node itself"
    (let [result (graph/dfs-find single-node "a" get-deps
                                (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] (:path result))))))

(deftest dfs-find-diamond-test
  (testing "finds node in diamond DAG"
    (let [result (graph/dfs-find diamond-dag "a" get-deps
                                (fn [id _node _path] (= id "d")))]
      (is (= "d" (:found-id result)))))

  (testing "finds first match in DFS order"
    (let [result (graph/dfs-find diamond-dag "a" get-deps
                                (fn [id _node _path]
                                  (contains? #{"b" "c"} id)))]
      ;; DFS visits b before c (b is first dep of a)
      (is (= "b" (:found-id result))))))

(deftest dfs-find-with-cycle-test
  (testing "find works safely with cycles"
    (let [result (graph/dfs-find direct-cycle "a" get-deps
                                (fn [id _node _path] (= id "b")))]
      (is (= "b" (:found-id result)))))

  (testing "find returns nil when target not in cyclic graph"
    (let [result (graph/dfs-find direct-cycle "a" get-deps
                                (fn [id _node _path] (= id "z")))]
      (is (nil? result)))))

(deftest dfs-find-predicate-receives-node-test
  (testing "predicate receives the full node map"
    (let [graph {"a" {:id "a" :deps ["b"] :color :red}
                 "b" {:id "b" :deps [] :color :blue}}
          result (graph/dfs-find graph "a" get-deps
                                (fn [_id node _path] (= :blue (:color node))))]
      (is (= "b" (:found-id result))))))

(deftest dfs-find-start-node-match-test
  (testing "start node can match the predicate"
    (let [result (graph/dfs-find linear-chain "a" get-deps
                                (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result))))))

(deftest dfs-find-predicate-receives-path-test
  (testing "found-fn receives the current path at time of match"
    (let [captured-path (atom nil)
          result (graph/dfs-find linear-chain "a" get-deps
                                (fn [id _node path]
                                  (when (= id "c")
                                    (reset! captured-path path)
                                    true)))]
      (is (= "c" (:found-id result)))
      ;; The path passed to found-fn is the ancestor path (before conj)
      (is (= ["a" "b"] @captured-path)))))

(deftest dfs-find-missing-dep-ignored-test
  (testing "missing dependencies are silently ignored during find"
    (let [result (graph/dfs-find missing-dep-graph "a" get-deps
                                (fn [id _node _path] (= id "b")))]
      (is (= "b" (:found-id result)))))

  (testing "find completes without error when target is missing"
    (let [result (graph/dfs-find missing-dep-graph "a" get-deps
                                (fn [id _node _path] (= id "missing")))]
      ;; "missing" is not in graph so on-visit is never called for it
      (is (nil? result)))))

;------------------------------------------------------------------------------ dfs-collect tests

(deftest dfs-collect-visit-test
  (testing "collects values for each visited node with :visit"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (= ["a" "b" "c" "d"] result))))

  (testing "collects transformed values from visited nodes"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] (str "visited-" id))
                                   :visit)]
      (is (= ["visited-a" "visited-b" "visited-c" "visited-d"] result))))

  (testing "nil values from collect-fn are excluded"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis]
                                     (when (not= id "b") id))
                                   :visit)]
      (is (= ["a" "c" "d"] result))))

  (testing "returns empty vector for empty graph"
    (let [result (graph/dfs-collect empty-graph [] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (= [] result))))

  (testing "each node visited once in diamond DAG"
    (let [result (graph/dfs-collect diamond-dag ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

(deftest dfs-collect-cycle-test
  (testing "collects cycle events with :cycle"
    (let [result (graph/dfs-collect direct-cycle ["a"] get-deps
                                   (fn [id path _v _vis] {:cycle-at id :path path})
                                   :cycle)]
      (is (pos? (count result)))
      (is (every? :cycle-at result))))

  (testing "does not collect visit events when collect-on is :cycle"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :cycle)]
      (is (= [] result)
          "No cycles in linear chain, so nothing collected")))

  (testing "collects all cycles in indirect cycle graph"
    (let [result (graph/dfs-collect indirect-cycle ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :cycle)]
      (is (pos? (count result))))))

(deftest dfs-collect-missing-test
  (testing "collects missing node events with :missing"
    (let [result (graph/dfs-collect missing-dep-graph ["a"] get-deps
                                   (fn [id _path _v _vis] {:missing id})
                                   :missing)]
      (is (= [{:missing "missing"}] result))))

  (testing "does not collect visit or cycle events when collect-on is :missing"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :missing)]
      (is (= [] result))))

  (testing "collects missing for start node not in graph"
    (let [result (graph/dfs-collect empty-graph ["ghost"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :missing)]
      (is (= ["ghost"] result))))

  (testing "collects multiple missing nodes"
    (let [result (graph/dfs-collect multiple-missing-graph ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :missing)]
      (is (= 2 (count result)))
      (is (= #{"gone1" "gone2"} (set result))))))

(deftest dfs-collect-all-test
  (testing "collects visits, cycles, and missing events with :all"
    (let [result (graph/dfs-collect cycle-and-missing-graph ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :all)]
      ;; Should collect visit events for a, b, c plus cycle/missing events
      (is (pos? (count result)))))

  (testing ":all collects both visit and cycle events"
    (let [result (graph/dfs-collect direct-cycle ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :all)]
      ;; Visits: a, b. Cycle: a (when b's dep a is in visiting).
      (is (>= (count result) 2))))

  (testing ":all collects both visit and missing events"
    (let [result (graph/dfs-collect missing-dep-graph ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :all)]
      ;; Visits: a, b. Missing: missing.
      (is (= 3 (count result)))
      (is (= #{"a" "b" "missing"} (set result))))))

(deftest dfs-collect-never-halts-test
  (testing "traversal continues through all nodes even with cycles"
    (let [visit-result (graph/dfs-collect direct-cycle ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      ;; Should visit both a and b despite the cycle
      (is (= #{"a" "b"} (set visit-result)))))

  (testing "traversal continues through all start nodes"
    (let [result (graph/dfs-collect forest-graph ["a" "x"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (= #{"a" "b" "x" "y"} (set result))))))

(deftest dfs-collect-custom-get-deps-test
  (testing "uses custom get-deps-fn"
    (let [graph {"a" {:name "a" :links ["b"]}
                 "b" {:name "b" :links []}}
          result (graph/dfs-collect graph ["a"] :links
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (= ["a" "b"] result)))))

(deftest dfs-collect-callback-receives-correct-args-test
  (testing "collect-fn on :visit receives node-id, path, visited, visiting"
    (let [captured (atom nil)
          _ (graph/dfs-collect linear-chain ["a"] get-deps
                              (fn [id path visited visiting]
                                (when (= id "b")
                                  (reset! captured {:id id :path path
                                                    :visited visited
                                                    :visiting visiting}))
                                nil)
                              :visit)]
      (is (= "b" (:id @captured)))
      ;; path for on-visit is the ancestor path (passed through from dfs)
      (is (vector? (:path @captured)))
      (is (set? (:visited @captured)))
      (is (set? (:visiting @captured))))))

(deftest dfs-collect-scalar-start-id-test
  (testing "scalar start-id works with dfs-collect"
    (let [result (graph/dfs-collect single-node "a" get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (= ["a"] result))))

  (testing "scalar start-id works with :post mode"
    (let [result (graph/dfs-collect linear-chain "a" get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      (is (= ["d" "c" "b" "a"] result)))))

;------------------------------------------------------------------------------ dfs-collect :pre mode tests

(deftest dfs-collect-pre-mode-test
  (testing ":pre is an alias for :visit — collects before visiting children"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :pre)]
      (is (= ["a" "b" "c" "d"] result))))

  (testing ":pre collects matching nodes based on criteria"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :color :red}
                 "b" {:id "b" :deps ["d"] :color :blue}
                 "c" {:id "c" :deps [] :color :red}
                 "d" {:id "d" :deps [] :color :blue}}
          result (graph/dfs-collect graph ["a"] get-deps
                                   (fn [id _path _v _vis]
                                     (when (= :red (:color (get graph id)))
                                       id))
                                   :pre)]
      (is (= ["a" "c"] result))))

  (testing ":pre returns empty vector for empty graph"
    (let [result (graph/dfs-collect empty-graph [] get-deps
                                   (fn [id _path _v _vis] id)
                                   :pre)]
      (is (= [] result))))

  (testing ":pre matches :visit output for diamond DAG"
    (let [pre-result (graph/dfs-collect diamond-dag ["a"] get-deps
                                       (fn [id _path _v _vis] id)
                                       :pre)
          visit-result (graph/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (= pre-result visit-result)))))

;------------------------------------------------------------------------------ dfs-collect :post mode tests

(deftest dfs-collect-post-mode-test
  (testing ":post collects after visiting children — leaf first"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      ;; Post-order: d first (leaf), then c, b, a
      (is (= ["d" "c" "b" "a"] result))))

  (testing ":post on diamond DAG visits shared dep before parents"
    (let [result (graph/dfs-collect diamond-dag ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      ;; d is visited before b and c; a is last
      (is (= "a" (last result)))
      (let [d-idx (.indexOf result "d")
            b-idx (.indexOf result "b")
            c-idx (.indexOf result "c")]
        (is (< d-idx b-idx) "d (leaf) appears before b")
        (is (< d-idx c-idx) "d (leaf) appears before c (via second path, already visited)"))
      ;; All four nodes collected exactly once
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result)))))

  (testing ":post returns empty vector for empty graph"
    (let [result (graph/dfs-collect empty-graph [] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      (is (= [] result))))

  (testing ":post with single node"
    (let [result (graph/dfs-collect single-node ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      (is (= ["a"] result))))

  (testing ":post collects nodes matching criteria"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :type :root}
                 "b" {:id "b" :deps ["d"] :type :branch}
                 "c" {:id "c" :deps [] :type :leaf}
                 "d" {:id "d" :deps [] :type :leaf}}
          result (graph/dfs-collect graph ["a"] get-deps
                                   (fn [id _path _v _vis]
                                     (when (= :leaf (:type (get graph id)))
                                       id))
                                   :post)]
      (is (= #{"c" "d"} (set result)))
      (is (= 2 (count result)))))

  (testing ":post with forest graph visits all trees"
    (let [result (graph/dfs-collect forest-graph ["a" "x"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      (is (= #{"a" "b" "x" "y"} (set result)))
      ;; Leaves before roots in each tree
      (is (< (.indexOf result "b") (.indexOf result "a")))
      (is (< (.indexOf result "y") (.indexOf result "x")))))

  (testing ":post with cycle graph still visits all nodes"
    (let [result (graph/dfs-collect direct-cycle ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      (is (= #{"a" "b"} (set result))))))

(deftest dfs-collect-post-cycle-collection-test
  (testing ":post mode collects cycle events too"
    (let [result (graph/dfs-collect direct-cycle ["a"] get-deps
                                   (fn [id _path _v _vis] {:event :cycle :id id})
                                   :cycle)]
      ;; :cycle collect-on in :post branch still invokes cycle handler
      (is (pos? (count result))))))

(deftest dfs-collect-post-missing-collection-test
  (testing ":post mode handles missing nodes"
    (let [result (graph/dfs-collect missing-dep-graph ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post)]
      ;; Should collect a and b (missing node is not collected in :post)
      (is (= #{"a" "b"} (set result))))))

(deftest dfs-collect-pre-vs-post-ordering-test
  (testing "pre-order and post-order produce reverse orderings for linear chain"
    (let [pre-result (graph/dfs-collect linear-chain ["a"] get-deps
                                       (fn [id _path _v _vis] id)
                                       :pre)
          post-result (graph/dfs-collect linear-chain ["a"] get-deps
                                        (fn [id _path _v _vis] id)
                                        :post)]
      (is (= pre-result (reverse post-result))))))

;------------------------------------------------------------------------------ dfs-collect custom reduce tests

(deftest dfs-collect-custom-reduce-test
  (testing "counting nodes with + reduce"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [_id _path _v _vis] 1)
                                   :visit
                                   {:reduce-fn + :init 0})]
      (is (= 4 result))))

  (testing "summing numeric values with custom reduce"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :weight 10}
                 "b" {:id "b" :deps [] :weight 20}
                 "c" {:id "c" :deps [] :weight 30}}
          result (graph/dfs-collect graph ["a"] get-deps
                                   (fn [id _path _v _vis]
                                     (:weight (get graph id)))
                                   :visit
                                   {:reduce-fn + :init 0})]
      (is (= 60 result))))

  (testing "building a set with conj and #{} init"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit
                                   {:reduce-fn conj :init #{}})]
      (is (= #{"a" "b" "c" "d"} result))))

  (testing "building a string with str reduce"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit
                                   {:reduce-fn str :init ""})]
      (is (= "abcd" result))))

  (testing "custom reduce with :post mode"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post
                                   {:reduce-fn str :init ""})]
      (is (= "dcba" result))))

  (testing "custom reduce with :pre mode"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [_id _path _v _vis] 1)
                                   :pre
                                   {:reduce-fn + :init 0})]
      (is (= 4 result))))

  (testing "custom reduce on empty graph returns init value"
    (let [result (graph/dfs-collect empty-graph [] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit
                                   {:reduce-fn + :init 0})]
      (is (= 0 result))))

  (testing "custom reduce collects cycles too"
    (let [result (graph/dfs-collect direct-cycle ["a"] get-deps
                                   (fn [_id _path _v _vis] 1)
                                   :cycle
                                   {:reduce-fn + :init 0})]
      (is (= 1 result)
          "One cycle detected in direct-cycle graph")))

  (testing "default opts (no opts map) still returns vector"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit)]
      (is (vector? result))
      (is (= ["a" "b" "c" "d"] result)))))

(deftest dfs-collect-custom-reduce-accumulation-test
  (testing "reduce-fn receives accumulator and current value correctly"
    (let [;; Use a reduce that builds a map of id -> visit-order
          result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :visit
                                   {:reduce-fn (fn [acc id]
                                                 (assoc acc id (count acc)))
                                    :init {}})]
      (is (= {"a" 0 "b" 1 "c" 2 "d" 3} result))))

  (testing "reduce-fn with max to find heaviest node weight"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :weight 5}
                 "b" {:id "b" :deps [] :weight 15}
                 "c" {:id "c" :deps [] :weight 8}}
          result (graph/dfs-collect graph ["a"] get-deps
                                   (fn [id _path _v _vis]
                                     (:weight (get graph id)))
                                   :visit
                                   {:reduce-fn max :init 0})]
      (is (= 15 result)))))

;------------------------------------------------------------------------------ dfs-collect :post with custom reduce

(deftest dfs-collect-post-custom-reduce-test
  (testing ":post with counting reduce"
    (let [result (graph/dfs-collect diamond-dag ["a"] get-deps
                                   (fn [_id _path _v _vis] 1)
                                   :post
                                   {:reduce-fn + :init 0})]
      (is (= 4 result))))

  (testing ":post with set accumulator"
    (let [result (graph/dfs-collect diamond-dag ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :post
                                   {:reduce-fn conj :init #{}})]
      (is (= #{"a" "b" "c" "d"} result)))))

;------------------------------------------------------------------------------ collect-on-matches? edge cases (indirect)

(deftest dfs-collect-unknown-collect-on-test
  (testing "unknown collect-on keyword collects nothing"
    (let [result (graph/dfs-collect linear-chain ["a"] get-deps
                                   (fn [id _path _v _vis] id)
                                   :bogus)]
      ;; :bogus doesn't match any event, so nothing collected
      (is (= [] result)))))

;------------------------------------------------------------------------------ dfs-collect :all with cycles and missing

(deftest dfs-collect-all-comprehensive-test
  (testing ":all collects visit + cycle + missing in single traversal"
    (let [graph {"a" {:id "a" :deps ["b" "c" "missing"]}
                 "b" {:id "b" :deps ["a"]}  ;; cycle
                 "c" {:id "c" :deps []}}
          events (atom [])
          result (graph/dfs-collect graph ["a"] :deps
                                   (fn [id _path _v _vis]
                                     (swap! events conj id)
                                     id)
                                   :all)]
      ;; Should have visit events for a, b, c and cycle/missing events
      (is (>= (count result) 4))
      ;; a, b, c visited; a cycle back; missing
      (is (some #{"missing"} result) "missing node collected")
      (is (some #{"a"} result) "root visited"))))

;------------------------------------------------------------------------------ dfs with multiple missing start nodes

(deftest dfs-multiple-missing-start-nodes-test
  (testing "multiple missing start nodes each trigger on-missing"
    (let [missing-ids (atom [])
          [_ _] (graph/dfs empty-graph ["x" "y" "z"] get-deps
                           noop-visit noop-cycle
                           (fn [id _v _vis]
                             (swap! missing-ids conj id)
                             nil))]
      (is (= ["x" "y" "z"] @missing-ids))))

  (testing "first missing start can halt before subsequent starts"
    (let [[_ result] (graph/dfs empty-graph ["x" "y"] get-deps
                               noop-visit noop-cycle
                               (fn [id _v _vis] {:halted id}))]
      (is (= {:halted "x"} result)))))

;------------------------------------------------------------------------------ dfs idempotency of visited across starts

(deftest dfs-visited-persists-across-starts-test
  (testing "visited from first start carries to second start"
    (let [visit-log (atom [])
          ;; a->b->c and c->d — starting from [a c],
          ;; c should already be visited by the time we get to start c
          graph {"a" {:id "a" :deps ["b"]}
                 "b" {:id "b" :deps ["c"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps []}}
          [visited _] (graph/dfs graph ["a" "c"] :deps
                                 (fn [id _node _path _v _vis]
                                   (swap! visit-log conj id)
                                   nil)
                                 noop-cycle noop-missing)]
      ;; All 4 nodes visited, but only once each
      (is (= #{"a" "b" "c" "d"} visited))
      (is (= 4 (count @visit-log))))))

;------------------------------------------------------------------------------ dfs-find with indirect cycle

(deftest dfs-find-indirect-cycle-test
  (testing "find terminates safely in indirect cycle when target absent"
    (let [result (graph/dfs-find indirect-cycle "a" get-deps
                                (fn [id _node _path] (= id "nonexistent")))]
      (is (nil? result))))

  (testing "find locates node in graph with indirect cycle"
    (let [result (graph/dfs-find indirect-cycle "a" get-deps
                                (fn [id _node _path] (= id "c")))]
      (is (= "c" (:found-id result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run these tests:
  ;; bb test -- -n ai.miniforge.algorithms.graph-test

  :leave-this-here)
