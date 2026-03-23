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

(ns ai.miniforge.algorithms.interface-test
  "Tests for the public algorithms interface: dfs-find, dfs-validate-graph,
   dfs-collect, and dfs."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.algorithms.interface :as algorithms]))

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

(def missing-dep-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["missing"]}})

(def cycle-and-missing-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["a"]}
   "c" {:id "c" :deps ["gone"]}})

(def wide-graph
  {"root" {:id "root" :deps ["c1" "c2" "c3" "c4" "c5"]}
   "c1" {:id "c1" :deps []}
   "c2" {:id "c2" :deps []}
   "c3" {:id "c3" :deps []}
   "c4" {:id "c4" :deps []}
   "c5" {:id "c5" :deps []}})

(def deep-graph
  {"n0" {:id "n0" :deps ["n1"]}
   "n1" {:id "n1" :deps ["n2"]}
   "n2" {:id "n2" :deps ["n3"]}
   "n3" {:id "n3" :deps ["n4"]}
   "n4" {:id "n4" :deps ["n5"]}
   "n5" {:id "n5" :deps ["n6"]}
   "n6" {:id "n6" :deps ["n7"]}
   "n7" {:id "n7" :deps ["n8"]}
   "n8" {:id "n8" :deps ["n9"]}
   "n9" {:id "n9" :deps []}})

(def forest-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps []}
   "x" {:id "x" :deps ["y"]}
   "y" {:id "y" :deps []}})

(def multiple-missing-graph
  {"a" {:id "a" :deps ["gone1" "gone2"]}})

;------------------------------------------------------------------------------ dfs-find tests

(deftest dfs-find-basic-test
  (testing "find node d in diamond graph returns path"
    (let [result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path] (= id "d")))]
      (is (some? result))
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))
      (is (= "d" (last (:path result))))
      (is (= "a" (first (:path result))))))

  (testing "find start node itself"
    (let [result (algorithms/dfs-find single-node "a" get-deps
                                      (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] (:path result)))))

  (testing "node not found returns nil"
    (let [result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path] (= id "zzz")))]
      (is (nil? result)))))

(deftest dfs-find-edge-cases-test
  (testing "empty graph returns nil"
    (is (nil? (algorithms/dfs-find empty-graph "a" get-deps
                                   (fn [_id _node _path] true)))))

  (testing "single node found"
    (let [result (algorithms/dfs-find single-node "a" get-deps
                                      (fn [id _node _path] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result))))

  (testing "graph with cycle, target reachable from cycle path"
    (let [result (algorithms/dfs-find indirect-cycle "a" get-deps
                                      (fn [id _node _path] (= id "c")))]
      (is (= "c" (:found-id result)))))

  (testing "graph with cycle, target unreachable returns nil (no infinite loop)"
    (let [result (algorithms/dfs-find direct-cycle "a" get-deps
                                      (fn [id _node _path] (= id "zzz")))]
      (is (nil? result))))

  (testing "find in deep graph reaches the bottom"
    (let [result (algorithms/dfs-find deep-graph "n0" get-deps
                                      (fn [id _node _path] (= id "n9")))]
      (is (= "n9" (:found-id result)))
      (is (= 10 (count (:path result))))))

  (testing "find with missing deps does not error, returns nil if not found"
    (let [result (algorithms/dfs-find missing-dep-graph "a" get-deps
                                      (fn [id _node _path] (= id "missing")))]
      ;; "missing" is not in the graph, so on-visit is never called for it
      (is (nil? result)))))

(deftest dfs-find-stops-early-test
  (testing "stops early on first match without visiting remaining nodes"
    (let [visited-ids (atom [])
          ;; linear-chain: a -> b -> c -> d
          ;; DFS should visit a, then b, and stop when b matches
          result (algorithms/dfs-find linear-chain "a" get-deps
                                      (fn [id _node _path]
                                        (swap! visited-ids conj id)
                                        (= id "b")))]
      (is (= "b" (:found-id result)))
      (is (= ["a" "b"] @visited-ids)
          "Should stop after finding b, never visiting c or d")))

  (testing "stops early at start node when it matches"
    (let [visited-ids (atom [])
          result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path]
                                        (swap! visited-ids conj id)
                                        (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] @visited-ids)
          "Should stop immediately at start, never visiting children")))

  (testing "stops early in diamond graph, skips unreached branch"
    (let [visited-ids (atom [])
          ;; diamond: a -> [b, c], b -> d, c -> d
          ;; DFS visits a, then b (first dep). If b matches, c is never visited.
          result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path]
                                        (swap! visited-ids conj id)
                                        (= id "b")))]
      (is (= "b" (:found-id result)))
      (is (not (some #{"c"} @visited-ids))
          "Branch c should not be visited since b matched first"))))

