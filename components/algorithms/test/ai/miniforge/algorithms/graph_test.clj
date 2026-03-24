;; Tests for ai.miniforge.algorithms.graph
;;
;; Covers: dfs, dfs-find, dfs-validate-graph, dfs-collect
;; Including edge cases: cycles, missing nodes, empty graphs, single nodes,
;; diamond dependencies, disconnected components, and custom reducers.

(ns ai.miniforge.algorithms.graph-test
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.miniforge.algorithms.graph :as sut]))

;; ---------------------------------------------------------------------------
;; Test fixtures / helper graphs
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

(def single-node-graph
  {"a" {:id "a" :deps []}})

(def missing-dep-graph
  {"a" {:id "a" :deps ["b" "missing"]}
   "b" {:id "b" :deps []}})

(defn get-deps [node] (:deps node))

(def noop-visit   (fn [_id _node _path _visited _visiting] nil))
(def noop-cycle   (fn [_id _path _visited _visiting] nil))
(def noop-missing (fn [_id _visited _visiting] nil))

;; ---------------------------------------------------------------------------
;; dfs - Core traversal
;; ---------------------------------------------------------------------------

(deftest dfs-linear-traversal-test
  (testing "traverses all nodes in a linear chain"
    (let [[visited result] (sut/dfs linear-graph ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c"} visited))
      (is (nil? result)))))

(deftest dfs-diamond-traversal-test
  (testing "traverses diamond graph visiting shared node only once"
    (let [[visited result] (sut/dfs diamond-graph ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

(deftest dfs-single-start-id-not-collection-test
  (testing "accepts a single start-id (not wrapped in a collection)"
    (let [[visited result] (sut/dfs linear-graph "a" get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c"} visited))
      (is (nil? result)))))

(deftest dfs-empty-graph-test
  (testing "handles empty graph with missing start node"
    (let [[visited result] (sut/dfs {} ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-empty-start-ids-test
  (testing "handles empty start-ids collection"
    (let [[visited result] (sut/dfs diamond-graph [] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-cycle-detection-test
  (testing "detects cycle and calls on-cycle-fn"
    (let [cycle-detected (atom nil)
          [_visited result]
          (sut/dfs cyclic-graph ["a"] get-deps
                   noop-visit
                   (fn [node-id path _visited _visiting]
                     (reset! cycle-detected {:node node-id :path path})
                     {:error :cycle :node node-id :path path})
                   noop-missing)]
      (is (some? result))
      (is (= :cycle (:error result)))
      (is (= "a" (:node result)))
      (is (some? @cycle-detected)))))

(deftest dfs-self-cycle-test
  (testing "detects self-referential cycle"
    (let [[_visited result]
          (sut/dfs self-cycle-graph ["a"] get-deps
                   noop-visit
                   (fn [node-id path _v _vis]
                     {:cycle-at node-id :path path})
                   noop-missing)]
      (is (= "a" (:cycle-at result)))
      (is (= ["a" "a"] (:path result))))))

(deftest dfs-missing-node-test
  (testing "calls on-missing-fn for nodes not in graph"
    (let [missing-nodes (atom [])
          [visited _result]
          (sut/dfs missing-dep-graph ["a"] get-deps
                   noop-visit
                   noop-cycle
                   (fn [node-id _v _vis]
                     (swap! missing-nodes conj node-id)
                     nil))]
      (is (contains? visited "a"))
      (is (contains? visited "b"))
      (is (= ["missing"] @missing-nodes)))))

(deftest dfs-missing-node-halts-test
  (testing "halts traversal when on-missing-fn returns non-nil"
    (let [[_visited result]
          (sut/dfs missing-dep-graph ["a"] get-deps
                   noop-visit
                   noop-cycle
                   (fn [node-id _v _vis]
                     {:error :missing :node node-id}))]
      (is (= {:error :missing :node "missing"} result)))))

(deftest dfs-on-visit-halts-test
  (testing "halts traversal when on-visit-fn returns non-nil"
    (let [[visited result]
          (sut/dfs diamond-graph ["a"] get-deps
                   (fn [node-id _node _path _v _vis]
                     (when (= node-id "b")
                       {:halt-at "b"}))
                   noop-cycle
                   noop-missing)]
      (is (= {:halt-at "b"} result))
      ;; "a" should not be in visited because it halted during processing a's deps
      ;; but "b" caused the halt so traversal stopped
      (is (not (contains? visited "d"))))))

(deftest dfs-multiple-start-nodes-test
  (testing "processes multiple disconnected start nodes"
    (let [[visited result]
          (sut/dfs disconnected-graph ["a" "x"] get-deps
                   noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "x" "y"} visited))
      (is (nil? result)))))

(deftest dfs-multiple-starts-halts-on-second-test
  (testing "halts during second start node processing"
    (let [[_visited result]
          (sut/dfs disconnected-graph ["a" "x"] get-deps
                   (fn [node-id _node _path _v _vis]
                     (when (= node-id "x")
                       {:found "x"}))
                   noop-cycle noop-missing)]
      (is (= {:found "x"} result)))))

(deftest dfs-already-visited-skipped-test
  (testing "nodes already visited from a previous start are skipped"
    (let [visit-order (atom [])
          [visited _result]
          (sut/dfs diamond-graph ["b" "a"] get-deps
                   (fn [node-id _node _path _v _vis]
                     (swap! visit-order conj node-id)
                     nil)
                   noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      ;; "b" and "d" visited in first pass, so only "a" and "c" in second
      (is (= 4 (count @visit-order)))
      ;; "d" should only appear once
      (is (= 1 (count (filter #(= "d" %) @visit-order)))))))

(deftest dfs-path-accumulation-test
  (testing "path accurately tracks traversal path"
    (let [paths (atom {})
          [_visited _result]
          (sut/dfs linear-graph ["a"] get-deps
                   (fn [node-id _node path _v _vis]
                     (swap! paths assoc node-id path)
                     nil)
                   noop-cycle noop-missing)]
      (is (= [] (get @paths "a")))
      (is (= ["a"] (get @paths "b")))
      (is (= ["a" "b"] (get @paths "c"))))))

;; ---------------------------------------------------------------------------
;; dfs-find
;; ---------------------------------------------------------------------------

(deftest dfs-find-existing-node-test
  (testing "finds a node matching the predicate"
    (let [result (sut/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path] (= id "d")))]
      (is (some? result))
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))
      (is (= "d" (last (:path result)))))))

(deftest dfs-find-start-node-test
  (testing "finds the start node itself if it matches"
    (let [result (sut/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] (:path result))))))

(deftest dfs-find-not-found-test
  (testing "returns nil when no node matches"
    (let [result (sut/dfs-find diamond-graph "a" get-deps
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-with-node-property-test
  (testing "finds node based on node properties"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :trust :verified}
                 "b" {:id "b" :deps [] :trust :verified}
                 "c" {:id "c" :deps [] :trust :tainted}}
          result (sut/dfs-find graph "a" get-deps
                               (fn [_id node _path] (= :tainted (:trust node))))]
      (is (= "c" (:found-id result))))))

(deftest dfs-find-empty-graph-test
  (testing "returns nil for missing start node"
    (let [result (sut/dfs-find {} "a" get-deps
                              (fn [_id _node _path] true))]
      (is (nil? result)))))

(deftest dfs-find-ignores-cycles-test
  (testing "does not crash or infinite-loop on cycles"
    (let [result (sut/dfs-find cyclic-graph "a" get-deps
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-path-correctness-test
  (testing "path reflects actual traversal path to found node"
    (let [result (sut/dfs-find linear-graph "a" get-deps
                              (fn [id _node _path] (= id "c")))]
      (is (= {:found-id "c" :path ["a" "b" "c"]} result)))))

;; ---------------------------------------------------------------------------
;; dfs-validate-graph
;; ---------------------------------------------------------------------------

(deftest dfs-validate-graph-valid-test
  (testing "returns valid for acyclic graph"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph) get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
      (is (true? (:valid? result)))
      (is (= diamond-graph (:graph result))))))

(deftest dfs-validate-graph-cycle-detected-test
  (testing "returns invalid for cyclic graph"
    (let [result (sut/dfs-validate-graph cyclic-graph (keys cyclic-graph) get-deps
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle" :node id})))]
      (is (false? (:valid? result)))
      (is (= "cycle" (:error result))))))

(deftest dfs-validate-graph-missing-dep-test
  (testing "detects missing dependencies"
    (let [result (sut/dfs-validate-graph missing-dep-graph ["a"] get-deps
                                        (fn [id _node ctx]
                                          (when (:missing? ctx)
                                            {:valid? false :error :missing-dep :node id})))]
      (is (false? (:valid? result)))
      (is (= "missing" (:node result))))))

(deftest dfs-validate-graph-no-validation-errors-test
  (testing "returns valid when validate-fn never returns errors"
    (let [result (sut/dfs-validate-graph cyclic-graph (keys cyclic-graph) get-deps
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-empty-test
  (testing "empty graph is valid"
    (let [result (sut/dfs-validate-graph {} [] get-deps
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result)))
      (is (= {} (:graph result))))))

(deftest dfs-validate-graph-self-cycle-test
  (testing "detects self-referential cycles"
    (let [result (sut/dfs-validate-graph self-cycle-graph ["a"] get-deps
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :self-cycle :node id})))]
      (is (false? (:valid? result)))
      (is (= "a" (:node result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect - pre-order (:visit / :pre)
;; ---------------------------------------------------------------------------

(deftest dfs-collect-pre-order-test
  (testing "collects node ids in pre-order (parent before children)"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= ["a" "b" "c"] result)))))

(deftest dfs-collect-pre-diamond-test
  (testing "collects diamond graph in pre-order, shared node visited once"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :pre)]
      (is (= 4 (count result)))
      (is (= "a" (first result)))
      ;; "d" appears exactly once
      (is (= 1 (count (filter #(= "d" %) result)))))))

(deftest dfs-collect-pre-alias-test
  (testing ":pre is an alias for :visit"
    (let [result-pre  (sut/dfs-collect diamond-graph ["a"] get-deps
                                      (fn [id _p _v _vis] id) :pre)
          result-visit (sut/dfs-collect diamond-graph ["a"] get-deps
                                       (fn [id _p _v _vis] id) :visit)]
      (is (= result-pre result-visit)))))

(deftest dfs-collect-nil-values-ignored-test
  (testing "nil return values from collect-fn are not accumulated"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _p _v _vis]
                                   (when (= id "d") "found-d"))
                                 :visit)]
      (is (= ["found-d"] result)))))

(deftest dfs-collect-empty-graph-test
  (testing "returns empty vector for empty graph"
    (let [result (sut/dfs-collect {} ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= [] result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect - post-order
;; ---------------------------------------------------------------------------

(deftest dfs-collect-post-order-test
  (testing "collects node ids in post-order (children before parents)"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= ["c" "b" "a"] result)))))

(deftest dfs-collect-post-diamond-test
  (testing "collects diamond graph in post-order"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= 4 (count result)))
      ;; "a" should be last (root), "d" should be first (leaf)
      (is (= "a" (last result)))
      (is (= "d" (first result))))))

(deftest dfs-collect-post-single-node-test
  (testing "post-order with a single node"
    (let [result (sut/dfs-collect single-node-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= ["a"] result)))))

(deftest dfs-collect-post-cycle-skipped-test
  (testing "post-order skips cyclic back-edges without hanging"
    (let [result (sut/dfs-collect cyclic-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      ;; All three nodes collected, cycle back-edge skipped
      (is (= 3 (count result)))
      (is (= #{"a" "b" "c"} (set result))))))

(deftest dfs-collect-post-missing-node-skipped-test
  (testing "post-order skips missing nodes"
    (let [result (sut/dfs-collect missing-dep-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= #{"a" "b"} (set result)))
      (is (not (some #(= "missing" %) result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect - :cycle events
;; ---------------------------------------------------------------------------

(deftest dfs-collect-cycles-test
  (testing "collects cycle information"
    (let [result (sut/dfs-collect cyclic-graph ["a"] get-deps
                                 (fn [id path _v _vis] {:node id :path path})
                                 :cycle)]
      (is (= 1 (count result)))
      (is (= "a" (:node (first result)))))))

(deftest dfs-collect-no-cycles-test
  (testing "returns empty when no cycles exist"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id path _v _vis] {:node id :path path})
                                 :cycle)]
      (is (= [] result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect - :missing events
;; ---------------------------------------------------------------------------

(deftest dfs-collect-missing-test
  (testing "collects missing node ids"
    (let [result (sut/dfs-collect missing-dep-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :missing)]
      (is (= ["missing"] result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect - :all events
;; ---------------------------------------------------------------------------

(deftest dfs-collect-all-events-test
  (testing "collects visits, cycles, and missing nodes"
    (let [graph {"a" {:id "a" :deps ["b" "c" "missing"]}
                 "b" {:id "b" :deps ["a"]}  ;; cycle
                 "c" {:id "c" :deps []}}
          result (sut/dfs-collect graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :all)]
      ;; Should collect visits for a, b, c + cycle for a + missing for "missing"
      (is (pos? (count result)))
      ;; a appears as visit and as cycle back-edge
      (is (some #(= "a" %) result))
      (is (some #(= "missing" %) result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect - custom reduce-fn and init
;; ---------------------------------------------------------------------------

(deftest dfs-collect-custom-reduce-count-test
  (testing "counts nodes with custom + reducer"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [_id _p _v _vis] 1)
                                 :pre + 0)]
      (is (= 4 result)))))

(deftest dfs-collect-custom-reduce-set-test
  (testing "collects into a set with custom conj/#{} reducer"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit conj #{})]
      (is (= #{"a" "b" "c" "d"} result)))))

(deftest dfs-collect-custom-reduce-string-test
  (testing "collects into a string with custom str reducer"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit str "")]
      (is (= "abc" result)))))

(deftest dfs-collect-post-custom-reduce-test
  (testing "post-order with custom reducer"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [_id _p _v _vis] 1)
                                 :post + 0)]
      (is (= 3 result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect - multiple start nodes
;; ---------------------------------------------------------------------------

(deftest dfs-collect-multiple-starts-test
  (testing "collects from disconnected components via multiple starts"
    (let [result (sut/dfs-collect disconnected-graph ["a" "x"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= #{"a" "b" "x" "y"} (set result)))
      (is (= 4 (count result))))))

(deftest dfs-collect-single-start-not-coll-test
  (testing "dfs-collect handles single start-id not wrapped in collection (via dfs)"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= ["a" "b" "c"] result)))))

;; ---------------------------------------------------------------------------
;; Edge case: single node graph
;; ---------------------------------------------------------------------------

(deftest dfs-single-node-test
  (testing "dfs on single node with no deps"
    (let [[visited result] (sut/dfs single-node-graph ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))
      (is (nil? result)))))

(deftest dfs-find-single-node-test
  (testing "dfs-find on single node"
    (let [result (sut/dfs-find single-node-graph "a" get-deps
                              (fn [id _node _path] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result)))))

;; ===========================================================================
;; Edge case tests: empty graph, single node, disconnected, self-loops,
;; diamond patterns, very deep graphs
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Edge case: Empty graph (no nodes)
;; ---------------------------------------------------------------------------

(deftest dfs-empty-graph-no-nodes-test
  (testing "empty graph with no start nodes returns empty visited set"
    (let [[visited result] (sut/dfs {} [] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-find-empty-graph-no-nodes-test
  (testing "dfs-find on completely empty graph returns nil"
    (let [result (sut/dfs-find {} "nonexistent" get-deps
                              (fn [_id _node _path] true))]
      (is (nil? result)))))

(deftest dfs-validate-empty-graph-no-nodes-test
  (testing "dfs-validate-graph with empty graph and no starts is valid"
    (let [result (sut/dfs-validate-graph {} [] get-deps
                                        (fn [_id _node ctx]
                                          (cond
                                            (:cycle? ctx) {:valid? false :error :cycle}
                                            (:missing? ctx) {:valid? false :error :missing})))]
      (is (true? (:valid? result)))
      (is (= {} (:graph result))))))

(deftest dfs-collect-empty-graph-no-nodes-test
  (testing "dfs-collect on empty graph with no starts returns empty"
    (let [result (sut/dfs-collect {} [] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= [] result)))))

(deftest dfs-collect-empty-graph-missing-start-test
  (testing "dfs-collect on empty graph with start id collects nothing (missing handler)"
    (let [result (sut/dfs-collect {} ["ghost"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= [] result)))))

(deftest dfs-collect-empty-graph-missing-event-test
  (testing "dfs-collect on empty graph with start id fires missing event"
    (let [result (sut/dfs-collect {} ["ghost"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :missing)]
      (is (= ["ghost"] result)))))

;; ---------------------------------------------------------------------------
;; Edge case: Single node, no edges
;; ---------------------------------------------------------------------------

(deftest dfs-single-node-no-edges-validate-test
  (testing "single node graph with no edges validates as valid"
    (let [result (sut/dfs-validate-graph single-node-graph ["a"] get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle})))]
      (is (true? (:valid? result))))))

(deftest dfs-single-node-no-edges-collect-test
  (testing "dfs-collect on single node collects exactly one node"
    (let [result (sut/dfs-collect single-node-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= ["a"] result)))))

(deftest dfs-single-node-no-edges-no-cycles-test
  (testing "single node with no edges produces no cycle events"
    (let [result (sut/dfs-collect single-node-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :cycle)]
      (is (= [] result)))))

(deftest dfs-single-node-no-edges-no-missing-test
  (testing "single node with no edges produces no missing events"
    (let [result (sut/dfs-collect single-node-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :missing)]
      (is (= [] result)))))

(deftest dfs-single-node-path-is-just-node-test
  (testing "path to a single node with no deps is just the node itself"
    (let [paths (atom {})
          [_visited _result]
          (sut/dfs single-node-graph ["a"] get-deps
                   (fn [node-id _node path _v _vis]
                     (swap! paths assoc node-id path)
                     nil)
                   noop-cycle noop-missing)]
      (is (= [] (get @paths "a"))))))

;; ---------------------------------------------------------------------------
;; Edge case: Disconnected components (only reachable nodes visited)
;; ---------------------------------------------------------------------------

(deftest dfs-disconnected-only-reachable-visited-test
  (testing "starting from one component does not visit the other component"
    (let [[visited result] (sut/dfs disconnected-graph ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b"} visited))
      (is (not (contains? visited "x")))
      (is (not (contains? visited "y")))
      (is (nil? result)))))

(deftest dfs-disconnected-second-component-only-test
  (testing "starting from second component does not visit the first"
    (let [[visited result] (sut/dfs disconnected-graph ["x"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"x" "y"} visited))
      (is (not (contains? visited "a")))
      (is (not (contains? visited "b")))
      (is (nil? result)))))

(deftest dfs-find-disconnected-only-reachable-test
  (testing "dfs-find from component A cannot find nodes in component B"
    (let [result (sut/dfs-find disconnected-graph "a" get-deps
                              (fn [id _node _path] (= id "x")))]
      (is (nil? result)))))

(deftest dfs-collect-disconnected-only-reachable-test
  (testing "dfs-collect starting from one component only collects reachable nodes"
    (let [result (sut/dfs-collect disconnected-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= #{"a" "b"} (set result)))
      (is (not (some #(= "x" %) result)))
      (is (not (some #(= "y" %) result))))))

(deftest dfs-validate-disconnected-partial-start-test
  (testing "validate from one component is valid even though other component is unreachable"
    (let [result (sut/dfs-validate-graph disconnected-graph ["a"] get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle})))]
      (is (true? (:valid? result))))))

(deftest dfs-disconnected-three-components-test
  (testing "three disconnected components, only two started, third is unreachable"
    (let [graph {"a" {:id "a" :deps ["b"]}
                 "b" {:id "b" :deps []}
                 "x" {:id "x" :deps ["y"]}
                 "y" {:id "y" :deps []}
                 "p" {:id "p" :deps ["q"]}
                 "q" {:id "q" :deps []}}
          [visited result] (sut/dfs graph ["a" "x"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "x" "y"} visited))
      (is (not (contains? visited "p")))
      (is (not (contains? visited "q")))
      (is (nil? result)))))

;; ---------------------------------------------------------------------------
;; Edge case: Self-loops
;; ---------------------------------------------------------------------------

(deftest dfs-self-loop-visit-still-happens-test
  (testing "node with self-loop is visited once, then cycle detected"
    (let [visit-count (atom 0)
          cycle-count (atom 0)
          [visited _result]
          (sut/dfs self-cycle-graph ["a"] get-deps
                   (fn [_id _node _path _v _vis]
                     (swap! visit-count inc)
                     nil)
                   (fn [_id _path _v _vis]
                     (swap! cycle-count inc)
                     nil)
                   noop-missing)]
      (is (= #{"a"} visited))
      (is (= 1 @visit-count))
      (is (= 1 @cycle-count)))))

(deftest dfs-self-loop-collect-visit-test
  (testing "dfs-collect on self-loop node collects one visit"
    (let [result (sut/dfs-collect self-cycle-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= ["a"] result)))))

(deftest dfs-self-loop-collect-cycle-test
  (testing "dfs-collect on self-loop node collects one cycle event"
    (let [result (sut/dfs-collect self-cycle-graph ["a"] get-deps
                                 (fn [id _p _v _vis] {:self-loop id})
                                 :cycle)]
      (is (= 1 (count result)))
      (is (= {:self-loop "a"} (first result))))))

(deftest dfs-self-loop-among-other-deps-test
  (testing "self-loop node that also has other dependencies"
    (let [graph {"a" {:id "a" :deps ["a" "b"]}
                 "b" {:id "b" :deps []}}
          cycle-nodes (atom [])
          [visited _result]
          (sut/dfs graph ["a"] get-deps
                   noop-visit
                   (fn [node-id _path _v _vis]
                     (swap! cycle-nodes conj node-id)
                     nil)
                   noop-missing)]
      (is (= #{"a" "b"} visited))
      (is (= ["a"] @cycle-nodes)))))

(deftest dfs-find-self-loop-still-finds-test
  (testing "dfs-find works correctly on node with self-loop"
    (let [result (sut/dfs-find self-cycle-graph "a" get-deps
                              (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result))))))

(deftest dfs-multiple-self-loops-test
  (testing "graph where multiple nodes have self-loops"
    (let [graph {"a" {:id "a" :deps ["a" "b"]}
                 "b" {:id "b" :deps ["b"]}}
          cycles (sut/dfs-collect graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :cycle)]
      (is (= 2 (count cycles)))
      (is (= #{"a" "b"} (set cycles))))))

;; ---------------------------------------------------------------------------
;; Edge case: Diamond dependency patterns
;; ---------------------------------------------------------------------------

(deftest dfs-diamond-shared-node-visited-once-test
  (testing "in a diamond, the shared leaf node is visited exactly once"
    (let [visit-count (atom 0)
          [visited _result]
          (sut/dfs diamond-graph ["a"] get-deps
                   (fn [node-id _node _path _v _vis]
                     (when (= node-id "d")
                       (swap! visit-count inc))
                     nil)
                   noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (= 1 @visit-count)))))

(deftest dfs-double-diamond-test
  (testing "double diamond: two shared merge points"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["d"]}
                 "c" {:id "c" :deps ["d"]}
                 "d" {:id "d" :deps ["e" "f"]}
                 "e" {:id "e" :deps ["g"]}
                 "f" {:id "f" :deps ["g"]}
                 "g" {:id "g" :deps []}}
          result (sut/dfs-collect graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      ;; All 7 nodes visited, each exactly once
      (is (= 7 (count result)))
      (is (= #{"a" "b" "c" "d" "e" "f" "g"} (set result))))))

(deftest dfs-diamond-post-order-d-before-a-test
  (testing "in diamond post-order, shared leaf d comes before root a"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (< (.indexOf result "d") (.indexOf result "a"))))))

(deftest dfs-diamond-find-shared-node-test
  (testing "dfs-find locates shared node in diamond via first branch"
    (let [result (sut/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path] (= id "d")))]
      (is (= "d" (:found-id result)))
      ;; Path should go through first branch (a -> b -> d)
      (is (= ["a" "b" "d"] (:path result))))))

(deftest dfs-diamond-validate-no-cycles-test
  (testing "diamond graph has no cycles despite shared deps"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph) get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle})))]
      (is (true? (:valid? result))))))

(deftest dfs-wide-diamond-test
  (testing "wide diamond: many branches converging to single node"
    (let [branches (mapv #(str "branch-" %) (range 10))
          graph (merge
                  {"root" {:id "root" :deps branches}
                   "leaf" {:id "leaf" :deps []}}
                  (into {} (map (fn [b] [b {:id b :deps ["leaf"]}]) branches)))
          result (sut/dfs-collect graph ["root"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      ;; root + 10 branches + leaf = 12
      (is (= 12 (count result)))
      (is (= 1 (count (filter #(= "leaf" %) result)))))))

;; ---------------------------------------------------------------------------
;; Edge case: Very deep graphs (stack behavior)
;; ---------------------------------------------------------------------------

(defn- build-deep-chain
  "Build a linear chain graph with n nodes: node-0 -> node-1 -> ... -> node-(n-1)."
  [n]
  (into {}
        (for [i (range n)]
          [(str "node-" i)
           {:id (str "node-" i)
            :deps (if (< i (dec n))
                    [(str "node-" (inc i))]
                    [])}])))

(deftest dfs-deep-graph-100-test
  (testing "DFS traverses a 100-node deep chain without issue"
    (let [graph (build-deep-chain 100)
          [visited result] (sut/dfs graph ["node-0"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= 100 (count visited)))
      (is (nil? result)))))

(deftest dfs-deep-graph-500-test
  (testing "DFS traverses a 500-node deep chain"
    (let [graph (build-deep-chain 500)
          [visited result] (sut/dfs graph ["node-0"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= 500 (count visited)))
      (is (nil? result)))))

(deftest dfs-deep-graph-collect-order-test
  (testing "DFS pre-order on deep chain visits nodes in order"
    (let [n 50
          graph (build-deep-chain n)
          result (sut/dfs-collect graph ["node-0"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)
          expected (mapv #(str "node-" %) (range n))]
      (is (= expected result)))))

(deftest dfs-deep-graph-post-order-test
  (testing "DFS post-order on deep chain visits leaves first"
    (let [n 50
          graph (build-deep-chain n)
          result (sut/dfs-collect graph ["node-0"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)
          expected (mapv #(str "node-" %) (reverse (range n)))]
      (is (= expected result)))))

(deftest dfs-deep-graph-find-leaf-test
  (testing "DFS find on deep chain locates the leaf node"
    (let [n 100
          graph (build-deep-chain n)
          leaf-id (str "node-" (dec n))
          result (sut/dfs-find graph "node-0" get-deps
                              (fn [id _node _path] (= id leaf-id)))]
      (is (= leaf-id (:found-id result)))
      ;; Path should include all nodes from root to leaf
      (is (= n (count (:path result))))
      (is (= "node-0" (first (:path result))))
      (is (= leaf-id (last (:path result)))))))

(deftest dfs-deep-graph-validate-no-cycles-test
  (testing "deep chain has no cycles"
    (let [graph (build-deep-chain 200)
          result (sut/dfs-validate-graph graph ["node-0"] get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle})))]
      (is (true? (:valid? result))))))

(deftest dfs-deep-graph-path-length-test
  (testing "path length grows correctly in deep chain"
    (let [n 30
          graph (build-deep-chain n)
          paths (atom {})
          [_visited _result]
          (sut/dfs graph ["node-0"] get-deps
                   (fn [node-id _node path _v _vis]
                     (swap! paths assoc node-id (count path))
                     nil)
                   noop-cycle noop-missing)]
      ;; Root has path length 0, each subsequent node increments by 1
      (is (= 0 (get @paths "node-0")))
      (is (= 1 (get @paths "node-1")))
      (is (= (dec n) (get @paths (str "node-" (dec n))))))))