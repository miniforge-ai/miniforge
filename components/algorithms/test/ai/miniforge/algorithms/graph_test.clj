(ns ai.miniforge.algorithms.graph-test
  "Unit tests for graph algorithms: DFS traversal, cycle detection,
   graph validation, and collection.

   Tests use the interface ns (not graph ns directly) per Polylith convention.

   Layer 0: Core DFS traversal tests
   Layer 1: DFS-based helper function tests (dfs-find, dfs-validate-graph, dfs-collect)"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.algorithms.interface :as alg]))

;; ============================================================================
;; Test fixtures — shared graph definitions
;; ============================================================================

;; Simple linear: a -> b -> c -> d
(def linear-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

;; Diamond: a -> b,c -> d
(def diamond-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

;; Self-cycle: a -> a
(def self-cycle-graph
  {"a" {:id "a" :deps ["a"]}})

;; Simple cycle: a -> b -> c -> a
(def simple-cycle-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a"]}})

;; Diamond with back-edge cycle: a -> b,c; b -> d; c -> d; d -> a
(def diamond-cycle-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps ["a"]}})

;; Disconnected: {a->b}, {c->d} (two independent components)
(def disconnected-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps []}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

;; Graph with missing dep reference: a -> b -> 'missing'
(def missing-dep-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["missing"]}})

;; Empty graph
(def empty-graph {})

;; Single node, no deps
(def single-node-graph
  {"x" {:id "x" :deps []}})

;; Shared helpers
(def get-deps (fn [node] (:deps node)))
(def noop (constantly nil))

;; ============================================================================
;; Layer 0 — Core DFS traversal
;; ============================================================================

