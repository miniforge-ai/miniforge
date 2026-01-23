(ns ai.miniforge.repo-dag.interface-test
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.repo-dag.interface :as dag]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *manager* nil)

(defn manager-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (f)))

(use-fixtures :each manager-fixture)

;------------------------------------------------------------------------------ Layer 0
;; Schema validation tests

(deftest schema-validation-test
  (testing "valid-repo-node? validates correctly"
    (is (dag/valid-repo-node?
         {:repo/url "https://github.com/acme/tf-modules"
          :repo/name "tf-modules"
          :repo/type :terraform-module
          :repo/layer :foundations
          :repo/default-branch "main"}))
    (is (not (dag/valid-repo-node?
              {:repo/name "missing-required-fields"}))))

  (testing "valid-repo-edge? validates correctly"
    (is (dag/valid-repo-edge?
         {:edge/from "repo-a"
          :edge/to "repo-b"
          :edge/constraint :module-before-live
          :edge/merge-ordering :sequential}))
    (is (not (dag/valid-repo-edge?
              {:edge/from "repo-a"}))))

  (testing "infer-layer derives layer from type"
    (is (= :foundations (dag/infer-layer :terraform-module)))
    (is (= :infrastructure (dag/infer-layer :terraform-live)))
    (is (= :platform (dag/infer-layer :kubernetes)))
    (is (= :platform (dag/infer-layer :argocd)))
    (is (= :application (dag/infer-layer :application)))
    (is (= :foundations (dag/infer-layer :library)))))

;------------------------------------------------------------------------------ Layer 1
;; DAG CRUD tests

(deftest create-dag-test
  (testing "creates DAG with required fields"
    (let [d (dag/create-dag *manager* "test-dag")]
      (is (uuid? (:dag/id d)))
      (is (= "test-dag" (:dag/name d)))
      (is (= [] (:dag/repos d)))
      (is (= [] (:dag/edges d)))))

  (testing "creates DAG with description"
    (let [d (dag/create-dag *manager* "test-dag" "A test DAG")]
      (is (= "A test DAG" (:dag/description d)))))

  (testing "DAG is retrievable after creation"
    (let [d (dag/create-dag *manager* "test-dag")]
      (is (= d (dag/get-dag *manager* (:dag/id d)))))))

(deftest add-repo-test
  (testing "adds repo with explicit layer"
    (let [d (dag/create-dag *manager* "test-dag")
          updated (dag/add-repo *manager* (:dag/id d)
                                {:repo/url "https://github.com/acme/tf-modules"
                                 :repo/name "tf-modules"
                                 :repo/type :terraform-module
                                 :repo/layer :foundations})]
      (is (= 1 (count (:dag/repos updated))))
      (is (= "tf-modules" (-> updated :dag/repos first :repo/name)))))

  (testing "adds repo with inferred layer"
    (let [d (dag/create-dag *manager* "test-dag")
          updated (dag/add-repo *manager* (:dag/id d)
                                {:repo/url "https://github.com/acme/tf-modules"
                                 :repo/name "tf-modules"
                                 :repo/type :terraform-module})]
      ;; Layer should be inferred as :foundations
      (is (= :foundations (-> updated :dag/repos first :repo/layer)))))

  (testing "throws on duplicate repo name"
    (let [d (dag/create-dag *manager* "test-dag")]
      (dag/add-repo *manager* (:dag/id d)
                    {:repo/url "https://github.com/acme/tf-modules"
                     :repo/name "tf-modules"
                     :repo/type :terraform-module})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Repo already exists"
            (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/other/tf-modules"
                           :repo/name "tf-modules"
                           :repo/type :terraform-module}))))))

(deftest remove-repo-test
  (testing "removes repo from DAG"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/tf-modules"
                           :repo/name "tf-modules"
                           :repo/type :terraform-module})
          updated (dag/remove-repo *manager* (:dag/id d) "tf-modules")]
      (is (= 0 (count (:dag/repos updated))))))

  (testing "removes edges when repo is removed"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/a"
                           :repo/name "repo-a"
                           :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/b"
                           :repo/name "repo-b"
                           :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-b"
                          :library-before-consumer :sequential)
          updated (dag/remove-repo *manager* (:dag/id d) "repo-a")]
      (is (= 1 (count (:dag/repos updated))))
      (is (= 0 (count (:dag/edges updated)))))))

;------------------------------------------------------------------------------ Layer 2
;; Edge operations tests

(deftest add-edge-test
  (testing "adds edge between repos"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/a"
                           :repo/name "repo-a"
                           :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/b"
                           :repo/name "repo-b"
                           :repo/type :application})
          updated (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-b"
                                :library-before-consumer :sequential)]
      (is (= 1 (count (:dag/edges updated))))
      (let [edge (first (:dag/edges updated))]
        (is (= "repo-a" (:edge/from edge)))
        (is (= "repo-b" (:edge/to edge)))
        (is (= :library-before-consumer (:edge/constraint edge)))
        (is (= :sequential (:edge/merge-ordering edge))))))

  (testing "throws on missing from-repo"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/b"
                           :repo/name "repo-b"
                           :repo/type :application})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"From repo not found"
            (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-b"
                          :library-before-consumer :sequential)))))

  (testing "throws on self-loop"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/a"
                           :repo/name "repo-a"
                           :repo/type :library})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Self-loop not allowed"
            (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-a"
                          :library-before-consumer :sequential)))))

  (testing "throws on duplicate edge"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/a"
                           :repo/name "repo-a"
                           :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/b"
                           :repo/name "repo-b"
                           :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-b"
                          :library-before-consumer :sequential)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Edge already exists"
            (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-b"
                          :library-before-consumer :sequential))))))

