;; Tests for ai.miniforge.algorithms.graph
;;
;; Covers: dfs, dfs-find, dfs-validate-graph, dfs-collect, collect-on-matches?

(ns ai.miniforge.algorithms.graph-test
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.miniforge.algorithms.graph :as sut]))

;; ---------------------------------------------------------------------------
;; Test fixtures / helper graphs
;; ---------------------------------------------------------------------------

(def linear-graph
  "A -> B -> C -> D (no cycles, no branches)"
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def diamond-graph
  "A -> B, A -> C, B -> D, C -> D (diamond / shared dependency)"
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def cyclic-graph
  "A -> B -> C -> A (simple cycle)"
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a"]}})

(def self-loop-graph
  "A -> A (self-referential cycle)"
  {"a" {:id "a" :deps ["a"]}})

(def disconnected-graph
  "Two disconnected components: A->B and C->D"
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps []}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def missing-dep-graph
  "A depends on B, but B is not in the graph"
  {"a" {:id "a" :deps ["b"]}})

(def empty-graph {})

(def single-node-graph
  {"a" {:id "a" :deps []}})

(def wide-graph
  "Root with many children, no depth"
  {"root" {:id "root" :deps ["c1" "c2" "c3" "c4"]}
   "c1"   {:id "c1" :deps []}
   "c2"   {:id "c2" :deps []}
   "c3"   {:id "c3" :deps []}
   "c4"   {:id "c4" :deps []}})

(def complex-cycle-graph
  "A -> B -> C -> D -> B (cycle not involving start node)"
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps ["b"]}})

(defn deps-fn [node] (:deps node))

(def noop-visit   (fn [_id _node _path _visited _visiting] nil))
(def noop-cycle   (fn [_id _path _visited _visiting] nil))
(def noop-missing (fn [_id _visited _visiting] nil))

;; ===========================================================================
;; dfs — Layer 0 Core
;; ===========================================================================

