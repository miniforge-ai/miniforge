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
  (testing "visits shared dependency only once in diamond graph"
    (let [[visited _] (sut/dfs diamond-graph
                               ["a"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c" "d"} visited)))))

(deftest dfs-single-start-id-not-collection
  (testing "accepts a single start-id that is not wrapped in a collection"
    (let [[visited _] (sut/dfs single-node-graph
                               "a"
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a"} visited)))))

(deftest dfs-empty-graph-returns-empty-visited
  (testing "traversal of an empty graph yields empty visited set"
    (let [[visited result] (sut/dfs empty-graph
                                    []
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-cycle-detection
  (testing "on-cycle-fn is called when a cycle is encountered"
    (let [[_ result] (sut/dfs cyclic-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [id path _ _]
                                {:cycle-at id :path path})
                              (fn [_ _ _] nil))]
      (is (some? result))
      (is (= "a" (:cycle-at result)))
      (is (vector? (:path result))))))

(deftest dfs-self-cycle-detection
  (testing "detects a self-referencing cycle"
    (let [[_ result] (sut/dfs self-cycle-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [id path _ _]
                                {:cycle-at id :path path})
                              (fn [_ _ _] nil))]
      (is (= "a" (:cycle-at result)))
      (is (= ["a" "a"] (:path result))))))

(deftest dfs-missing-node-callback
  (testing "on-missing-fn is called for nodes not in the graph"
    (let [[_ result] (sut/dfs missing-dep-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [_ _ _ _] nil)
                              (fn [id _ _]
                                {:missing id}))]
      (is (= {:missing "ghost"} result)))))

(deftest dfs-on-visit-halts-traversal
  (testing "returning non-nil from on-visit-fn halts traversal immediately"
    (let [visit-count (atom 0)
          [_ result] (sut/dfs diamond-graph
                              ["a"]
                              deps-fn
                              (fn [id _ _ _ _]
                                (swap! visit-count inc)
                                (when (= id "b") {:halted-at id}))
                              (fn [_ _ _ _] nil)
                              (fn [_ _ _] nil))]
      (is (= {:halted-at "b"} result))
      ;; "a" visited first, then "b" halts — exactly 2 visits
      (is (= 2 @visit-count)))))

(deftest dfs-result-nil-when-no-halt
  (testing "result is nil when traversal completes normally"
    (let [[_ result] (sut/dfs diamond-graph
                              ["a"]
                              deps-fn
                              (fn [_ _ _ _ _] nil)
                              (fn [_ _ _ _] nil)
                              (fn [_ _ _] nil))]
      (is (nil? result)))))

(deftest dfs-multiple-start-nodes
  (testing "processes all disconnected components via multiple start nodes"
    (let [[visited _] (sut/dfs disconnected-graph
                               ["a" "x"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "x" "y"} visited)))))

(deftest dfs-path-tracking
  (testing "path correctly tracks ancestry during traversal"
    (let [paths (atom {})
          [_ _] (sut/dfs linear-graph
                         ["a"]
                         deps-fn
                         (fn [id _ path _ _]
                           (swap! paths assoc id path)
                           nil)
                         (fn [_ _ _ _] nil)
                         (fn [_ _ _] nil))]
      (is (= [] (get @paths "a")))
      (is (= ["a"] (get @paths "b")))
      (is (= ["a" "b"] (get @paths "c"))))))

(deftest dfs-already-visited-not-revisited
  (testing "nodes already in visited set are not revisited"
    (let [visit-count (atom 0)
          [visited _] (sut/dfs diamond-graph
                               ["a"]
                               deps-fn
                               (fn [_ _ _ _ _]
                                 (swap! visit-count inc)
                                 nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      ;; "d" is reachable via both "b" and "c" but visited only once
      (is (= 4 @visit-count))
      (is (= #{"a" "b" "c" "d"} visited)))))

;; ---------------------------------------------------------------------------
;; dfs — Edge case: empty graph with non-empty start-ids
;; ---------------------------------------------------------------------------

(deftest dfs-empty-graph-with-start-ids-triggers-missing
  (testing "starting from a node not in an empty graph triggers on-missing-fn"
    (let [missing-ids (atom [])
          [visited result] (sut/dfs empty-graph
                                    ["a"]
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [id _ _]
                                      (swap! missing-ids conj id)
                                      {:missing id}))]
      (is (= #{} visited))
      (is (= {:missing "a"} result))
      (is (= ["a"] @missing-ids)))))

;; ---------------------------------------------------------------------------
;; dfs — Edge case: disconnected components, only reachable nodes visited
;; ---------------------------------------------------------------------------

(deftest dfs-disconnected-only-reachable-visited
  (testing "starting from one component does not visit the other component"
    (let [visit-log (atom [])
          [visited _] (sut/dfs disconnected-graph
                               ["a"]
                               deps-fn
                               (fn [id _ _ _ _]
                                 (swap! visit-log conj id)
                                 nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b"} visited))
      (is (not (contains? visited "x")))
      (is (not (contains? visited "y")))
      (is (= ["a" "b"] @visit-log)))))

(deftest dfs-disconnected-second-component-only
  (testing "starting from second component visits only that component"
    (let [[visited _] (sut/dfs disconnected-graph
                               ["x"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"x" "y"} visited))
      (is (not (contains? visited "a")))
      (is (not (contains? visited "b"))))))

;; ---------------------------------------------------------------------------
;; dfs — Edge case: self-loop with children
;; ---------------------------------------------------------------------------

(deftest dfs-self-loop-with-children
  (testing "self-loop detected but traversal continues to children when on-cycle returns nil"
    (let [cycles (atom [])
          [visited _] (sut/dfs self-loop-with-children-graph
                               ["a"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [id path _ _]
                                 (swap! cycles conj {:id id :path path})
                                 nil) ;; return nil to continue
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b"} visited))
      (is (= 1 (count @cycles)))
      (is (= "a" (:id (first @cycles)))))))

(deftest dfs-multi-self-loop
  (testing "multiple nodes with self-loops are each detected"
    (let [cycles (atom [])
          [visited _] (sut/dfs multi-self-loop-graph
                               ["a"]
                               deps-fn
                               (fn [_ _ _ _ _] nil)
                               (fn [id path _ _]
                                 (swap! cycles conj id)
                                 nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b"} visited))
      (is (= 2 (count @cycles)))
      (is (= #{"a" "b"} (set @cycles))))))

;; ---------------------------------------------------------------------------
;; dfs — Edge case: diamond dependency patterns
;; ---------------------------------------------------------------------------

(deftest dfs-diamond-no-duplicate-visits
  (testing "diamond pattern: shared node visited exactly once"
    (let [visit-log (atom [])
          [visited _] (sut/dfs diamond-graph
                               ["a"]
                               deps-fn
                               (fn [id _ _ _ _]
                                 (swap! visit-log conj id)
                                 nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= 4 (count @visit-log)))
      (is (= 1 (count (filter #(= "d" %) @visit-log))))
      (is (= #{"a" "b" "c" "d"} visited)))))

(deftest dfs-double-diamond-traversal
  (testing "double-diamond: all 7 nodes visited, each exactly once"
    (let [visit-log (atom [])
          [visited _] (sut/dfs double-diamond-graph
                               ["a"]
                               deps-fn
                               (fn [id _ _ _ _]
                                 (swap! visit-log conj id)
                                 nil)
                               (fn [_ _ _ _] nil)
                               (fn [_ _ _] nil))]
      (is (= #{"a" "b" "c" "d" "e" "f" "g"} visited))
      (is (= 7 (count @visit-log)))
      ;; Each convergence node visited exactly once
      (is (= 1 (count (filter #(= "d" %) @visit-log))))
      (is (= 1 (count (filter #(= "g" %) @visit-log)))))))

(deftest dfs-diamond-path-to-shared-node
  (testing "diamond: path to shared node reflects first traversal path"
    (let [paths (atom {})
          [_ _] (sut/dfs diamond-graph
                         ["a"]
                         deps-fn
                         (fn [id _ path _ _]
                           (swap! paths assoc id path)
                           nil)
                         (fn [_ _ _ _] nil)
                         (fn [_ _ _] nil))]
      ;; "d" is reached via a -> b -> d first (DFS left-to-right)
      (is (= ["a" "b"] (get @paths "d"))))))

;; ---------------------------------------------------------------------------
;; dfs — Edge case: very deep graphs (stack behavior)
;; ---------------------------------------------------------------------------

(deftest dfs-very-deep-graph-100
  (testing "DFS traverses a 100-node deep chain without stack overflow"
    (let [graph (make-deep-chain 100)
          [visited result] (sut/dfs graph
                                    ["n0"]
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= 100 (count visited)))
      (is (nil? result))
      (is (contains? visited "n0"))
      (is (contains? visited "n99")))))

(deftest dfs-very-deep-graph-500
  (testing "DFS traverses a 500-node deep chain without stack overflow"
    ;; NOTE: The current DFS implementation uses JVM stack recursion.
    ;; This test passes on default JVM settings but could fail under
    ;; constrained stack sizes or significantly deeper chains.
    (let [graph (make-deep-chain 500)
          [visited result] (sut/dfs graph
                                    ["n0"]
                                    deps-fn
                                    (fn [_ _ _ _ _] nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= 500 (count visited)))
      (is (nil? result)))))

(deftest dfs-deep-graph-path-accuracy
  (testing "path grows correctly in a deep chain"
    (let [graph (make-deep-chain 50)
          paths (atom {})
          [_ _] (sut/dfs graph
                         ["n0"]
                         deps-fn
                         (fn [id _ path _ _]
                           (swap! paths assoc id path)
                           nil)
                         (fn [_ _ _ _] nil)
                         (fn [_ _ _] nil))]
      (is (= [] (get @paths "n0")))
      ;; Last node has path of all predecessors
      (is (= 49 (count (get @paths "n49"))))
      ;; Path is ordered n0, n1, ..., n48
      (is (= "n0" (first (get @paths "n49"))))
      (is (= "n48" (last (get @paths "n49")))))))

;; ---------------------------------------------------------------------------
;; dfs — Edge case: single node, no edges
;; ---------------------------------------------------------------------------

(deftest dfs-single-node-no-edges-visited
  (testing "single node with no deps is visited and result is nil"
    (let [visit-log (atom [])
          [visited result] (sut/dfs single-node-graph
                                    ["a"]
                                    deps-fn
                                    (fn [id _ _ _ _]
                                      (swap! visit-log conj id)
                                      nil)
                                    (fn [_ _ _ _] nil)
                                    (fn [_ _ _] nil))]
      (is (= #{"a"} visited))
      (is (nil? result))
      (is (= ["a"] @visit-log)))))

(deftest dfs-single-node-path-is-empty
  (testing "single node receives empty path (no ancestors)"
    (let [recorded-path (atom nil)
          [_ _] (sut/dfs single-node-graph
                         ["a"]
                         deps-fn
                         (fn [_ _ path _ _]
                           (reset! recorded-path path)
                           nil)
                         (fn [_ _ _ _] nil)
                         (fn [_ _ _] nil))]
      (is (= [] @recorded-path)))))

;; ---------------------------------------------------------------------------
;; dfs-find
;; ---------------------------------------------------------------------------

(deftest dfs-find-existing-node
  (testing "finds a node matching the predicate"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _node _path] (= id "d")))]
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))
      (is (= "d" (last (:path result)))))))

(deftest dfs-find-returns-nil-when-not-found
  (testing "returns nil when no node matches"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-start-node-matches
  (testing "finds the start node itself if it matches"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] (:path result))))))

(deftest dfs-find-with-cycle-does-not-hang
  (testing "find in a cyclic graph terminates without error"
    (let [result (sut/dfs-find cyclic-graph "a" deps-fn
                              (fn [_id _node _path] false))]
      (is (nil? result)))))

(deftest dfs-find-missing-dep-does-not-error
  (testing "find tolerates missing dependencies"
    (let [result (sut/dfs-find missing-dep-graph "a" deps-fn
                              (fn [id _node _path] (= id "b")))]
      (is (= "b" (:found-id result))))))

(deftest dfs-find-empty-graph
  (testing "returns nil when searching an empty graph"
    (let [result (sut/dfs-find empty-graph "a" deps-fn
                              (fn [_ _ _] true))]
      (is (nil? result)))))

(deftest dfs-find-path-accuracy
  (testing "path reflects actual traversal ancestry to found node"
    (let [result (sut/dfs-find deep-graph "a" deps-fn
                              (fn [id _node _path] (= id "e")))]
      (is (= {:found-id "e" :path ["a" "b" "c" "d" "e"]} result)))))

(deftest dfs-find-uses-node-data
  (testing "predicate receives the node map for inspection"
    (let [tagged-graph {"a" {:id "a" :deps ["b"] :color :red}
                        "b" {:id "b" :deps [] :color :blue}}
          result (sut/dfs-find tagged-graph "a" deps-fn
                              (fn [_id node _path] (= :blue (:color node))))]
      (is (= "b" (:found-id result))))))

;; ---------------------------------------------------------------------------
;; dfs-find — Edge cases
;; ---------------------------------------------------------------------------

(deftest dfs-find-single-node-found
  (testing "find on single node graph returns the node when it matches"
    (let [result (sut/dfs-find single-node-graph "a" deps-fn
                              (fn [id _ _] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result)))))

(deftest dfs-find-single-node-not-found
  (testing "find on single node graph returns nil when predicate fails"
    (let [result (sut/dfs-find single-node-graph "a" deps-fn
                              (fn [_ _ _] false))]
      (is (nil? result)))))

(deftest dfs-find-self-loop-terminates
  (testing "find on self-loop graph terminates and can find the node"
    (let [result (sut/dfs-find self-cycle-graph "a" deps-fn
                              (fn [id _ _] (= id "a")))]
      (is (= "a" (:found-id result))))))

(deftest dfs-find-self-loop-not-found
  (testing "find on self-loop graph terminates even when predicate never matches"
    (let [result (sut/dfs-find self-cycle-graph "a" deps-fn
                              (fn [_ _ _] false))]
      (is (nil? result)))))

(deftest dfs-find-disconnected-only-searches-reachable
  (testing "find starting from one component does not find nodes in another"
    (let [result (sut/dfs-find disconnected-graph "a" deps-fn
                              (fn [id _ _] (= id "x")))]
      (is (nil? result)))))

(deftest dfs-find-diamond-finds-via-first-path
  (testing "find in diamond returns path through first branch"
    (let [result (sut/dfs-find diamond-graph "a" deps-fn
                              (fn [id _ _] (= id "d")))]
      ;; DFS goes left first: a -> b -> d
      (is (= ["a" "b" "d"] (:path result))))))

(deftest dfs-find-double-diamond
  (testing "find in double-diamond navigates through both convergence points"
    (let [result (sut/dfs-find double-diamond-graph "a" deps-fn
                              (fn [id _ _] (= id "g")))]
      (is (= "g" (:found-id result)))
      ;; DFS left-first: a -> b -> d -> e -> g
      (is (= ["a" "b" "d" "e" "g"] (:path result))))))

(deftest dfs-find-very-deep-graph
  (testing "find at bottom of a 200-node chain"
    (let [graph (make-deep-chain 200)
          result (sut/dfs-find graph "n0" deps-fn
                              (fn [id _ _] (= id "n199")))]
      (is (= "n199" (:found-id result)))
      (is (= 200 (count (:path result)))))))

;; ---------------------------------------------------------------------------
;; dfs-validate-graph
;; ---------------------------------------------------------------------------

(deftest dfs-validate-graph-valid
  (testing "returns valid for an acyclic graph with no missing deps"
    (let [result (sut/dfs-validate-graph diamond-graph
                                        (keys diamond-graph)
                                        deps-fn
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
      (is (true? (:valid? result)))
      (is (= diamond-graph (:graph result))))))

(deftest dfs-validate-graph-detects-cycle
  (testing "returns invalid when cycle is detected"
    (let [result (sut/dfs-validate-graph cyclic-graph
                                        ["a"]
                                        deps-fn
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false
                                             :error (str "Cycle at " id)
                                             :path (:path ctx)})))]
      (is (false? (:valid? result)))
      (is (string? (:error result))))))

(deftest dfs-validate-graph-detects-missing
  (testing "returns invalid when missing node is detected"
    (let [result (sut/dfs-validate-graph missing-dep-graph
                                        ["a"]
                                        deps-fn
                                        (fn [id _node ctx]
                                          (when (:missing? ctx)
                                            {:valid? false
                                             :error (str "Missing node: " id)})))]
      (is (false? (:valid? result)))
      (is (= "Missing node: ghost" (:error result))))))

(deftest dfs-validate-graph-valid-when-validate-fn-returns-nil
  (testing "graph is valid when validate-fn always returns nil"
    (let [result (sut/dfs-validate-graph cyclic-graph
                                        ["a"]
                                        deps-fn
                                        (fn [_ _ _] nil))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-empty
  (testing "empty graph is valid"
    (let [result (sut/dfs-validate-graph empty-graph
                                        []
                                        deps-fn
                                        (fn [_ _ _] nil))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-self-cycle
  (testing "detects self-referencing cycle"
    (let [result (sut/dfs-validate-graph self-cycle-graph
                                        ["a"]
                                        deps-fn
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error (str "Self-cycle: " id)})))]
      (is (false? (:valid? result))))))

;; ---------------------------------------------------------------------------
;; dfs-validate-graph — Edge cases
;; ---------------------------------------------------------------------------

(deftest dfs-validate-graph-single-node-valid
  (testing "single node with no deps is valid"
    (let [result (sut/dfs-validate-graph single-node-graph
                                        ["a"]
                                        deps-fn
                                        (fn [_ _ ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-disconnected-partial-start
  (testing "validating from one component ignores the other"
    (let [result (sut/dfs-validate-graph disconnected-graph
                                        ["a"]
                                        deps-fn
                                        (fn [_ _ _] nil))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-double-diamond-valid
  (testing "double diamond is a valid acyclic graph"
    (let [result (sut/dfs-validate-graph double-diamond-graph
                                        ["a"]
                                        deps-fn
                                        (fn [_ _ ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-self-loop-with-children
  (testing "self-loop-with-children detects cycle"
    (let [result (sut/dfs-validate-graph self-loop-with-children-graph
                                        ["a"]
                                        deps-fn
                                        (fn [id _ ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false
                                             :error (str "Self-cycle: " id)})))]
      (is (false? (:valid? result)))
      (is (= "Self-cycle: a" (:error result))))))

(deftest dfs-validate-graph-deep-chain-valid
  (testing "100-node deep chain is a valid acyclic graph"
    (let [graph (make-deep-chain 100)
          result (sut/dfs-validate-graph graph
                                        ["n0"]
                                        deps-fn
                                        (fn [_ _ ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
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
      ;; "a" must come before "b" and "c"; "d" is visited via "b" before "c"
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
      (is (contains? (set result) "ghost"))
      ;; a appears at least twice: once for visit, once for cycle
      (is (>= (count (filter #(= "a" %) result)) 2)))))

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