(deftest remove-edge-test
  (testing "removes edge from DAG"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/a"
                           :repo/name "repo-a"
                           :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/acme/b"
                           :repo/name "repo-b"
                           :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "repo-a" "repo-b"
                          :library-before-consumer :sequential)
          updated (dag/remove-edge *manager* (:dag/id d) "repo-a" "repo-b")]
      (is (= 0 (count (:dag/edges updated)))))))

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

;------------------------------------------------------------------------------ Layer 5
;; Affected repos and upstream repos tests

(deftest affected-repos-test
  (testing "returns direct downstream repos"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          affected (dag/affected-repos *manager* (:dag/id d) "a")]
      (is (= #{"b"} affected))))

  (testing "returns transitive downstream repos"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "b" "c" :library-before-consumer :sequential)
          affected (dag/affected-repos *manager* (:dag/id d) "a")]
      (is (= #{"b" "c"} affected))))

  (testing "returns empty set for leaf node"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          affected (dag/affected-repos *manager* (:dag/id d) "b")]
      (is (= #{} affected)))))

(deftest upstream-repos-test
  (testing "returns direct upstream repos"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          upstream (dag/upstream-repos *manager* (:dag/id d) "b")]
      (is (= #{"a"} upstream))))

  (testing "returns transitive upstream repos"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "b" "c" :library-before-consumer :sequential)
          upstream (dag/upstream-repos *manager* (:dag/id d) "c")]
      (is (= #{"a" "b"} upstream))))

  (testing "returns empty set for root node"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          upstream (dag/upstream-repos *manager* (:dag/id d) "a")]
      (is (= #{} upstream)))))

;------------------------------------------------------------------------------ Layer 6
;; Merge order tests

(deftest merge-order-test
  (testing "computes merge order for subset of repos"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          _ (dag/add-edge *manager* (:dag/id d) "b" "c" :library-before-consumer :sequential)
          ;; Only merge b and c (not a)
          result (dag/merge-order *manager* (:dag/id d) #{"b" "c"})]
      (is (:success result))
      (is (= ["b" "c"] (:order result)))))

  (testing "handles disconnected repos in pr-set"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/c" :repo/name "c" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "c" :library-before-consumer :sequential)
          ;; a and b are disconnected, but both in PR set
          result (dag/merge-order *manager* (:dag/id d) #{"a" "b"})]
      (is (:success result))
      (is (= 2 (count (:order result))))
      (is (= #{"a" "b"} (set (:order result)))))))

;------------------------------------------------------------------------------ Layer 7
;; DAG validation tests

(deftest validate-dag-test
  (testing "valid DAG passes validation"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/a" :repo/name "a" :repo/type :library})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/b" :repo/name "b" :repo/type :application})
          _ (dag/add-edge *manager* (:dag/id d) "a" "b" :library-before-consumer :sequential)
          result (dag/validate-dag *manager* (:dag/id d))]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "empty DAG is valid"
    (let [d (dag/create-dag *manager* "test-dag")
          result (dag/validate-dag *manager* (:dag/id d))]
      (is (:valid? result))
      (is (empty? (:errors result))))))

;------------------------------------------------------------------------------ Layer 8
;; Compute layers tests

(deftest compute-layers-test
  (testing "groups repos by layer"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/tf-mod" :repo/name "tf-modules"
                           :repo/type :terraform-module})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/tf-live" :repo/name "tf-live"
                           :repo/type :terraform-live})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/k8s" :repo/name "k8s"
                           :repo/type :kubernetes})
          _ (dag/add-repo *manager* (:dag/id d)
                          {:repo/url "https://github.com/app" :repo/name "app"
                           :repo/type :application})
          current-dag (dag/get-dag *manager* (:dag/id d))
          layers (dag/compute-layers current-dag)]
      (is (= ["tf-modules"] (:foundations layers)))
      (is (= ["tf-live"] (:infrastructure layers)))
      (is (= ["k8s"] (:platform layers)))
      (is (= ["app"] (:application layers))))))

;------------------------------------------------------------------------------ Layer 9
;; Edge cases and error handling tests

(deftest edge-cases-test
  (testing "operations on nonexistent DAG"
    (let [fake-id (random-uuid)]
      (is (nil? (dag/get-dag *manager* fake-id)))
      (is (thrown? clojure.lang.ExceptionInfo
            (dag/add-repo *manager* fake-id
                          {:repo/url "https://github.com/a" :repo/name "a"
                           :repo/type :library})))
      (is (thrown? clojure.lang.ExceptionInfo
            (dag/compute-topo-order *manager* fake-id)))))

  (testing "get-all-dags returns all dags"
    (dag/create-dag *manager* "dag-1")
    (dag/create-dag *manager* "dag-2")
    (dag/create-dag *manager* "dag-3")
    (is (= 3 (count (dag/get-all-dags *manager*)))))

  (testing "reset-manager clears all dags"
    (dag/create-dag *manager* "dag-1")
    (dag/reset-manager! *manager*)
    (is (= 0 (count (dag/get-all-dags *manager*))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.repo-dag.interface-test)

  :leave-this-here)
