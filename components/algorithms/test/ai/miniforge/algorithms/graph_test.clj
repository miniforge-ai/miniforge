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
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.algorithms.graph :as sut]))

;; ---------------------------------------------------------------------------
;; Test fixtures / graphs
;; ---------------------------------------------------------------------------

(def linear-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps []}})

(def diamond-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def cyclic-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a"]}})

(def self-cycle-graph
  {"a" {:id "a" :deps ["a"]}})

(def disconnected-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps []}
   "x" {:id "x" :deps ["y"]}
   "y" {:id "y" :deps []}})

(def missing-dep-graph
  {"a" {:id "a" :deps ["b" "ghost"]}
   "b" {:id "b" :deps []}})

(def single-node-graph
  {"a" {:id "a" :deps []}})

(def empty-graph {})

(def wide-graph
  {"root" {:id "root" :deps ["c1" "c2" "c3" "c4"]}
   "c1"   {:id "c1" :deps []}
   "c2"   {:id "c2" :deps []}
   "c3"   {:id "c3" :deps []}
   "c4"   {:id "c4" :deps []}})

(def deep-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps ["e"]}
   "e" {:id "e" :deps []}})

(defn deps-fn [node] (:deps node))

;; ---------------------------------------------------------------------------
;; Edge case graph fixtures
;; ---------------------------------------------------------------------------

(def self-loop-with-children-graph
  "Node that references itself AND has real children."
  {"a" {:id "a" :deps ["a" "b"]}
   "b" {:id "b" :deps []}})

(def multi-self-loop-graph
  "Multiple nodes with self-loops."
  {"a" {:id "a" :deps ["a" "b"]}
   "b" {:id "b" :deps ["b"]}})

(def double-diamond-graph
  "Two diamonds chained: a->b,c->d->e,f->g"
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps ["e" "f"]}
   "e" {:id "e" :deps ["g"]}
   "f" {:id "f" :deps ["g"]}
   "g" {:id "g" :deps []}})