(deftest dfs-linear-traversal-test
  (testing "Traverses a linear graph visiting all nodes"
    (let [[visited result] (sut/dfs linear-graph ["a"] deps-fn
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

(deftest dfs-diamond-traversal-test
  (testing "Traverses diamond graph, visiting shared node only once"
    (let [[visited result] (sut/dfs diamond-graph ["a"] deps-fn
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

(deftest dfs-single-start-id-not-collection-test
  (testing "Accepts a single start-id that is not wrapped in a collection"
    (let [[visited result] (sut/dfs linear-graph "a" deps-fn
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

(deftest dfs-cycle-detection-test
  (testing "Detects cycle and returns on-cycle-fn result"
    (let [[_visited result] (sut/dfs cyclic-graph ["a"] deps-fn
                                     noop-visit
                                     (fn [node-id path _v _vis]
                                       {:cycle-at node-id :path path})
                                     noop-missing)]
      (is (some? result))
      (is (= "a" (:cycle-at result)))
      (is (vector? (:path result))))))

(deftest dfs-self-loop-cycle-test
  (testing "Detects self-loop cycle"
    (let [[_visited result] (sut/dfs self-loop-graph ["a"] deps-fn
                                     noop-visit
                                     (fn [node-id path _v _vis]
                                       {:cycle-at node-id :path path})
                                     noop-missing)]
      (is (= "a" (:cycle-at result)))
      (is (= ["a" "a"] (:path result))))))

(deftest dfs-missing-node-test
  (testing "Calls on-missing-fn for nodes not in the graph"
    (let [[_visited result] (sut/dfs missing-dep-graph ["a"] deps-fn
                                     noop-visit
                                     noop-cycle
                                     (fn [node-id _v _vis]
                                       {:missing node-id}))]
      (is (= {:missing "b"} result)))))

(deftest dfs-missing-start-node-test
  (testing "Calls on-missing-fn when start node itself is not in graph"
    (let [[_visited result] (sut/dfs empty-graph ["x"] deps-fn
                                     noop-visit
                                     noop-cycle
                                     (fn [node-id _v _vis]
                                       {:missing node-id}))]
      (is (= {:missing "x"} result)))))

(deftest dfs-empty-graph-empty-starts-test
  (testing "Empty graph with no start nodes returns empty visited and nil result"
    (let [[visited result] (sut/dfs empty-graph [] deps-fn
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-halt-on-visit-test
  (testing "Halts traversal when on-visit-fn returns non-nil"
    (let [visit-order (atom [])
          [_visited result] (sut/dfs linear-graph ["a"] deps-fn
                                     (fn [node-id _node _path _v _vis]
                                       (swap! visit-order conj node-id)
                                       (when (= node-id "c")
                                         {:halted-at "c"}))
                                     noop-cycle
                                     noop-missing)]
      (is (= {:halted-at "c"} result))
      ;; Should have visited a, b, c but not d
      (is (= ["a" "b" "c"] @visit-order)))))

(deftest dfs-multiple-start-nodes-test
  (testing "Processes multiple disconnected start nodes"
    (let [[visited result] (sut/dfs disconnected-graph ["a" "c"] deps-fn
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

(deftest dfs-multiple-starts-halts-on-second-component-test
  (testing "Halts during second component if on-visit returns result"
    (let [[_visited result] (sut/dfs disconnected-graph ["a" "c"] deps-fn
                                     (fn [node-id _node _path _v _vis]
                                       (when (= node-id "d")
                                         {:found "d"}))
                                     noop-cycle
                                     noop-missing)]
      (is (= {:found "d"} result)))))

(deftest dfs-already-visited-nodes-skipped-test
  (testing "Nodes already visited are skipped (diamond graph)"
    (let [visit-count (atom 0)]
      (sut/dfs diamond-graph ["a"] deps-fn
               (fn [_id _node _path _v _vis]
                 (swap! visit-count inc)
                 nil)
               noop-cycle
               noop-missing)
      ;; Each of a, b, c, d visited exactly once
      (is (= 4 @visit-count)))))

(deftest dfs-path-tracking-test
  (testing "Path tracks the ancestors leading to the current node"
    (let [paths (atom {})]
      (sut/dfs linear-graph ["a"] deps-fn
               (fn [node-id _node path _v _vis]
                 (swap! paths assoc node-id path)
                 nil)
               noop-cycle
               noop-missing)
      (is (= [] (get @paths "a")))
      (is (= ["a"] (get @paths "b")))
      (is (= ["a" "b"] (get @paths "c")))
      (is (= ["a" "b" "c"] (get @paths "d"))))))

(deftest dfs-single-node-no-deps-test
  (testing "Single node with no dependencies"
    (let [[visited result] (sut/dfs single-node-graph ["a"] deps-fn
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))
      (is (nil? result)))))

(deftest dfs-complex-cycle-not-at-start-test
  (testing "Cycle detected when the cycle does not involve the start node"
    (let [[_visited result] (sut/dfs complex-cycle-graph ["a"] deps-fn
                                     noop-visit
                                     (fn [node-id path _v _vis]
                                       {:cycle-at node-id :path path})
                                     noop-missing)]
      (is (= "b" (:cycle-at result)))
      (is (some #(= "b" %) (:path result))))))

;; ===========================================================================
;; dfs-find — Layer 1
;; ===========================================================================

(deftest dfs-find-existing-node-test
  (testing "Finds a node matching the predicate"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _node _path] (= id "d")))]
      (is (some? result))
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))
      (is (= "d" (last (:path result)))))))

(deftest dfs-find-returns-first-match-test
  (testing "Returns the first match found in DFS order"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _node _path] (contains? #{"b" "c"} id)))]
      ;; DFS goes depth-first, so "b" should be found before "c"
      (is (= "b" (:found-id result))))))

(deftest dfs-find-not-found-test
  (testing "Returns nil when no node matches"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-start-node-matches-test
  (testing "Can find the start node itself"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] (:path result))))))

(deftest dfs-find-path-is-correct-test
  (testing "Path includes the full traversal path to the found node"
    (let [result (sut/dfs-find linear-graph "a" deps-fn
                              (fn [id _node _path] (= id "d")))]
      (is (= ["a" "b" "c" "d"] (:path result))))))

(deftest dfs-find-uses-node-data-test
  (testing "Predicate can use node data"
    (let [tagged-graph {"a" {:id "a" :deps ["b"] :tag :normal}
                        "b" {:id "b" :deps [] :tag :special}}
          result (sut/dfs-find tagged-graph "a" deps-fn
                              (fn [_id node _path] (= :special (:tag node))))]
      (is (= "b" (:found-id result))))))

(deftest dfs-find-handles-cycle-gracefully-test
  (testing "dfs-find does not loop forever on cyclic graphs"
    (let [result (sut/dfs-find cyclic-graph "a" deps-fn
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-handles-missing-deps-gracefully-test
  (testing "dfs-find skips missing nodes without error"
    (let [result (sut/dfs-find missing-dep-graph "a" deps-fn
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-empty-graph-test
  (testing "dfs-find on empty graph returns nil"
    (let [result (sut/dfs-find empty-graph "x" deps-fn
                              (fn [_id _node _path] true))]
      (is (nil? result)))))

;; ===========================================================================
;; dfs-validate-graph — Layer 1
;; ===========================================================================

(deftest dfs-validate-graph-valid-test
  (testing "Returns valid for a DAG"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph) deps-fn
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
      (is (true? (:valid? result)))
      (is (= diamond-graph (:graph result))))))

(deftest dfs-validate-graph-cycle-detected-test
  (testing "Returns error for cyclic graph"
    (let [result (sut/dfs-validate-graph cyclic-graph (keys cyclic-graph) deps-fn
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error (str "cycle at " id)})))]
      (is (false? (:valid? result)))
      (is (string? (:error result))))))

(deftest dfs-validate-graph-missing-dep-test
  (testing "Returns error for missing dependency"
    (let [result (sut/dfs-validate-graph missing-dep-graph ["a"] deps-fn
                                        (fn [id _node ctx]
                                          (when (:missing? ctx)
                                            {:valid? false :error (str "missing: " id)})))]
      (is (false? (:valid? result)))
      (is (= "missing: b" (:error result))))))

(deftest dfs-validate-graph-ignores-acceptable-issues-test
  (testing "Validate-fn returning nil means the issue is acceptable"
    (let [result (sut/dfs-validate-graph cyclic-graph (keys cyclic-graph) deps-fn
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-empty-test
  (testing "Empty graph is valid"
    (let [result (sut/dfs-validate-graph empty-graph [] deps-fn
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result)))
      (is (= empty-graph (:graph result))))))

(deftest dfs-validate-graph-self-loop-test
  (testing "Detects self-loop as a cycle"
    (let [result (sut/dfs-validate-graph self-loop-graph ["a"] deps-fn
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error (str "self-loop: " id)})))]
      (is (false? (:valid? result)))
      (is (= "self-loop: a" (:error result))))))

(deftest dfs-validate-graph-context-has-path-test
  (testing "Context includes path on cycle detection"
    (let [captured-ctx (atom nil)
          _ (sut/dfs-validate-graph cyclic-graph ["a"] deps-fn
                                   (fn [_id _node ctx]
                                     (when (:cycle? ctx)
                                       (reset! captured-ctx ctx)
                                       {:valid? false})))]
      (is (true? (:cycle? @captured-ctx)))
      (is (vector? (:path @captured-ctx))))))

;; ===========================================================================
;; collect-on-matches? (private, tested indirectly via dfs-collect)
;; ===========================================================================

(deftest collect-on-matches-indirect-test
  (testing ":pre is treated as alias for :visit"
    (let [pre-result (sut/dfs-collect single-node-graph ["a"] deps-fn
                                     (fn [id _path _v _vis] id)
                                     :pre)
          visit-result (sut/dfs-collect single-node-graph ["a"] deps-fn
                                       (fn [id _path _v _vis] id)
                                       :visit)]
      (is (= pre-result visit-result)))))

;; ===========================================================================
;; dfs-collect — Layer 1
;; ===========================================================================

(deftest dfs-collect-pre-order-test
  (testing "Collects node IDs in pre-order (DFS)"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      ;; Pre-order: a first, then depth-first through b->d, then c
      ;; d is visited via b, so c's dep on d is already visited
      (is (= ["a" "b" "d" "c"] result)))))

(deftest dfs-collect-post-order-test
  (testing "Collects node IDs in post-order (children before parents)"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      ;; Post-order: leaves first
      (is (= ["d" "b" "c" "a"] result)))))

(deftest dfs-collect-post-order-linear-test
  (testing "Post-order on linear graph gives reverse order"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= ["d" "c" "b" "a"] result)))))

(deftest dfs-collect-cycles-test
  (testing "Collects cycle information"
    (let [result (sut/dfs-collect cyclic-graph ["a"] deps-fn
                                 (fn [id path _v _vis] {:cycle-at id :path path})
                                 :cycle)]
      (is (= 1 (count result)))
      (is (= "a" (:cycle-at (first result)))))))

(deftest dfs-collect-missing-test
  (testing "Collects missing node information"
    (let [result (sut/dfs-collect missing-dep-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] {:missing id})
                                 :missing)]
      (is (= [{:missing "b"}] result)))))

(deftest dfs-collect-all-events-test
  (testing ":all collects on visit, cycle, and missing events"
    (let [graph-with-issues {"a" {:id "a" :deps ["b" "c"]}
                             "b" {:id "b" :deps ["a"]}}  ; b->a is cycle, c is missing
          result (sut/dfs-collect graph-with-issues ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :all)]
      ;; Should collect: a (visit), b (visit), a (cycle), c (missing)
      (is (= 4 (count result)))
      (is (some #(= "a" %) result))
      (is (some #(= "b" %) result))
      (is (some #(= "c" %) result)))))

(deftest dfs-collect-with-reduce-fn-test
  (testing "Custom reduce function accumulates results"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [_id _path _v _vis] 1)
                                 :visit
                                 {:reduce-fn + :init 0})]
      (is (= 4 result)))))

(deftest dfs-collect-with-reduce-fn-string-concat-test
  (testing "Reduce with string concatenation"
    (let [result (sut/dfs-collect linear-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit
                                 {:reduce-fn str :init ""})]
      (is (= "abcd" result)))))

(deftest dfs-collect-with-reduce-fn-set-accumulation-test
  (testing "Reduce into a set"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit
                                 {:reduce-fn conj :init #{}})]
      (is (= #{"a" "b" "c" "d"} result)))))

(deftest dfs-collect-nil-values-filtered-test
  (testing "collect-fn returning nil does not add to results"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis]
                                   (when (= id "d") "found-d"))
                                 :visit)]
      (is (= ["found-d"] result)))))

(deftest dfs-collect-empty-graph-test
  (testing "Collecting from empty graph with no starts returns empty vector"
    (let [result (sut/dfs-collect empty-graph [] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= [] result)))))

(deftest dfs-collect-single-start-id-not-collection-test
  (testing "Accepts a single start-id not wrapped in a collection"
    (let [result (sut/dfs-collect single-node-graph "a" deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= ["a"] result)))))

(deftest dfs-collect-multiple-start-nodes-test
  (testing "Collects from multiple disconnected start nodes"
    (let [result (sut/dfs-collect disconnected-graph ["a" "c"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= #{"a" "b" "c" "d"} (set result)))
      (is (= 4 (count result))))))

(deftest dfs-collect-post-order-cycle-handling-test
  (testing "Post-order traversal handles cycles without infinite loop"
    (let [result (sut/dfs-collect cyclic-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      ;; All nodes visited exactly once in post-order
      (is (= 3 (count result)))
      (is (= #{"a" "b" "c"} (set result))))))

(deftest dfs-collect-post-order-missing-handling-test
  (testing "Post-order traversal handles missing nodes"
    (let [result (sut/dfs-collect missing-dep-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      ;; Only 'a' should be collected in post-order (b is missing)
      (is (= ["a"] result)))))

(deftest dfs-collect-post-order-with-reduce-test
  (testing "Post-order with custom reduce function"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [_id _path _v _vis] 1)
                                 :post
                                 {:reduce-fn + :init 0})]
      (is (= 4 result)))))

(deftest dfs-collect-post-order-multiple-starts-test
  (testing "Post-order with multiple start nodes"
    (let [result (sut/dfs-collect disconnected-graph ["a" "c"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

(deftest dfs-collect-wide-graph-test
  (testing "Collects all children of a wide graph"
    (let [result (sut/dfs-collect wide-graph ["root"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= 5 (count result)))
      (is (= "root" (first result))))))

(deftest dfs-collect-visit-mode-ignores-cycles-and-missing-test
  (testing ":visit mode does not collect cycle or missing events"
    (let [graph {"a" {:id "a" :deps ["b" "c"]}
                 "b" {:id "b" :deps ["a"]}}  ; cycle back to a, c is missing
          result (sut/dfs-collect graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      ;; Only a and b are visited; cycle-at-a and missing-c should NOT appear
      (is (= ["a" "b"] result)))))

(deftest dfs-collect-cycle-mode-ignores-visits-test
  (testing ":cycle mode does not collect visit events"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :cycle)]
      ;; No cycles in diamond graph, so nothing collected
      (is (= [] result)))))

(deftest dfs-collect-missing-mode-on-complete-graph-test
  (testing ":missing mode collects nothing on a complete graph"
    (let [result (sut/dfs-collect diamond-graph ["a"] deps-fn
                                 (fn [id _path _v _vis] id)
                                 :missing)]
      (is (= [] result)))))
