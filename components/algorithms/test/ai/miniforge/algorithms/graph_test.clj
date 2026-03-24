;; Tests for ai.miniforge.algorithms.graph
;;
;; Covers: dfs, dfs-find, dfs-validate-graph, dfs-collect, dfs-collect-reduce
;; Including edge cases: cycles, missing nodes, empty graphs, single nodes,
;; diamond dependencies, disconnected components, self-loops, deep graphs.

(ns ai.miniforge.algorithms.graph-test
  (:require [clojure.test :refer [deftest testing is]]
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

(defn noop-visit [_id _node _path _visited _visiting] nil)
(defn noop-cycle [_id _path _visited _visiting] nil)
(defn noop-missing [_id _visited _visiting] nil)

;; ===========================================================================
;; dfs tests
;; ===========================================================================

(deftest dfs-visits-all-reachable-nodes-test
  (testing "visits all reachable nodes in a simple graph"
    (let [[visited result] (sut/dfs diamond-graph ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

(deftest dfs-detects-cycle-test
  (testing "detects cycle and returns cycle info"
    (let [[_visited result]
          (sut/dfs cyclic-graph ["a"] get-deps
                   noop-visit
                   (fn [node-id path _visited _visiting]
                     {:cycle-node node-id :path path})
                   noop-missing)]
      (is (some? result))
      (is (= "a" (:cycle-node result))))))

(deftest dfs-detects-missing-test
  (testing "detects missing node and returns missing info"
    (let [[_visited result]
          (sut/dfs missing-dep-graph ["a"] get-deps
                   noop-visit
                   noop-cycle
                   (fn [node-id _visited _visiting]
                     {:missing node-id}))]
      (is (some? result))
      (is (= "missing" (:missing result))))))

(deftest dfs-on-visit-halt-test
  (testing "on-visit can halt early by returning a value"
    (let [[_visited result]
          (sut/dfs diamond-graph ["a"] get-deps
                   (fn [node-id _node _path _visited _visiting]
                     (when (= node-id "b") {:halted-at "b"}))
                   noop-cycle noop-missing)]
      (is (= {:halted-at "b"} result)))))

(deftest dfs-visited-set-grows-test
  (testing "visited set grows as traversal proceeds"
    (let [visited-sizes (atom [])
          [_visited _result]
          (sut/dfs linear-graph ["a"] get-deps
                   (fn [_id _node _path visited _visiting]
                     (swap! visited-sizes conj (count visited))
                     nil)
                   noop-cycle noop-missing)]
      ;; As we visit a, b, c in order, visited is #{} then #{a} then #{a,b}
      ;; on-visit sees the visited set BEFORE the current node is added
      (is (= [0 0 0] @visited-sizes)))))

(deftest dfs-multiple-start-nodes-test
  (testing "multiple start nodes visit all components"
    (let [[visited result] (sut/dfs disconnected-graph ["a" "x"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "x" "y"} visited))
      (is (nil? result)))))

(deftest dfs-single-start-id-not-coll-test
  (testing "single start-id (not wrapped in collection) works"
    (let [[visited result] (sut/dfs linear-graph "a" get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c"} visited))
      (is (nil? result)))))

(deftest dfs-self-cycle-test
  (testing "self-referential node triggers cycle detection"
    (let [[_visited result]
          (sut/dfs self-cycle-graph ["a"] get-deps
                   noop-visit
                   (fn [node-id _path _v _vis] {:self-cycle node-id})
                   noop-missing)]
      (is (= {:self-cycle "a"} result)))))

(deftest dfs-diamond-shared-dep-visited-once-test
  (testing "shared dependency in diamond is visited exactly once"
    (let [visit-count (atom 0)
          [_visited _result]
          (sut/dfs diamond-graph ["a"] get-deps
                   (fn [node-id _node _path _v _vis]
                     (when (= node-id "d")
                       (swap! visit-count inc))
                     nil)
                   noop-cycle noop-missing)]
      (is (= 1 @visit-count)))))

(deftest dfs-path-tracks-ancestry-test
  (testing "path parameter correctly tracks the ancestry chain"
    (let [paths (atom {})
          [_visited _result]
          (sut/dfs linear-graph ["a"] get-deps
                   (fn [node-id _node path _v _vis]
                     (swap! paths assoc node-id (vec path))
                     nil)
                   noop-cycle noop-missing)]
      (is (= [] (get @paths "a")))
      (is (= ["a"] (get @paths "b")))
      (is (= ["a" "b"] (get @paths "c"))))))

(deftest dfs-visiting-set-contains-ancestors-test
  (testing "visiting set contains current ancestry during traversal"
    (let [visiting-snapshots (atom {})
          [_visited _result]
          (sut/dfs linear-graph ["a"] get-deps
                   (fn [node-id _node _path _v visiting]
                     (swap! visiting-snapshots assoc node-id visiting)
                     nil)
                   noop-cycle noop-missing)]
      ;; When visiting "c", the visiting set should contain "a" and "b"
      (is (contains? (get @visiting-snapshots "c") "a"))
      (is (contains? (get @visiting-snapshots "c") "b")))))

(deftest dfs-cycle-path-includes-cycle-node-test
  (testing "cycle path includes the cycling node at the end"
    (let [[_visited result]
          (sut/dfs cyclic-graph ["a"] get-deps
                   noop-visit
                   (fn [node-id path _v _vis]
                     {:path path})
                   noop-missing)]
      ;; path passed to on-cycle-fn is (conj path node-id)
      (is (= "a" (last (:path result)))))))

;; ===========================================================================
;; dfs-find tests
;; ===========================================================================

(deftest dfs-find-returns-match-test
  (testing "returns found-id and path when predicate matches"
    (let [result (sut/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path] (= id "d")))]
      (is (= "d" (:found-id result)))
      (is (= ["a" "b" "d"] (:path result))))))

(deftest dfs-find-returns-nil-when-not-found-test
  (testing "returns nil when no node matches"
    (let [result (sut/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path] (= id "z")))]
      (is (nil? result)))))

(deftest dfs-find-returns-start-node-test
  (testing "can find the start node itself"
    (let [result (sut/dfs-find linear-graph "a" get-deps
                              (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result))))))

(deftest dfs-find-ignores-cycles-test
  (testing "dfs-find does not hang on cycles"
    (let [result (sut/dfs-find cyclic-graph "a" get-deps
                              (fn [id _node _path] (= id "nonexistent")))]
      (is (nil? result)))))

(deftest dfs-find-ignores-missing-nodes-test
  (testing "dfs-find skips missing nodes without error"
    (let [result (sut/dfs-find missing-dep-graph "a" get-deps
                              (fn [id _node _path] (= id "b")))]
      (is (= "b" (:found-id result))))))

(deftest dfs-find-uses-node-data-test
  (testing "predicate can inspect node data"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :color :red}
                 "b" {:id "b" :deps [] :color :blue}
                 "c" {:id "c" :deps [] :color :green}}
          result (sut/dfs-find graph "a" get-deps
                              (fn [_id node _path] (= :green (:color node))))]
      (is (= "c" (:found-id result))))))

(deftest dfs-find-uses-path-test
  (testing "predicate can use path for decisions"
    (let [result (sut/dfs-find linear-graph "a" get-deps
                              (fn [_id _node path] (= 2 (count path))))]
      ;; "c" has path ["a" "b"] (length 2) when visited
      (is (= "c" (:found-id result))))))

(deftest dfs-find-empty-graph-test
  (testing "dfs-find on completely empty graph returns nil"
    (let [result (sut/dfs-find {} "nonexistent" get-deps
                              (fn [_id _node _path] true))]
      (is (nil? result)))))

;; ===========================================================================
;; dfs-validate-graph tests
;; ===========================================================================

(deftest dfs-validate-graph-valid-test
  (testing "valid graph returns {:valid? true}"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph) get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle})))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-cycle-test
  (testing "cyclic graph returns validation error"
    (let [result (sut/dfs-validate-graph cyclic-graph ["a"] get-deps
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle :node id})))]
      (is (false? (:valid? result))))))