(deftest dfs-find-complex-predicates-test
  (testing "predicate using node data"
    (let [graph {"x" {:id "x" :type :root :deps ["y" "z"]}
                 "y" {:id "y" :type :internal :deps ["w"]}
                 "z" {:id "z" :type :leaf :deps []}
                 "w" {:id "w" :type :leaf :deps []}}
          result (algorithms/dfs-find graph "x" get-deps
                                      (fn [_id node _path]
                                        (= :leaf (:type node))))]
      (is (some? result))
      (is (= :leaf (get-in graph [(:found-id result) :type])))))

  (testing "predicate using path depth"
    (let [result (algorithms/dfs-find linear-chain "a" get-deps
                                      (fn [_id _node path]
                                        ;; path is the ancestors; node at depth 2
                                        ;; means path has 2 elements
                                        (= 2 (count path))))]
      (is (some? result))
      (is (= "c" (:found-id result)))
      (is (= ["a" "b" "c"] (:path result)))))

  (testing "predicate combining node data and path"
    (let [graph {"root" {:id "root" :priority 1 :deps ["mid"]}
                 "mid"  {:id "mid"  :priority 5 :deps ["leaf"]}
                 "leaf" {:id "leaf" :priority 10 :deps []}}
          result (algorithms/dfs-find graph "root" get-deps
                                      (fn [_id node path]
                                        (and (> (:priority node) 3)
                                             (pos? (count path)))))]
      (is (= "mid" (:found-id result)))
      (is (= ["root" "mid"] (:path result)))))

  (testing "predicate that never matches returns nil"
    (let [result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-wide-graph-test
  (testing "find last child in wide graph"
    (let [result (algorithms/dfs-find wide-graph "root" get-deps
                                      (fn [id _node _path] (= id "c5")))]
      (is (= "c5" (:found-id result)))
      (is (= ["root" "c5"] (:path result)))))

  (testing "find first child in wide graph stops early"
    (let [visited (atom [])
          result (algorithms/dfs-find wide-graph "root" get-deps
                                      (fn [id _node _path]
                                        (swap! visited conj id)
                                        (= id "c1")))]
      (is (= "c1" (:found-id result)))
      (is (= ["root" "c1"] @visited)
          "Should not visit c2-c5"))))

;------------------------------------------------------------------------------ dfs-validate-graph tests

(deftest dfs-validate-graph-valid-test
  (testing "linear chain is valid"
    (let [result (algorithms/dfs-validate-graph
                   linear-chain (keys linear-chain) get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle"})))]
      (is (:valid? result))
      (is (= linear-chain (:graph result)))))

  (testing "diamond DAG is valid"
    (let [result (algorithms/dfs-validate-graph
                   diamond-dag (keys diamond-dag) get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle"})))]
      (is (:valid? result))))

  (testing "single node is valid"
    (let [result (algorithms/dfs-validate-graph
                   single-node ["a"] get-deps
                   (fn [_id _node _ctx] nil))]
      (is (:valid? result))))

  (testing "empty graph with empty start-nodes is valid"
    (let [result (algorithms/dfs-validate-graph
                   empty-graph [] get-deps
                   (fn [_id _node _ctx] nil))]
      (is (:valid? result))))

  (testing "valid result includes the original graph"
    (let [result (algorithms/dfs-validate-graph
                   diamond-dag (keys diamond-dag) get-deps
                   (fn [_id _node _ctx] nil))]
      (is (:valid? result))
      (is (= diamond-dag (:graph result))))))

(deftest dfs-validate-graph-cycle-test
  (testing "direct cycle detected via validate-fn"
    (let [result (algorithms/dfs-validate-graph
                   direct-cycle ["a"] get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle" :path (:path context)})))]
      (is (false? (:valid? result)))
      (is (= "cycle" (:error result)))
      (is (vector? (:path result)))))

  (testing "self-cycle detected"
    (let [result (algorithms/dfs-validate-graph
                   self-cycle ["a"] get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "self-cycle"})))]
      (is (false? (:valid? result)))))

  (testing "indirect cycle detected"
    (let [result (algorithms/dfs-validate-graph
                   indirect-cycle ["a"] get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "indirect-cycle"
                        :path (:path context)})))]
      (is (false? (:valid? result)))
      (is (= "indirect-cycle" (:error result))))))

(deftest dfs-validate-graph-missing-test
  (testing "missing dep detected via validate-fn"
    (let [result (algorithms/dfs-validate-graph
                   missing-dep-graph ["a"] get-deps
                   (fn [node-id _node context]
                     (when (:missing? context)
                       {:valid? false :error "missing" :node-id node-id})))]
      (is (false? (:valid? result)))
      (is (= "missing" (:error result)))
      (is (= "missing" (:node-id result)))))

  (testing "validate-fn receives node-id for missing nodes"
    (let [captured-id (atom nil)
          _ (algorithms/dfs-validate-graph
              missing-dep-graph ["a"] get-deps
              (fn [node-id _node context]
                (when (:missing? context)
                  (reset! captured-id node-id)
                  {:valid? false})))]
      (is (= "missing" @captured-id)))))

(deftest dfs-validate-graph-context-shape-test
  (testing "cycle context has :cycle? true and :path vector"
    (let [captured (atom nil)
          _ (algorithms/dfs-validate-graph
              direct-cycle ["a"] get-deps
              (fn [_id _node context]
                (when (:cycle? context)
                  (reset! captured context)
                  {:valid? false})))]
      (is (true? (:cycle? @captured)))
      (is (vector? (:path @captured)))))

  (testing "missing context has :missing? true"
    (let [captured (atom nil)
          _ (algorithms/dfs-validate-graph
              missing-dep-graph ["a"] get-deps
              (fn [_id _node context]
                (when (:missing? context)
                  (reset! captured context)
                  {:valid? false})))]
      (is (true? (:missing? @captured))))))

(deftest dfs-validate-graph-validate-fn-ignoring-errors-test
  (testing "validate-fn returning nil for cycles allows traversal to continue as valid"
    (let [result (algorithms/dfs-validate-graph
                   direct-cycle ["a"] get-deps
                   (fn [_id _node _context] nil))]
      (is (:valid? result))))

  (testing "validate-fn returning nil for missing allows traversal to continue as valid"
    (let [result (algorithms/dfs-validate-graph
                   missing-dep-graph ["a"] get-deps
                   (fn [_id _node _context] nil))]
      (is (:valid? result)))))

(deftest dfs-validate-graph-multiple-start-nodes-test
  (testing "validates across disconnected subgraphs"
    (let [graph (merge forest-graph {"z" {:id "z" :deps ["z"]}})
          result (algorithms/dfs-validate-graph
                   graph ["a" "x" "z"] get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle"})))]
      (is (false? (:valid? result))))))

;------------------------------------------------------------------------------ dfs-collect tests

(deftest dfs-collect-visit-test
  (testing "collect all node IDs on :visit in diamond graph"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (vector? result))
      (is (= #{"a" "b" "c" "d"} (set result)))
      (is (= 4 (count result))
          "d should not be duplicated")))

  (testing "empty graph returns empty vector"
    (let [result (algorithms/dfs-collect empty-graph [] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (= [] result))))

  (testing "collect visit order in linear chain is DFS order"
    (let [result (algorithms/dfs-collect linear-chain ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (= ["a" "b" "c" "d"] result))))

  (testing "collect node data on visit"
    (let [result (algorithms/dfs-collect single-node ["a"] get-deps
                                         (fn [id _path _v _vis]
                                           {:id id :collected true})
                                         :visit)]
      (is (= [{:id "a" :collected true}] result)))))

(deftest dfs-collect-cycle-test
  (testing "graph with cycle, collect-on :cycle returns cycle info"
    (let [result (algorithms/dfs-collect direct-cycle ["a"] get-deps
                                         (fn [id path _v _vis]
                                           {:cycle-node id :path path})
                                         :cycle)]
      (is (vector? result))
      (is (pos? (count result)))
      (is (= "a" (:cycle-node (first result))))))

  (testing "no cycles returns empty vector"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id path _v _vis]
                                           {:cycle-node id :path path})
                                         :cycle)]
      (is (= [] result))))

  (testing "self-cycle collected"
    (let [result (algorithms/dfs-collect self-cycle ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :cycle)]
      (is (= ["a"] result))))

  (testing "indirect cycle collected"
    (let [result (algorithms/dfs-collect indirect-cycle ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :cycle)]
      (is (= ["a"] result)
          "The cycle-forming node 'a' is detected when c tries to visit a"))))

(deftest dfs-collect-missing-test
  (testing "graph with missing deps, collect-on :missing"
    (let [result (algorithms/dfs-collect missing-dep-graph ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :missing)]
      (is (= ["missing"] result))))

  (testing "no missing returns empty vector"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :missing)]
      (is (= [] result))))

  (testing "multiple missing deps collected"
    (let [result (algorithms/dfs-collect multiple-missing-graph ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :missing)]
      (is (= #{"gone1" "gone2"} (set result)))
      (is (= 2 (count result))))))

(deftest dfs-collect-all-test
  (testing "collect-on :all collects visits, cycles, and missing"
    (let [result (algorithms/dfs-collect cycle-and-missing-graph ["a"] get-deps
                                         (fn [id _path _v _vis]
                                           {:id id})
                                         :all)]
      (is (vector? result))
      (let [ids (set (map :id result))]
        (is (contains? ids "a") "a should appear (visited and/or cycled)")
        (is (contains? ids "b") "b should appear (visited)")
        (is (contains? ids "c") "c should appear (visited)")
        (is (contains? ids "gone") "gone should appear (missing)"))))

  (testing "collect-on :all on clean graph returns only visits"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :all)]
      (is (= #{"a" "b" "c" "d"} (set result)))
      (is (= 4 (count result))))))

