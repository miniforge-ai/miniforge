(ns ai.miniforge.repo-dag.dag-validation-test
  "Tests for DAG validation, layers, and edge cases (Layers 7-9)."
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.repo-dag.interface :as dag]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *manager* nil)

(defn manager-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (f)))

(use-fixtures :each manager-fixture)

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