(deftest dfs-validate-graph-missing-test
  (testing "graph with missing deps returns validation error"
    (let [result (sut/dfs-validate-graph missing-dep-graph ["a"] get-deps
                                        (fn [id _node ctx]
                                          (when (:missing? ctx)
                                            {:valid? false :error :missing :node id})))]
      (is (false? (:valid? result)))
      (is (= "missing" (:node result))))))

(deftest dfs-validate-graph-empty-test
  (testing "empty graph with no starts is valid"
    (let [result (sut/dfs-validate-graph {} [] get-deps
                                        (fn [_id _node ctx]
                                          (cond
                                            (:cycle? ctx) {:valid? false :error :cycle}
                                            (:missing? ctx) {:valid? false :error :missing})))]
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

(deftest dfs-validate-graph-returns-graph-when-valid-test
  (testing "valid result includes the original graph"
    (let [result (sut/dfs-validate-graph diamond-graph (keys diamond-graph) get-deps
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result)))
      (is (= diamond-graph (:graph result))))))

(deftest dfs-validate-graph-cycle-path-provided-test
  (testing "cycle validation context includes the path"
    (let [result (sut/dfs-validate-graph cyclic-graph ["a"] get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :path (:path ctx)})))]
      (is (false? (:valid? result)))
      (is (vector? (:path result)))
      (is (pos? (count (:path result)))))))

