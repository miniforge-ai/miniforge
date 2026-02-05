(ns ai.miniforge.repo-dag.dag-topology-test
  "Tests for topological sort and cycle detection (Layers 3-4)."
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.repo-dag.interface :as dag]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *manager* nil)

(defn manager-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (f)))

(use-fixtures :each manager-fixture)

;------------------------------------------------------------------------------ Layer 3
;; Topological sort tests

(deftest topo-sort-test
  (testing "returns correct order for linear chain"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "b" "c" :library-before-consumer :sequential)
          result (dag/compute-topo-order *manager* (:dag/id d))]
      (is (:success result))
      (is (= ["a" "b" "c"] (:order result)))))

  (testing "handles diamond dependency"
    (let [d (dag/create-dag *manager* "test-dag")
          ;; Diamond: a -> b, a -> c, b -> d, c -> d
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/d" :repo/name "d" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "a" "c" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "b" "d" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "c" "d" :library-before-consumer :sequential)
          result (dag/compute-topo-order *manager* (:dag/id d))]
      (is (:success result))
      ;; a must come first, d must come last
      (is (= "a" (first (:order result))))
      (is (= "d" (last (:order result))))
      ;; b and c must come before d
      (is (< (.indexOf (:order result) "b") (.indexOf (:order result) "d")))
      (is (< (.indexOf (:order result) "c") (.indexOf (:order result) "d")))))

  (testing "handles isolated nodes"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          result (dag/compute-topo-order *manager* (:dag/id d))]
      (is (:success result))
      (is (= 2 (count (:order result))))
      (is (= #{"a" "b"} (set (:order result)))))))

;------------------------------------------------------------------------------ Layer 4
;; Cycle detection tests

(deftest cycle-detection-test
  (testing "detects simple cycle on add-edge"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Adding edge would create cycle"
            (dag/add-edge *manager* (:dag/id d) "b" "a"
                          :library-before-consumer :sequential)))))

  (testing "detects longer cycle on add-edge"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "b" "c" :library-before-consumer :sequential)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Adding edge would create cycle"
            (dag/add-edge *manager* (:dag/id d) "c" "a"
                          :library-before-consumer :sequential)))))

  (testing "find-cycle-nodes identifies cycle members"
    ;; We can test the pure function directly by creating a dag with a cycle
    ;; (bypassing the add-edge check)
    (let [dag-with-cycle {:dag/id (random-uuid)
                          :dag/name "cyclic"
                          :dag/repos [{:repo/url "a" :repo/name "a" :repo/type :library :repo/layer :foundations :repo/default-branch "main"}
                                      {:repo/url "b" :repo/name "b" :repo/type :library :repo/layer :foundations :repo/default-branch "main"}
                                      {:repo/url "c" :repo/name "c" :repo/type :library :repo/layer :foundations :repo/default-branch "main"}]
                          :dag/edges [{:edge/from "a" :edge/to "b" :edge/constraint :library-before-consumer :edge/merge-ordering :sequential}
                                      {:edge/from "b" :edge/to "c" :edge/constraint :library-before-consumer :edge/merge-ordering :sequential}
                                      {:edge/from "c" :edge/to "a" :edge/constraint :library-before-consumer :edge/merge-ordering :sequential}]}
          cycle-nodes (dag/find-cycle-nodes dag-with-cycle)]
      (is (= #{"a" "b" "c"} cycle-nodes)))))