(def multi-cycle-graph
  "Graph with two independent cycles."
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["a" "c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps ["c"]}})

(def fan-in-graph
  "Many nodes all pointing to a single leaf."
  {"a" {:id "a" :deps ["z"]}
   "b" {:id "b" :deps ["z"]}
   "c" {:id "c" :deps ["z"]}
   "d" {:id "d" :deps ["z"]}
   "z" {:id "z" :deps []}})

(def all-missing-deps-graph
  "Every dependency is missing from the graph."
  {"a" {:id "a" :deps ["ghost1" "ghost2"]}})

(def mixed-problems-graph
  "Graph with both cycles and missing nodes."
  {"a" {:id "a" :deps ["b" "missing1"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a" "missing2"]}})

(defn make-deep-chain
  "Build a linear chain graph of `n` nodes: n0 -> n1 -> ... -> n(n-1)."
  [n]
  (reduce (fn [m i]
            (let [id (str "n" i)
                  deps (if (< i (dec n)) [(str "n" (inc i))] [])]
              (assoc m id {:id id :deps deps})))
          {}
          (range n)))

;; ---------------------------------------------------------------------------
;; dfs — Core traversal
;; ---------------------------------------------------------------------------

(deftest dfs-simple-linear-traversal
  (testing "visits all nodes in a linear chain"
    (let [[visited _] (sut/dfs linear-graph
                               ["a"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c"} visited)))))

(deftest dfs-diamond-traversal
  (testing "visits all nodes in a diamond graph"
    (let [[visited _] (sut/dfs diamond-graph
                               ["a"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c" "d"} visited)))))

(deftest dfs-cycle-detection
  (testing "detects cycle and returns result"
    (let [[_visited result] (sut/dfs cyclic-graph
                                     ["a"]
                                     deps-fn
                                     (fn [_ _ _ _ _] nil)
                                     (fn [id path _ _]
                                       {:cycle-at id :path path})
                                     (fn [_ _ _] nil))]
      (is (some? result))
      (is (= "a" (:cycle-at result))))))

(deftest dfs-missing-node-detection
  (testing "detects missing nodes"
    (let [[_visited result] (sut/dfs missing-dep-graph
                                     ["a"]
                                     deps-fn
                                     (fn [_ _ _ _ _] nil)
                                     (fn [_ _ _ _] nil)
                                     (fn [id _ _] {:missing id}))]
      (is (= {:missing "ghost"} result)))))

(deftest dfs-on-visit-halt
  (testing "halts when on-visit returns non-nil"
    (let [[_ result] (sut/dfs diamond-graph
                              ["a"]
                              deps-fn
                              (fn [id _ _ _ _]
                                (when (= id "b") :found-b))
                              (fn [_ _ _ _] nil)
                              (fn [_ _ _] nil))]
      (is (= :found-b result)))))

(deftest dfs-single-id-not-collection
  (testing "accepts a single start-id, not just a collection"
    (let [[visited _] (sut/dfs linear-graph
                               "a"
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c"} visited)))))

(deftest dfs-self-loop-cycle
  (testing "self-loop is detected as cycle"
    (let [[_ result] (sut/dfs self-cycle-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [id path _ _]
                                {:self-loop id :path path})
                              (fn [_ _ _] nil))]
      (is (= "a" (:self-loop result))))))

(deftest dfs-disconnected-two-starts
  (testing "visits both components when started from both roots"
    (let [[visited _] (sut/dfs disconnected-graph
                               ["a" "x"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "x" "y"} visited)))))

(deftest dfs-empty-graph-empty-starts
  (testing "empty graph with empty start-ids returns empty visited"
    (let [[visited result] (sut/dfs empty-graph
                                    []
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-path-tracking
  (testing "path accumulates parent chain during traversal"
    (let [paths (atom [])
          [_ _] (sut/dfs linear-graph
                         ["a"]
                         deps-fn
                         (fn [id _node path _ _]
                           (swap! paths conj {:id id :path path})
                           nil)
                         (fn [_ _ _ _] nil)
                         (fn [_ _ _] nil))]
      (is (= [{:id "a" :path []}
              {:id "b" :path ["a"]}
              {:id "c" :path ["a" "b"]}]
             @paths)))))

(deftest dfs-start-node-not-in-graph
  (testing "starting from a node not in the graph triggers on-missing"
    (let [[visited result] (sut/dfs linear-graph
                                    ["nonexistent"]
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [id _ _] {:missing id}))]
      (is (= #{} visited))
      (is (= {:missing "nonexistent"} result)))))

(deftest dfs-start-node-not-in-graph-nil-on-missing
  (testing "starting from missing node with nil on-missing continues"
    (let [[visited result] (sut/dfs linear-graph
                                    ["nonexistent" "a"]
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c"} visited))
      (is (nil? result)))))

(deftest dfs-visited-shared-across-start-nodes
  (testing "visited set is shared across multiple start nodes"
    (let [visit-count (atom 0)
          [visited _] (sut/dfs diamond-graph
                               ["a" "b" "d"]
                               deps-fn
                               (fn [_ _ _ _ _]
                                 (swap! visit-count inc)
                                 nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c" "d"} visited))
      ;; b and d are already visited after processing "a", so only 4 visits total
      (is (= 4 @visit-count)))))

(deftest dfs-cycle-path-includes-cycle-node
  (testing "cycle path includes the cycle-forming node"
    (let [[_ result] (sut/dfs cyclic-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [id path _ _] {:id id :path path})
                              (fn [_ _ _] nil))]
      ;; path should be ["a" "b" "c" "a"] — full trail including the repeated node
      (is (= "a" (last (:path result))))
      (is (= "a" (first (:path result)))))))

(deftest dfs-halt-prevents-further-traversal
  (testing "halting on first visit prevents visiting deeper nodes"
    (let [visited-ids (atom #{})
          [_ result] (sut/dfs linear-graph
                              ["a"]
                              deps-fn
                              (fn [id _ _ _ _]
                                (swap! visited-ids conj id)
                                (when (= id "a") :halt-at-a))
                              (fn [_ _ _ _] nil)
                              (fn [_ _ _] nil))]
      (is (= :halt-at-a result))
      ;; Only "a" was visited before halt
      (is (= #{"a"} @visited-ids)))))

(deftest dfs-on-cycle-halt-stops-traversal
  (testing "halting from on-cycle prevents further traversal"
    (let [[_ result] (sut/dfs multi-cycle-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [id path _ _]
                                {:halted-cycle id :path path})
                              (fn [_ _ _] nil))]
      ;; Should halt at first cycle encountered
      (is (some? result))
      (is (contains? result :halted-cycle)))))

(deftest dfs-multiple-missing-deps-halts-at-first
  (testing "graph with all missing deps halts at first missing"
    (let [[_ result] (sut/dfs all-missing-deps-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [_ _ _ _] nil)
                              (fn [id _ _] {:missing id}))]
      ;; Halts at first missing dep
      (is (= {:missing "ghost1"} result)))))

(deftest dfs-fan-in-graph-visits-shared-node-once
  (testing "shared dependency is visited only once even with multiple parents"
    (let [visit-count (atom 0)
          [visited _] (sut/dfs fan-in-graph
                               ["a" "b" "c" "d"]
                               deps-fn
                               (fn [id _ _ _ _]
                                 (when (= id "z")
                                   (swap! visit-count inc))
                                 nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c" "d" "z"} visited))
      (is (= 1 @visit-count)))))

(deftest dfs-visiting-set-reset-between-start-nodes
  (testing "visiting set resets for each top-level start node"
    ;; If start nodes share structure but are independent starts,
    ;; the visiting set (cycle detection) should reset.
    ;; This means a node visited in start-1's subtree won't appear
    ;; as 'currently visiting' in start-2's subtree.
    (let [cycle-detected (atom false)
          [visited _] (sut/dfs linear-graph
                               ["a" "b"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _]
                                 (reset! cycle-detected true)
                                 nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c"} visited))
      (is (false? @cycle-detected)))))

(deftest dfs-returns-visited-even-on-halt
  (testing "visited set returned reflects nodes visited before halt"
    (let [[visited result] (sut/dfs deep-graph
                                    ["a"]
                                    deps-fn
                                    (fn [id _ _ _ _]
                                      (when (= id "c") :halt))
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= :halt result))
      ;; "a" visits "b" which visits "c" — halt. "a" and "b" not yet in visited
      ;; because they haven't finished their dep processing
      (is (not (contains? visited "d")))
      (is (not (contains? visited "e"))))))

;; ---------------------------------------------------------------------------
;; dfs-find
;; ---------------------------------------------------------------------------

(deftest dfs-find-in-linear-graph
  (testing "finds target in linear graph"
    (let [result (sut/dfs-find linear-graph "a" deps-fn
                              (fn [id _ _] (= id "c")))]
      (is (= {:found-id "c" :path ["a" "b" "c"]} result)))))

(deftest dfs-find-not-found
  (testing "returns nil when target not in graph"
    (let [result (sut/dfs-find linear-graph "a" deps-fn
                              (fn [id _ _] (= id "z")))]
      (is (nil? result)))))

(deftest dfs-find-root-matches
  (testing "finds the root node itself"
    (let [result (sut/dfs-find linear-graph "a" deps-fn
                              (fn [id _ _] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result)))))

(deftest dfs-find-diamond-first-path
  (testing "finds node via first path in diamond graph"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _ _] (= id "d")))]
      (is (= {:found-id "d" :path ["a" "b" "d"]} result)))))

(deftest dfs-find-with-cycle
  (testing "find works in cyclic graph without hanging"
    (let [result (sut/dfs-find cyclic-graph "a" deps-fn
                              (fn [id _ _] (= id "c")))]
      (is (= {:found-id "c" :path ["a" "b" "c"]} result)))))

(deftest dfs-find-with-missing-deps
  (testing "find works when graph has missing deps"
    (let [result (sut/dfs-find missing-dep-graph "a" deps-fn
                              (fn [id _ _] (= id "b")))]
      (is (= {:found-id "b" :path ["a" "b"]} result)))))

(deftest dfs-find-in-empty-graph
  (testing "find in empty graph from nonexistent start returns nil"
    (let [result (sut/dfs-find empty-graph "a" deps-fn
                              (fn [_ _ _] true))]
      (is (nil? result)))))

(deftest dfs-find-in-single-node-graph
  (testing "find single node when it matches"
    (let [result (sut/dfs-find single-node-graph "a" deps-fn
                              (fn [id _ _] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result)))))

(deftest dfs-find-in-single-node-no-match
  (testing "find returns nil when single node doesn't match"
    (let [result (sut/dfs-find single-node-graph "a" deps-fn
                              (fn [id _ _] (= id "z")))]
      (is (nil? result)))))

(deftest dfs-find-predicate-uses-node-data
  (testing "predicate can inspect node data"
    (let [graph {"a" {:id "a" :deps ["b"] :role :admin}
                 "b" {:id "b" :deps [] :role :user}}
          result (sut/dfs-find graph "a"
                              (fn [node] (:deps node))
                              (fn [_id node _path] (= :user (:role node))))]
      (is (= {:found-id "b" :path ["a" "b"]} result)))))

(deftest dfs-find-predicate-uses-path
  (testing "predicate can use path for depth-based search"
    (let [result (sut/dfs-find deep-graph "a" deps-fn
                              (fn [_id _node path] (= 3 (count path))))]
      ;; path has 3 elements means we're at depth 3: ["a" "b" "c"]
      ;; and we're visiting "d"
      (is (= "d" (:found-id result))))))

(deftest dfs-find-cycle-does-not-infinite-loop
  (testing "find with target not reachable in cyclic graph returns nil"
    (let [result (sut/dfs-find cyclic-graph "a" deps-fn
                              (fn [id _ _] (= id "nonexistent")))]
      (is (nil? result)))))

(deftest dfs-find-self-loop-graph
  (testing "find works with self-looping nodes"
    (let [result (sut/dfs-find self-loop-with-children-graph "a" deps-fn
                              (fn [id _ _] (= id "b")))]
      (is (= {:found-id "b" :path ["a" "b"]} result)))))

;; ---------------------------------------------------------------------------
;; dfs-validate-graph
;; ---------------------------------------------------------------------------

(defn cycle-validator [_id _node context]
  (when (:cycle? context)
    {:valid? false :error "Cycle detected" :path (:path context)}))

(defn missing-validator [id _node context]
  (when (:missing? context)
    {:valid? false :error (str "Missing node: " id)}))

(defn strict-validator [id _node context]
  (or (cycle-validator id nil context)
      (missing-validator id nil context)))

(deftest dfs-validate-acyclic-graph
  (testing "acyclic graph passes validation"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph)
                                        deps-fn cycle-validator)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-cyclic-graph
  (testing "cyclic graph fails validation"
    (let [result (sut/dfs-validate-graph cyclic-graph (keys cyclic-graph)
                                        deps-fn cycle-validator)]
      (is (false? (:valid? result)))
      (is (= "Cycle detected" (:error result))))))

(deftest dfs-validate-missing-node
  (testing "missing dependency fails validation"
    (let [result (sut/dfs-validate-graph missing-dep-graph ["a"]
                                        deps-fn missing-validator)]
      (is (false? (:valid? result)))
      (is (= "Missing node: ghost" (:error result))))))

(deftest dfs-validate-empty-graph
  (testing "empty graph with no start nodes is valid"
    (let [result (sut/dfs-validate-graph empty-graph []
                                        deps-fn strict-validator)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-self-loop-detected
  (testing "self-loop is caught as cycle"
    (let [result (sut/dfs-validate-graph self-cycle-graph ["a"]
                                        deps-fn cycle-validator)]
      (is (false? (:valid? result))))))

(deftest dfs-validate-disconnected-valid
  (testing "disconnected acyclic graph is valid"
    (let [result (sut/dfs-validate-graph disconnected-graph ["a" "x"]
                                        deps-fn strict-validator)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-returns-graph-on-valid
  (testing "valid result includes the original graph"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph)
                                        deps-fn strict-validator)]
      (is (= diamond-graph (:graph result))))))

(deftest dfs-validate-strict-catches-cycle-and-missing
  (testing "strict validator catches first problem in mixed graph"
    (let [result (sut/dfs-validate-graph mixed-problems-graph ["a"]
                                        deps-fn strict-validator)]
      (is (false? (:valid? result))))))

(deftest dfs-validate-noop-validator-always-valid
  (testing "validator that always returns nil makes any graph valid"
    (let [noop-validator (fn [_ _ _] nil)
          result (sut/dfs-validate-graph cyclic-graph (keys cyclic-graph)
                                        deps-fn noop-validator)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-cycle-path-is-provided
  (testing "cycle validator receives the path to the cycle"
    (let [result (sut/dfs-validate-graph cyclic-graph ["a"]
                                        deps-fn cycle-validator)]
      (is (vector? (:path result)))
      (is (pos? (count (:path result)))))))

(deftest dfs-validate-single-node-no-deps
  (testing "single node with no deps is valid"
    (let [result (sut/dfs-validate-graph single-node-graph ["a"]
                                        deps-fn strict-validator)]
      (is (true? (:valid? result))))))

(deftest dfs-validate-fan-in-valid
  (testing "fan-in graph (many parents, one child) is valid"
    (let [result (sut/dfs-validate-graph fan-in-graph (keys fan-in-graph)
                                        deps-fn strict-validator)]
      (is (true? (:valid? result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect — :visit mode
;; ---------------------------------------------------------------------------

(deftest dfs-collect-visit-pre-order
  (testing "collects visited node ids in pre-order"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (vector? result))
      ;; "a" is visited first
      (is (= "a" (first result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

(deftest dfs-collect-visit-nil-values-excluded
  (testing "nil return values from collect-fn are not accumulated"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis]
                                   (when (= id "d") "found-d"))
                                 :visit)]
      (is (= ["found-d"] result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect — :cycle mode
;; ---------------------------------------------------------------------------

(deftest dfs-collect-cycle-events
  (testing "collects cycle events when :cycle mode is used"
    (let [result (sut/dfs-collect cyclic-graph ["a"] deps-fn
                                 (fn [id path _v _vis]
                                   {:cycle-at id :path path})
                                 :cycle)]
      (is (= 1 (count result)))
      (is (= "a" (:cycle-at (first result)))))))

(deftest dfs-collect-cycle-on-acyclic-graph
  (testing "no cycle events on acyclic graph"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :cycle)]
      (is (empty? result)))))

(deftest dfs-collect-multi-cycle-graph
  (testing "collects cycle event from graph with multiple cycles"
    (let [result (sut/dfs-collect multi-cycle-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :cycle)]
      ;; At minimum one cycle should be detected
      (is (pos? (count result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect — :missing mode
;; ---------------------------------------------------------------------------

(deftest dfs-collect-missing-events
  (testing "collects missing node events"
    (let [result (sut/dfs-collect missing-dep-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :missing)]
      (is (= ["ghost"] result)))))

(deftest dfs-collect-no-missing-on-complete-graph
  (testing "no missing events on a complete graph"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :missing)]
      (is (empty? result)))))

(deftest dfs-collect-all-missing-deps
  (testing "collects all missing deps when none halt traversal"
    (let [result (sut/dfs-collect all-missing-deps-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :missing)]
      (is (= #{"ghost1" "ghost2"} (set result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect — :all mode
;; ---------------------------------------------------------------------------

(deftest dfs-collect-all-events
  (testing ":all collects visit, cycle, and missing events"
    (let [graph {"a" {:id "a" :deps ["b" "c" "ghost"]}
                 "b" {:id "b" :deps ["a"]}  ;; cycle back to a
                 "c" {:id "c" :deps []}}
          result (sut/dfs-collect graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :all)]
      ;; Should have visits for a, b, c + cycle for a + missing for ghost
      (is (>= (count result) 4))
      ;; "a" appears at least twice (visit + cycle)
      (is (>= (count (filter #(= "a" %) result)) 2)))))

(deftest dfs-collect-all-on-clean-graph
  (testing ":all on clean acyclic graph only collects visit events"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :all)]
      (is (= #{"a" "b" "c" "d"} (set result)))
      (is (= 4 (count result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect — Edge cases
;; ---------------------------------------------------------------------------

(deftest dfs-collect-empty-graph
  (testing "collecting from empty graph returns empty vector"
    (let [result (sut/dfs-collect empty-graph [] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= [] result)))))

(deftest dfs-collect-single-node
  (testing "collecting from single node graph"
    (let [result (sut/dfs-collect single-node-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= ["a"] result)))))

(deftest dfs-collect-multiple-start-ids
  (testing "collects across multiple disconnected components"
    (let [result (sut/dfs-collect disconnected-graph ["a" "x"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= #{"a" "b" "x" "y"} (set result)))
      (is (= 4 (count result))))))

(deftest dfs-collect-wide-graph
  (testing "collects all children of a wide graph"
    (let [result (sut/dfs-collect wide-graph ["root"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= #{"root" "c1" "c2" "c3" "c4"} (set result))))))

(deftest dfs-collect-disconnected-single-start-only-reachable
  (testing "collect from one component does not include the other"
    (let [result (sut/dfs-collect disconnected-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= ["a" "b"] result))
      (is (not (contains? (set result) "x")))
      (is (not (contains? (set result) "y"))))))

(deftest dfs-collect-self-loop-cycle-events
  (testing "self-loop generates exactly one cycle event"
    (let [result (sut/dfs-collect self-cycle-graph ["a"] deps-fn
                                 (fn [id _path _v _vis]
                                   {:cycle-node id})
                                 :cycle)]
      (is (= 1 (count result)))
      (is (= {:cycle-node "a"} (first result))))))

(deftest dfs-collect-self-loop-visit-still-collects
  (testing "self-loop node is still collected as a visit"
    (let [result (sut/dfs-collect self-cycle-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= ["a"] result)))))

(deftest dfs-collect-multi-self-loop-cycles
  (testing "multiple self-loop nodes each generate cycle events"
    (let [result (sut/dfs-collect multi-self-loop-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :cycle)]
      (is (= #{"a" "b"} (set result)))
      (is (= 2 (count result))))))

(deftest dfs-collect-double-diamond-visit-order
  (testing "double-diamond collects all 7 nodes in visit mode"
    (let [result (sut/dfs-collect double-diamond-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= 7 (count result)))
      (is (= #{"a" "b" "c" "d" "e" "f" "g"} (set result)))
      ;; root is visited first
      (is (= "a" (first result))))))

(deftest dfs-collect-deep-chain-visit-order
  (testing "deep chain collects in order n0, n1, ..., n99"
    (let [graph (make-deep-chain 100)
          result (sut/dfs-collect graph ["n0"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= 100 (count result)))
      (is (= "n0" (first result)))
      (is (= "n99" (last result))))))

(deftest dfs-collect-duplicate-start-ids
  (testing "duplicate start IDs don't cause duplicate visits"
    (let [result (sut/dfs-collect single-node-graph ["a" "a" "a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :visit)]
      (is (= ["a"] result)))))

(deftest dfs-collect-collect-fn-receives-path
  (testing "collect-fn receives accurate path information"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id path _v _vis]
                                   {:id id :depth (count path)})
                                 :visit)]
      (is (= [{:id "a" :depth 0}
              {:id "b" :depth 1}
              {:id "c" :depth 2}]
             result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect — :pre mode (alias for :visit)
;; ---------------------------------------------------------------------------

(deftest dfs-collect-pre-mode-collects-matching-nodes
  (testing ":pre collects nodes matching criteria (same as :visit)"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :pre)]
      (is (vector? result))
      (is (= "a" (first result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

(deftest dfs-collect-pre-mode-linear-graph-order
  (testing ":pre mode visits nodes in pre-order (parent before children)"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :pre)]
      (is (= ["a" "b" "c"] result)))))

(deftest dfs-collect-pre-mode-diamond-pre-order
  (testing ":pre mode on diamond graph matches :visit exactly"
    (let [pre-result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                     (fn [id _path _v _vis] id)
                                     :pre)
          visit-result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                       (fn [id _path _v _vis] id)
                                       :visit)]
      (is (= pre-result visit-result)))))

(deftest dfs-collect-pre-mode-nil-values-excluded
  (testing ":pre mode excludes nil return values from collect-fn"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis]
                                   (when (not= id "a") id))
                                 :pre)]
      (is (= #{"b" "c" "d"} (set result)))
      (is (not (contains? (set result) "a"))))))

(deftest dfs-collect-pre-mode-empty-graph
  (testing ":pre mode on empty graph returns empty vector"
    (let [result (sut/dfs-collect empty-graph [] deps-fn
                                 (fn [id _ _ _] id)
                                 :pre)]
      (is (= [] result)))))

(deftest dfs-collect-pre-mode-single-node
  (testing ":pre mode on single node graph"
    (let [result (sut/dfs-collect single-node-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :pre)]
      (is (= ["a"] result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect — :post mode (post-order traversal)
;; ---------------------------------------------------------------------------

(deftest dfs-collect-post-mode-linear-graph
  (testing ":post collects leaves before parents in linear graph"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= ["c" "b" "a"] result)))))

(deftest dfs-collect-post-mode-diamond-graph
  (testing ":post collects children before parents in diamond graph"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      ;; d must come before b and c, all must come before a
      (is (= #{"a" "b" "c" "d"} (set result)))
      (is (= "a" (last result)))
      (let [idx (into {} (map-indexed (fn [i v] [v i]) result))]
        (is (< (idx "d") (idx "b")) "d before b")
        (is (< (idx "d") (idx "c")) "d before c (via post-order, d visited via b first)")
        (is (< (idx "b") (idx "a")) "b before a")
        (is (< (idx "c") (idx "a")) "c before a")))))

(deftest dfs-collect-post-mode-single-node
  (testing ":post on single node graph"
    (let [result (sut/dfs-collect single-node-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      (is (= ["a"] result)))))

(deftest dfs-collect-post-mode-empty-graph
  (testing ":post on empty graph returns empty vector"
    (let [result (sut/dfs-collect empty-graph [] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      (is (= [] result)))))

(deftest dfs-collect-post-mode-wide-graph
  (testing ":post on wide graph collects all children before root"
    (let [result (sut/dfs-collect wide-graph ["root"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      (is (= "root" (last result)))
      (is (= #{"root" "c1" "c2" "c3" "c4"} (set result))))))

(deftest dfs-collect-post-mode-deep-chain
  (testing ":post on deep chain is reverse of :pre"
    (let [result (sut/dfs-collect deep-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      (is (= ["e" "d" "c" "b" "a"] result)))))

(deftest dfs-collect-post-mode-nil-values-excluded
  (testing ":post mode excludes nil values from collect-fn"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis]
                                   (when (not= id "d") id))
                                 :post)]
      (is (not (contains? (set result) "d")))
      (is (= #{"a" "b" "c"} (set result))))))

(deftest dfs-collect-post-mode-skips-cycles
  (testing ":post mode handles cycles without infinite loop"
    (let [result (sut/dfs-collect cyclic-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      ;; All nodes visited, cycle back-edge is simply skipped
      (is (= #{"a" "b" "c"} (set result)))
      ;; Post-order: c before b before a
      (is (= ["c" "b" "a"] result)))))

(deftest dfs-collect-post-mode-skips-missing
  (testing ":post mode skips missing nodes"
    (let [result (sut/dfs-collect missing-dep-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      ;; "ghost" is missing so not collected; b and a are collected post-order
      (is (= #{"a" "b"} (set result)))
      (is (not (contains? (set result) "ghost"))))))

(deftest dfs-collect-post-mode-disconnected-multiple-starts
  (testing ":post mode works across disconnected components"
    (let [result (sut/dfs-collect disconnected-graph ["a" "x"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      (is (= #{"a" "b" "x" "y"} (set result)))
      (is (= 4 (count result))))))

(deftest dfs-collect-post-vs-pre-different-order
  (testing ":post and :pre produce different orderings"
    (let [pre-result  (sut/dfs-collect linear-graph ["a"] deps-fn
                                      (fn [id _ _ _] id)
                                      :pre)
          post-result (sut/dfs-collect linear-graph ["a"] deps-fn
                                      (fn [id _ _ _] id)
                                      :post)]
      (is (= (reverse pre-result) post-result)))))

(deftest dfs-collect-post-mode-double-diamond
  (testing ":post on double-diamond respects dependency ordering"
    (let [result (sut/dfs-collect double-diamond-graph ["a"] deps-fn
                                 (fn [id _ _ _] id)
                                 :post)]
      (is (= 7 (count result)))
      (is (= "a" (last result)))
      (let [idx (into {} (map-indexed (fn [i v] [v i]) result))]
        ;; g must come before e and f
        (is (< (idx "g") (idx "e")))
        (is (< (idx "g") (idx "f")))
        ;; d must come before a
        (is (< (idx "d") (idx "a")))))))

;; ---------------------------------------------------------------------------
;; dfs-collect — Custom reduce function
;; ---------------------------------------------------------------------------

(deftest dfs-collect-custom-reduce-into-set
  (testing "custom reduce with conj into a set"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :pre conj #{})]
      (is (set? result))
      (is (= #{"a" "b" "c" "d"} result)))))

(deftest dfs-collect-custom-reduce-counting
  (testing "custom reduce that counts visited nodes"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [_id _path _v _vis] 1)
                                 :pre + 0)]
      (is (= 4 result)))))

(deftest dfs-collect-custom-reduce-string-concat
  (testing "custom reduce that concatenates node ids"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :pre str "")]
      (is (= "abc" result)))))

(deftest dfs-collect-custom-reduce-max-depth
  (testing "custom reduce to find maximum depth"
    (let [result (sut/dfs-collect deep-graph ["a"] deps-fn
                                 (fn [_id path _v _vis] (count path))
                                 :pre max 0)]
      ;; a has path len 0, b has 1, c has 2, d has 3, e has 4
      (is (= 4 result)))))

(deftest dfs-collect-custom-reduce-into-map
  (testing "custom reduce that builds a map of node-id to depth"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id path _v _vis] [id (count path)])
                                 :pre
                                 (fn [acc [k v]] (assoc acc k v))
                                 {})]
      (is (= {"a" 0 "b" 1 "c" 2} result)))))

(deftest dfs-collect-custom-reduce-empty-graph
  (testing "custom reduce on empty graph returns init value"
    (let [result (sut/dfs-collect empty-graph [] deps-fn
                                 (fn [id _ _ _] id)
                                 :pre + 0)]
      (is (= 0 result)))))

(deftest dfs-collect-custom-reduce-with-post-mode
  (testing "custom reduce works with :post mode"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post conj #{})]
      (is (set? result))
      (is (= #{"a" "b" "c"} result)))))

(deftest dfs-collect-custom-reduce-post-mode-counting
  (testing "custom reduce counting with :post mode"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [_id _path _v _vis] 1)
                                 :post + 0)]
      (is (= 4 result)))))

(deftest dfs-collect-default-arity-matches-explicit
  (testing "5-arity defaults to conj and [] (same as explicit 7-arity)"
    (let [default-result  (sut/dfs-collect diamond-graph ["a"] deps-fn
                                          (fn [id _ _ _] id)
                                          :visit)
          explicit-result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                          (fn [id _ _ _] id)
                                          :visit conj [])]
      (is (= default-result explicit-result)))))

(deftest dfs-collect-custom-reduce-cycle-counting
  (testing "custom reduce counting cycles"
    (let [result (sut/dfs-collect multi-cycle-graph ["a"] deps-fn
                                 (fn [_id _path _v _vis] 1)
                                 :cycle + 0)]
      (is (pos? result)))))

(deftest dfs-collect-custom-reduce-post-string-concat
  (testing "post-order string concatenation produces reverse order"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post str "")]
      (is (= "cba" result)))))

;; ---------------------------------------------------------------------------
;; Integration-style: rich comment examples as regression tests
;; ---------------------------------------------------------------------------

(deftest rich-comment-find-example
  (testing "example from rich comment block: find node d"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["d"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps []}}
          result (sut/dfs-find graph "a" deps-fn
                              (fn [id _node _path] (= id "d")))]
      (is (= {:found-id "d" :path ["a" "b" "d"]} result)))))

(deftest rich-comment-validate-example
  (testing "example from rich comment block: validate acyclic graph"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["d"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps []}}
          result (sut/dfs-validate-graph graph (keys graph) deps-fn
                                        (fn [_id _node context]
                                          (when (:cycle? context)
                                            {:valid? false :error "Cycle detected"})))]
      (is (= {:valid? true :graph graph} result)))))

(deftest rich-comment-collect-visit-example
  (testing "example from rich comment block: collect visited nodes"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["d"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps []}}
          result (sut/dfs-collect graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      ;; Exact order from comment: ["a" "b" "d" "c"]
      (is (= ["a" "b" "d" "c"] result)))))

(deftest rich-comment-collect-post-example
  (testing "example from rich comment block: collect post-order"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["d"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps []}}
          result (sut/dfs-collect graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= ["d" "b" "c" "a"] result)))))

(deftest rich-comment-collect-into-set-example
  (testing "example from rich comment block: collect into set"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["d"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps []}}
          result (sut/dfs-collect graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :pre conj #{})]
      (is (= #{"a" "b" "c" "d"} result)))))

;; ---------------------------------------------------------------------------
;; Invariant / property-style tests
;; ---------------------------------------------------------------------------

(deftest invariant-visited-count-equals-reachable-nodes
  (testing "visited set size equals number of reachable nodes"
    (doseq [[label graph starts expected-count]
            [["linear" linear-graph ["a"] 3]
             ["diamond" diamond-graph ["a"] 4]
             ["single" single-node-graph ["a"] 1]
             ["wide" wide-graph ["root"] 5]
             ["deep" deep-graph ["a"] 5]
             ["disconnected-both" disconnected-graph ["a" "x"] 4]
             ["disconnected-one" disconnected-graph ["a"] 2]]]
      (testing label
        (let [[visited _] (sut/dfs graph starts deps-fn
                                   (fn [_ _ _ _ _] nil)
                                   (fn [_ _ _ _] nil)
                                   (fn [_ _ _] nil))]
          (is (= expected-count (count visited))))))))

(deftest invariant-pre-and-post-collect-same-set
  (testing ":pre and :post collect the same set of nodes on acyclic graphs"
    (doseq [[label graph starts]
            [["linear" linear-graph ["a"]]
             ["diamond" diamond-graph ["a"]]
             ["wide" wide-graph ["root"]]
             ["deep" deep-graph ["a"]]
             ["double-diamond" double-diamond-graph ["a"]]]]
      (testing label
        (let [pre-result  (sut/dfs-collect graph starts deps-fn
                                          (fn [id _ _ _] id) :pre)
              post-result (sut/dfs-collect graph starts deps-fn
                                          (fn [id _ _ _] id) :post)]
          (is (= (set pre-result) (set post-result))))))))

(deftest invariant-post-order-children-before-parents
  (testing "in post-order, every dependency appears before its parent"
    (doseq [[label graph starts]
            [["linear" linear-graph ["a"]]
             ["diamond" diamond-graph ["a"]]
             ["deep" deep-graph ["a"]]
             ["double-diamond" double-diamond-graph ["a"]]]]
      (testing label
        (let [result (sut/dfs-collect graph starts deps-fn
                                     (fn [id _ _ _] id) :post)
              idx (into {} (map-indexed (fn [i v] [v i]) result))]
          (doseq [[node-id node] graph
                  :when (contains? idx node-id)
                  dep (deps-fn node)
                  :when (contains? idx dep)]
            (is (< (idx dep) (idx node-id))
                (str dep " should appear before " node-id " in post-order"))))))))

(deftest invariant-dfs-find-path-is-valid
  (testing "the path returned by dfs-find represents a valid walk in the graph"
    (let [graph double-diamond-graph
          result (sut/dfs-find graph "a" deps-fn
                              (fn [id _ _] (= id "g")))]
      (is (some? result))
      ;; Each consecutive pair in path should be parent -> child
      (let [path (:path result)]
        (doseq [[parent child] (partition 2 1 path)]
          (let [parent-node (get graph parent)]
            (is (some? parent-node)
                (str "parent " parent " should exist in graph"))
            (is (some #{child} (deps-fn parent-node))
                (str child " should be a dep of " parent))))))))

(deftest invariant-collect-visit-count-matches-visited-set
  (testing "number of :visit collected items matches visited set size"
    (doseq [[label graph starts]
            [["diamond" diamond-graph ["a"]]
             ["wide" wide-graph ["root"]]
             ["disconnected" disconnected-graph ["a" "x"]]]]
      (testing label
        (let [[visited _] (sut/dfs graph starts deps-fn
                                   (fn [_ _ _ _ _] nil)
                                   (fn [_ _ _ _] nil)
                                   (fn [_ _ _] nil))
              collected (sut/dfs-collect graph starts deps-fn
                                        (fn [id _ _ _] id) :visit)]
          (is (= (count visited) (count collected))))))))