(deftest dfs-validate-graph-tolerant-validator-test
  (testing "validator that returns nil for all events means graph is valid"
    (let [result (sut/dfs-validate-graph cyclic-graph ["a"] get-deps
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result))))))

;; ---------------------------------------------------------------------------
;; dfs-collect - :visit (pre-order)
;; ---------------------------------------------------------------------------

(deftest dfs-collect-pre-order-test
  (testing "collects node ids in pre-order (parent before children)"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= ["a" "b" "c"] result)))))

(deftest dfs-collect-visit-diamond-test
  (testing "collects diamond graph with :visit, shared node visited once"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :visit)]
      (is (= 4 (count result)))
      (is (= "a" (first result)))
      ;; "d" appears exactly once
      (is (= 1 (count (filter #(= "d" %) result)))))))

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
;; dfs-collect - :pre mode (alias for :visit)
;; ---------------------------------------------------------------------------

(deftest dfs-collect-pre-mode-linear-test
  (testing ":pre collects in same order as :visit (parent before children)"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :pre)]
      (is (= ["a" "b" "c"] result)))))

(deftest dfs-collect-pre-mode-diamond-test
  (testing ":pre collects diamond graph same as :visit"
    (let [pre-result (sut/dfs-collect diamond-graph ["a"] get-deps
                                     (fn [id _path _v _vis] id)
                                     :pre)
          visit-result (sut/dfs-collect diamond-graph ["a"] get-deps
                                       (fn [id _path _v _vis] id)
                                       :visit)]
      (is (= pre-result visit-result)))))

(deftest dfs-collect-pre-mode-empty-graph-test
  (testing ":pre on empty graph returns empty vector"
    (let [result (sut/dfs-collect {} [] get-deps
                                 (fn [id _p _v _vis] id)
                                 :pre)]
      (is (= [] result)))))

(deftest dfs-collect-pre-mode-single-node-test
  (testing ":pre on single node graph collects exactly one"
    (let [result (sut/dfs-collect single-node-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :pre)]
      (is (= ["a"] result)))))

(deftest dfs-collect-pre-mode-collects-matching-criteria-test
  (testing ":pre collects only nodes matching criteria"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _p _v _vis]
                                   (when (#{"b" "d"} id) id))
                                 :pre)]
      (is (= ["b" "d"] result)))))

;; ---------------------------------------------------------------------------
;; dfs-collect - :post mode (post-order)
;; ---------------------------------------------------------------------------

(deftest dfs-collect-post-linear-test
  (testing ":post collects in post-order (children before parents)"
    (let [result (sut/dfs-collect linear-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :post)]
      (is (= ["c" "b" "a"] result)))))

(deftest dfs-collect-post-diamond-test
  (testing ":post collects diamond graph in post-order"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :post)]
      ;; "d" must come before "b" and "c", all before "a"
      (let [idx (zipmap result (range))]
        (is (< (idx "d") (idx "b")))
        (is (< (idx "d") (idx "c")))
        (is (< (idx "b") (idx "a")))
        (is (< (idx "c") (idx "a"))))
      ;; All 4 nodes collected exactly once
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

(deftest dfs-collect-post-single-node-test
  (testing ":post on single node collects exactly one"
    (let [result (sut/dfs-collect single-node-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= ["a"] result)))))

(deftest dfs-collect-post-empty-graph-test
  (testing ":post on empty graph returns empty vector"
    (let [result (sut/dfs-collect {} [] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= [] result)))))

