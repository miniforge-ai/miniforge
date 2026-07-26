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
(ns ai.miniforge.repo-dag.dag-queries-test
  "Tests for affected repos, upstream repos, and merge order."
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.repo-dag.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Fixtures
(def ^{:stratum 0} ^:dynamic *manager* nil)

(defn ^{:stratum 0} manager-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (f)))

;------------------------------------------------------------------------------ Layer 1

;; Affected repos and upstream repos tests
(deftest ^{:stratum 1} affected-repos-test
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

(deftest ^{:stratum 1} upstream-repos-test
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

;; Merge order tests
(deftest ^{:stratum 1} merge-order-test
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

(use-fixtures :each manager-fixture)