(deftest dfs-collect-nil-filtering-test
  (testing "collect-fn returning nil does not add to results"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis]
                                           (when (= id "d") id))
                                         :visit)]
      (is (= ["d"] result))))

  (testing "collect-fn returning nil for all nodes returns empty vector"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [_id _path _v _vis] nil)
                                         :visit)]
      (is (= [] result)))))

(deftest dfs-collect-multiple-start-nodes-test
  (testing "collecting from multiple start nodes in a forest"
    (let [result (algorithms/dfs-collect forest-graph ["a" "x"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (= #{"a" "b" "x" "y"} (set result)))
      (is (= 4 (count result)))))

  (testing "overlapping start nodes do not produce duplicates"
    (let [result (algorithms/dfs-collect diamond-dag ["a" "d"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      ;; d is reachable from a, so starting from d should not re-visit
      (is (= 4 (count result))))))

;------------------------------------------------------------------------------ dfs (raw) through interface

(deftest dfs-raw-through-interface-test
  (testing "dfs returns [visited result] tuple"
    (let [ret (algorithms/dfs diamond-dag ["a"] get-deps
                              (fn [_id _n _p _v _vi] nil)
                              (fn [_id _p _v _vi] nil)
                              (fn [_id _v _vi] nil))]
      (is (vector? ret))
      (is (= 2 (count ret)))
      (is (set? (first ret)))
      (is (nil? (second ret)))))

  (testing "dfs halts when on-visit returns non-nil"
    (let [[_ result] (algorithms/dfs linear-chain ["a"] get-deps
                                     (fn [id _n _p _v _vi]
                                       (when (= id "c") :halt))
                                     (fn [_id _p _v _vi] nil)
                                     (fn [_id _v _vi] nil))]
      (is (= :halt result)))))

;------------------------------------------------------------------------------ Interface delegation tests

(deftest interface-vars-exist-test
  (testing "all public API vars are functions"
    (is (fn? algorithms/dfs))
    (is (fn? algorithms/dfs-find))
    (is (fn? algorithms/dfs-validate-graph))
    (is (fn? algorithms/dfs-collect))))

(deftest interface-delegates-to-graph-test
  (testing "interface dfs-find produces same result as calling graph/dfs-find"
    (let [pred (fn [id _node _path] (= id "d"))
          result (algorithms/dfs-find diamond-dag "a" get-deps pred)]
      (is (= {:found-id "d" :path ["a" "b" "d"]} result))))

  (testing "interface dfs-validate-graph produces same result as graph/dfs-validate-graph"
    (let [result (algorithms/dfs-validate-graph
                   diamond-dag (keys diamond-dag) get-deps
                   (fn [_id _node ctx]
                     (when (:cycle? ctx)
                       {:valid? false :error "cycle"})))]
      (is (:valid? result)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run these tests:
  ;; bb test -- -n ai.miniforge.algorithms.interface-test

  :leave-this-here)