(deftest dfs-empty-graph-test
  (testing "empty graph with no start nodes returns empty visited set"
    (let [[visited result] (alg/dfs {} [] get-deps noop noop noop)]
      (is (= #{} visited))
      (is (nil? result)))))

(deftest dfs-single-node-test
  (testing "single node with no deps visits that node"
    (let [visits (atom [])
          [visited result] (alg/dfs single-node-graph ["x"] get-deps
                                    (fn [id _node _path _visited _visiting]
                                      (swap! visits conj id) nil)
                                    noop noop)]
      (is (= #{"x"} visited))
      (is (nil? result))
      (is (= ["x"] @visits)))))

(deftest dfs-linear-traversal-test
  (testing "linear graph visits all nodes in depth-first order"
    (let [visits (atom [])
          [visited _] (alg/dfs linear-graph ["a"] get-deps
                               (fn [id _node _path _visited _visiting]
                                 (swap! visits conj id) nil)
                               noop noop)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (= ["a" "b" "c" "d"] @visits)))))

(deftest dfs-diamond-traversal-test
  (testing "diamond graph visits shared dep only once"
    (let [visits (atom [])
          [visited _] (alg/dfs diamond-graph ["a"] get-deps
                               (fn [id _node _path _visited _visiting]
                                 (swap! visits conj id) nil)
                               noop noop)]
      (is (= #{"a" "b" "c" "d"} visited))
      ;; d visited once via b, then skipped when reached via c
      (is (= 4 (count @visits))))))

(deftest dfs-halt-on-visit-test
  (testing "on-visit returning non-nil halts traversal"
    (let [[_ result] (alg/dfs linear-graph ["a"] get-deps
                              (fn [id _node _path _visited _visiting]
                                (when (= id "c") :found-c))
                              noop noop)]
      (is (= :found-c result)))))

(deftest dfs-cycle-detection-test
  (testing "self-cycle detected"
    (let [[_ result] (alg/dfs self-cycle-graph ["a"] get-deps
                              (fn [_id _node _path _visited _visiting] nil)
                              (fn [id path _visited _visiting]
                                {:cycle-at id :path path})
                              noop)]
      (is (some? result))
      (is (= "a" (:cycle-at result)))))

  (testing "simple cycle a->b->c->a detected"
    (let [[_ result] (alg/dfs simple-cycle-graph ["a"] get-deps
                              (fn [_id _node _path _visited _visiting] nil)
                              (fn [id path _visited _visiting]
                                {:cycle-at id :path path})
                              noop)]
      (is (some? result))
      (is (= "a" (:cycle-at result)))))

  (testing "diamond with back-edge cycle detected"
    (let [[_ result] (alg/dfs diamond-cycle-graph ["a"] get-deps
                              (fn [_id _node _path _visited _visiting] nil)
                              (fn [id path _visited _visiting]
                                {:cycle-at id :path path})
                              noop)]
      (is (some? result))
      (is (= "a" (:cycle-at result))))))

(deftest dfs-missing-node-test
  (testing "on-missing-fn called for missing deps"
    (let [[_ result] (alg/dfs missing-dep-graph ["a"] get-deps
                              (fn [_id _node _path _visited _visiting] nil)
                              noop
                              (fn [id _visited _visiting]
                                {:missing id}))]
      (is (= {:missing "missing"} result)))))

(deftest dfs-disconnected-graph-test
  (testing "multiple start nodes traverse disconnected components"
    (let [visits (atom [])
          [visited _] (alg/dfs disconnected-graph ["a" "c"] get-deps
                               (fn [id _node _path _visited _visiting]
                                 (swap! visits conj id) nil)
                               noop noop)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (= 4 (count @visits))))))

(deftest dfs-path-tracking-test
  (testing "path reflects current traversal path"
    (let [paths (atom {})
          [_ _] (alg/dfs linear-graph ["a"] get-deps
                         (fn [id _node path _visited _visiting]
                           (swap! paths assoc id path) nil)
                         noop noop)]
      (is (= [] (get @paths "a")))
      (is (= ["a"] (get @paths "b")))
      (is (= ["a" "b"] (get @paths "c")))
      (is (= ["a" "b" "c"] (get @paths "d"))))))

;; ============================================================================
;; Layer 1 — dfs-find
;; ============================================================================

(deftest dfs-find-basic-test
  (testing "finds node by id predicate"
    (let [result (alg/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path] (= id "d")))]
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))
      (is (= "d" (last (:path result))))))

  (testing "returns nil when predicate matches nothing"
    (is (nil? (alg/dfs-find diamond-graph "a" get-deps
                            (fn [_id _node _path] false)))))

  (testing "finds first match in DFS order"
    (let [result (alg/dfs-find diamond-graph "a" get-deps
                              (fn [id _node _path]
                                (contains? #{"b" "c"} id)))]
      ;; b is visited before c in DFS
      (is (= "b" (:found-id result)))))

  (testing "find in single-node graph"
    (let [result (alg/dfs-find single-node-graph "x" get-deps
                              (fn [id _node _path] (= id "x")))]
      (is (= "x" (:found-id result)))))

  (testing "find with missing start node returns nil"
    (is (nil? (alg/dfs-find {} "nonexistent" get-deps
                            (fn [_id _node _path] true)))))

  (testing "ignores cycles gracefully"
    (let [result (alg/dfs-find simple-cycle-graph "a" get-deps
                              (fn [id _node _path] (= id "c")))]
      (is (= "c" (:found-id result))))))

;; ============================================================================
;; Layer 1 — dfs-validate-graph
;; ============================================================================

(deftest dfs-validate-graph-valid-test
  (testing "valid acyclic graph returns {:valid? true :graph graph}"
    (let [result (alg/dfs-validate-graph diamond-graph (keys diamond-graph) get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error "cycle"})))]
      (is (true? (:valid? result)))
      (is (= diamond-graph (:graph result)))))

  (testing "empty graph is valid"
    (let [result (alg/dfs-validate-graph {} [] get-deps
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result))))))

(deftest dfs-validate-graph-cycle-test
  (testing "self-cycle detected"
    (let [result (alg/dfs-validate-graph self-cycle-graph ["a"] get-deps
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false
                                             :error (str "cycle at " id)})))]
      (is (false? (:valid? result)))
      (is (= "cycle at a" (:error result)))))

  (testing "simple cycle a->b->c->a detected"
    (let [result (alg/dfs-validate-graph simple-cycle-graph
                                        (keys simple-cycle-graph) get-deps
                                        (fn [id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false
                                             :cycle-node id
                                             :path (:path ctx)})))]
      (is (false? (:valid? result)))))

  (testing "diamond cycle detected"
    (let [result (alg/dfs-validate-graph diamond-cycle-graph
                                        (keys diamond-cycle-graph) get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false})))]
      (is (false? (:valid? result))))))

