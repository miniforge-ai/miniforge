;; Tests for ai.miniforge.algorithms.interface
;;
;; Verifies that the public API delegates correctly to the graph module
;; and that all functions are accessible through the interface namespace.

(ns ai.miniforge.algorithms.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.algorithms.interface :as sut]
            [ai.miniforge.algorithms.graph :as graph]))

;; ---------------------------------------------------------------------------
;; Verify interface vars point to the correct implementation
;; ---------------------------------------------------------------------------

(deftest interface-dfs-is-graph-dfs-test
  (testing "interface/dfs is the same var as graph/dfs"
    (is (= sut/dfs graph/dfs))))

(deftest interface-dfs-find-is-graph-dfs-find-test
  (testing "interface/dfs-find is the same var as graph/dfs-find"
    (is (= sut/dfs-find graph/dfs-find))))

(deftest interface-dfs-validate-graph-is-graph-dfs-validate-graph-test
  (testing "interface/dfs-validate-graph is the same var as graph/dfs-validate-graph"
    (is (= sut/dfs-validate-graph graph/dfs-validate-graph))))

(deftest interface-dfs-collect-is-graph-dfs-collect-test
  (testing "interface/dfs-collect is the same var as graph/dfs-collect"
    (is (= sut/dfs-collect graph/dfs-collect))))

(deftest interface-dfs-collect-reduce-is-graph-dfs-collect-reduce-test
  (testing "interface/dfs-collect-reduce is the same var as graph/dfs-collect-reduce"
    (is (= sut/dfs-collect-reduce graph/dfs-collect-reduce))))

;; ---------------------------------------------------------------------------
;; Smoke tests: ensure each interface function is callable and produces
;; correct results (same as direct graph module calls)
;; ---------------------------------------------------------------------------

(def sample-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(defn get-deps [node] (:deps node))

(deftest interface-dfs-smoke-test
  (testing "dfs through interface traverses the graph"
    (let [[visited result]
          (sut/dfs sample-graph ["a"] get-deps
                   (fn [_id _n _p _v _vis] nil)
                   (fn [_id _p _v _vis] nil)
                   (fn [_id _v _vis] nil))]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result)))))

<<<<<<< Updated upstream
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

;------------------------------------------------------------------------------ dfs-find tests

(deftest dfs-find-basic-test
  (testing "find node d in diamond graph returns path"
    (let [result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path] (= id "d")))]
      (is (some? result))
=======
(deftest interface-dfs-find-smoke-test
  (testing "dfs-find through interface finds a node"
    (let [result (sut/dfs-find sample-graph "a" get-deps
                              (fn [id _node _path] (= id "d")))]
>>>>>>> Stashed changes
      (is (= "d" (:found-id result)))
      (is (vector? (:path result))))))

(deftest interface-dfs-validate-graph-smoke-test
  (testing "dfs-validate-graph through interface validates"
    (let [result (sut/dfs-validate-graph sample-graph (keys sample-graph) get-deps
                                        (fn [_id _node ctx]
                                          (when (:cycle? ctx)
                                            {:valid? false :error :cycle})))]
      (is (true? (:valid? result))))))

(deftest interface-dfs-collect-smoke-test
  (testing "dfs-collect through interface collects node ids"
    (let [result (sut/dfs-collect sample-graph ["a"] get-deps
                                 (fn [id _p _v _vis] id)
                                 :visit)]
      (is (= 4 (count result)))
      (is (= #{"a" "b" "c" "d"} (set result))))))

<<<<<<< Updated upstream
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
      (is (nil? result)))))

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
      (is (:valid? result)))))

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
      (is (false? (:valid? result))))))

(deftest dfs-validate-graph-missing-test
  (testing "missing dep detected via validate-fn"
    (let [result (algorithms/dfs-validate-graph
                   missing-dep-graph ["a"] get-deps
                   (fn [node-id _node context]
                     (when (:missing? context)
                       {:valid? false :error "missing" :node-id node-id})))]
      (is (false? (:valid? result)))
      (is (= "missing" (:error result)))
      (is (= "missing" (:node-id result))))))

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
      (is (= [] result)))))

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
      (is (= [] result)))))

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
      (is (= [] result)))))

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
        (is (contains? ids "gone") "gone should appear (missing)")))))

;------------------------------------------------------------------------------ Interface delegation tests

(deftest interface-vars-exist-test
  (testing "all public API vars are functions"
    (is (fn? algorithms/dfs))
    (is (fn? algorithms/dfs-find))
    (is (fn? algorithms/dfs-validate-graph))
    (is (fn? algorithms/dfs-collect))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run these tests:
  ;; bb test -- -n ai.miniforge.algorithms.interface-test

  :leave-this-here)
=======
(deftest interface-dfs-collect-reduce-smoke-test
  (testing "dfs-collect-reduce through interface reduces correctly"
    (let [result (sut/dfs-collect-reduce sample-graph ["a"] get-deps
                                        (fn [_id _p _v _vis] 1)
                                        :visit
                                        + 0)]
      (is (= 4 result)))))
>>>>>>> Stashed changes