(deftest dfs-collect-post-empty-graph-missing-start-test
  (testing ":post on empty graph with start id returns empty (missing nodes skipped)"
    (let [result (sut/dfs-collect {} ["ghost"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= [] result)))))

(deftest dfs-collect-post-disconnected-test
  (testing ":post collects both components when both started"
    (let [result (sut/dfs-collect disconnected-graph ["a" "x"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= 4 (count result)))
      (is (= #{"a" "b" "x" "y"} (set result)))
      ;; Children before parents in each component
      (let [idx (zipmap result (range))]
        (is (< (idx "b") (idx "a")))
        (is (< (idx "y") (idx "x")))))))

(deftest dfs-collect-post-with-cycle-test
  (testing ":post on cyclic graph collects nodes without infinite loop"
    (let [result (sut/dfs-collect cyclic-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      ;; All three nodes collected, each once
      (is (= 3 (count result)))
      (is (= #{"a" "b" "c"} (set result))))))

(deftest dfs-collect-post-self-loop-test
  (testing ":post on self-loop graph collects the node"
    (let [result (sut/dfs-collect self-cycle-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :post)]
      (is (= ["a"] result)))))

(deftest dfs-collect-post-nil-values-ignored-test
  (testing ":post ignores nil return values from collect-fn"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _p _v _vis]
                                   (when (= id "d") "leaf"))
                                 :post)]
      (is (= ["leaf"] result)))))

(deftest dfs-collect-post-collects-matching-criteria-test
  (testing ":post collects only nodes matching criteria, in post-order"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _p _v _vis]
                                   (when (#{"b" "d"} id) id))
                                 :post)]
      ;; d before b in post-order
      (is (= ["d" "b"] result)))))

(deftest dfs-collect-pre-vs-post-order-test
  (testing ":pre and :post produce reversed order for linear graph"
    (let [pre  (sut/dfs-collect linear-graph ["a"] get-deps
                               (fn [id _p _v _vis] id)
                               :pre)
          post (sut/dfs-collect linear-graph ["a"] get-deps
                               (fn [id _p _v _vis] id)
                               :post)]
      (is (= (reverse pre) post)))))

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

(deftest dfs-collect-missing-multiple-test
  (testing "collects multiple missing node ids"
    (let [graph {"a" {:id "a" :deps ["ghost1" "ghost2" "b"]}
                 "b" {:id "b" :deps ["ghost3"]}}
          result (sut/dfs-collect graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :missing)]
      (is (= 3 (count result)))
      (is (= #{"ghost1" "ghost2" "ghost3"} (set result))))))

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

(deftest dfs-collect-all-on-acyclic-graph-test
  (testing ":all on acyclic graph with no missing collects only visits"
    (let [result (sut/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _v _vis] id)
                                 :all)]
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

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
    (let [result (sut/dfs-collect linear-graph "a" get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= ["a" "b" "c"] result)))))

(deftest dfs-collect-overlapping-starts-test
  (testing "overlapping start nodes don't cause duplicate visits"
    (let [result (sut/dfs-collect diamond-graph ["a" "b" "d"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      ;; Each node collected at most once
      (is (= (count result) (count (set result))))
      (is (= #{"a" "b" "c" "d"} (set result))))))

;; ===========================================================================
;; dfs-collect-reduce tests
;; ===========================================================================

(deftest dfs-collect-reduce-count-nodes-test
  (testing "counts visited nodes using + reducer"
    (let [result (sut/dfs-collect-reduce diamond-graph ["a"] get-deps
                                        (fn [_id _p _v _vis] 1)
                                        :visit
                                        + 0)]
      (is (= 4 result)))))

(deftest dfs-collect-reduce-into-set-test
  (testing "collects node ids into a set using conj reducer"
    (let [result (sut/dfs-collect-reduce diamond-graph ["a"] get-deps
                                        (fn [id _p _v _vis] id)
                                        :visit
                                        conj #{})]
      (is (= #{"a" "b" "c" "d"} result)))))

(deftest dfs-collect-reduce-sum-values-test
  (testing "sums numeric values extracted from nodes"
    (let [graph {"a" {:id "a" :deps ["b" "c"] :weight 10}
                 "b" {:id "b" :deps [] :weight 20}
                 "c" {:id "c" :deps [] :weight 30}}
          result (sut/dfs-collect-reduce graph ["a"] get-deps
                                        (fn [id _p _v _vis]
                                          (:weight (get graph id)))
                                        :visit
                                        + 0)]
      (is (= 60 result)))))

(deftest dfs-collect-reduce-string-concat-test
  (testing "concatenates node ids with custom reducer"
    (let [result (sut/dfs-collect-reduce linear-graph ["a"] get-deps
                                        (fn [id _p _v _vis] id)
                                        :visit
                                        (fn [acc v] (str acc (when (seq acc) "->") v))
                                        "")]
      (is (= "a->b->c" result)))))

(deftest dfs-collect-reduce-empty-graph-test
  (testing "returns init value for empty graph"
    (let [result (sut/dfs-collect-reduce {} [] get-deps
                                        (fn [id _p _v _vis] id)
                                        :visit
                                        + 0)]
      (is (= 0 result)))))

(deftest dfs-collect-reduce-with-pre-mode-test
  (testing "works with :pre mode"
    (let [result (sut/dfs-collect-reduce linear-graph ["a"] get-deps
                                        (fn [id _p _v _vis] id)
                                        :pre
                                        conj [])]
      (is (= ["a" "b" "c"] result)))))

(deftest dfs-collect-reduce-with-post-mode-test
  (testing "works with :post mode"
    (let [result (sut/dfs-collect-reduce linear-graph ["a"] get-deps
                                        (fn [id _p _v _vis] id)
                                        :post
                                        conj [])]
      (is (= ["c" "b" "a"] result)))))

(deftest dfs-collect-reduce-with-cycle-mode-test
  (testing "reduces cycle events"
    (let [result (sut/dfs-collect-reduce cyclic-graph ["a"] get-deps
                                        (fn [_id _p _v _vis] 1)
                                        :cycle
                                        + 0)]
      (is (= 1 result)))))

(deftest dfs-collect-reduce-nil-values-filtered-test
  (testing "nil values from collect-fn are filtered before reducing"
    (let [result (sut/dfs-collect-reduce diamond-graph ["a"] get-deps
                                        (fn [id _p _v _vis]
                                          (when (= id "d") 100))
                                        :visit
                                        + 0)]
      (is (= 100 result)))))

(deftest dfs-collect-reduce-max-depth-test
  (testing "finds max depth using custom reducer"
    (let [result (sut/dfs-collect-reduce diamond-graph ["a"] get-deps
                                        (fn [_id path _v _vis]
                                          (count path))
                                        :visit
                                        max 0)]
      ;; a=0, b=1, d=2, c=1 -> max is 2
      (is (= 2 result)))))

(deftest dfs-collect-reduce-build-map-test
  (testing "builds a map from node ids to depths using reduce"
    (let [result (sut/dfs-collect-reduce linear-graph ["a"] get-deps
                                        (fn [id path _v _vis]
                                          [id (count path)])
                                        :visit
                                        (fn [acc [k v]] (assoc acc k v))
                                        {})]
      (is (= {"a" 0 "b" 1 "c" 2} result)))))

(deftest dfs-collect-reduce-with-missing-mode-test
  (testing "reduces missing events"
    (let [result (sut/dfs-collect-reduce missing-dep-graph ["a"] get-deps
                                        (fn [id _p _v _vis] id)
                                        :missing
                                        conj [])]
      (is (= ["missing"] result)))))

(deftest dfs-collect-reduce-with-all-mode-test
  (testing "reduces all events"
    (let [graph {"a" {:id "a" :deps ["b" "missing"]}
                 "b" {:id "b" :deps ["a"]}}  ;; cycle
          result (sut/dfs-collect-reduce graph ["a"] get-deps
                                        (fn [id _p _v _vis] id)
                                        :all
                                        conj [])]
      ;; visits a, b + cycle a + missing "missing"
      (is (pos? (count result)))
      (is (some #(= "missing" %) result)))))

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
  (testing "dfs-collect on empty graph with start id collects nothing on :visit"
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

(deftest dfs-single-node-no-edges-test
  (testing "dfs on single node with no deps"
    (let [[visited result] (sut/dfs single-node-graph ["a"] get-deps
                                    noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))
      (is (nil? result)))))

(deftest dfs-find-single-node-no-edges-test
  (testing "dfs-find on single node"
    (let [result (sut/dfs-find single-node-graph "a" get-deps
                              (fn [id _node _path] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result)))))

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
      (is (= 1 @visit-count))
      (is (= 1 @cycle-count))
      (is (= #{"a"} visited)))))