(deftest dfs-validate-graph-missing-test
  (testing "missing dependency flagged"
    (let [result (alg/dfs-validate-graph missing-dep-graph
                                        (keys missing-dep-graph) get-deps
                                        (fn [id _node ctx]
                                          (when (:missing? ctx)
                                            {:valid? false
                                             :error (str "missing node: " id)})))]
      (is (false? (:valid? result)))
      (is (= "missing node: missing" (:error result)))))

  (testing "validate-fn can ignore missing nodes"
    (let [result (alg/dfs-validate-graph missing-dep-graph
                                        (keys missing-dep-graph) get-deps
                                        (fn [_id _node _ctx] nil))]
      (is (true? (:valid? result))))))

;; ============================================================================
;; Layer 1 — dfs-collect
;; ============================================================================

(deftest dfs-collect-visit-test
  (testing "collect all visited node ids"
    (let [result (alg/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _visited _visiting] id)
                                 :visit)]
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result)))))

  (testing "collect from empty graph"
    (is (= [] (alg/dfs-collect {} [] get-deps
                               (fn [id _path _visited _visiting] id)
                               :visit))))

  (testing "collect from single node"
    (is (= ["x"] (alg/dfs-collect single-node-graph ["x"] get-deps
                                  (fn [id _path _visited _visiting] id)
                                  :visit))))

  (testing "collect with selective fn (returning nil to skip)"
    (let [result (alg/dfs-collect diamond-graph ["a"] get-deps
                                 (fn [id _path _visited _visiting]
                                   (when (not= id "a") id))
                                 :visit)]
      (is (= 3 (count result)))
      (is (not (contains? (set result) "a"))))))

(deftest dfs-collect-cycle-test
  (testing "collect cycles"
    (let [result (alg/dfs-collect simple-cycle-graph ["a"] get-deps
                                 (fn [id path _visited _visiting]
                                   {:node id :path path})
                                 :cycle)]
      (is (= 1 (count result)))
      (is (= "a" (-> result first :node)))))

  (testing "collect multiple cycles"
    ;; Two independent self-cycles
    (let [two-cycles {"a" {:id "a" :deps ["a"]}
                      "b" {:id "b" :deps ["b"]}}
          result (alg/dfs-collect two-cycles ["a" "b"] get-deps
                                 (fn [id _path _visited _visiting] id)
                                 :cycle)]
      (is (= 2 (count result))))))

(deftest dfs-collect-missing-test
  (testing "collect missing node references"
    (let [result (alg/dfs-collect missing-dep-graph ["a"] get-deps
                                 (fn [id _path _visited _visiting] id)
                                 :missing)]
      (is (= ["missing"] result)))))

(deftest dfs-collect-all-test
  (testing ":all collects visits, cycles, and missing"
    (let [graph-with-all {"a" {:id "a" :deps ["b" "missing"]}
                          "b" {:id "b" :deps ["a"]}}
          result (alg/dfs-collect graph-with-all ["a"] get-deps
                                 (fn [id _path _visited _visiting] id)
                                 :all)]
      ;; Should collect: visit a, visit b, cycle a, missing 'missing'
      (is (>= (count result) 3))))

  (testing "disconnected components collected"
    (let [result (alg/dfs-collect disconnected-graph ["a" "c"] get-deps
                                 (fn [id _path _visited _visiting] id)
                                 :visit)]
      (is (= #{"a" "b" "c" "d"} (set result))))))

;; ============================================================================
;; Rich Comment — REPL exploration
;; ============================================================================

(comment
  ;; Run all tests in this namespace
  (clojure.test/run-tests 'ai.miniforge.algorithms.graph-test)

  ;; Run a single test
  (clojure.test/test-var #'dfs-linear-traversal-test)

  :leave-this-here)
