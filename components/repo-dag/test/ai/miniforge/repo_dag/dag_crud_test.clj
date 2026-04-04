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

(ns ai.miniforge.repo-dag.dag-crud-test
  "Tests for DAG schema, creation, and CRUD operations (Layers 0-2)."
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
