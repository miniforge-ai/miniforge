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

(ns ai.miniforge.repo-dag.anomaly.queries-test
  "Coverage for the read-only anomaly-returning protocol methods:
   compute-topo-order, affected-repos, upstream-repos, merge-order,
   and validate-dag. All five share the same failure path
   (`:not-found` when the DAG is missing) and pass through their
   normal return shape on success."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.repo-dag.interface :as dag]))

(def ^:dynamic *manager* nil)
(def ^:dynamic *dag-id* nil)

(defn manager-and-dag-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (let [d (dag/create-dag *manager* "test-dag")]
      (dag/add-repo-anomaly *manager* (:dag/id d)
                            {:repo/url "https://github.com/acme/a"
                             :repo/name "repo-a"
                             :repo/type :library})
      (dag/add-repo-anomaly *manager* (:dag/id d)
                            {:repo/url "https://github.com/acme/b"
                             :repo/name "repo-b"
                             :repo/type :application})
      (dag/add-edge-anomaly *manager* (:dag/id d) "repo-a" "repo-b"
                            :library-before-consumer :sequential)
      (binding [*dag-id* (:dag/id d)]
        (f)))))

(use-fixtures :each manager-and-dag-fixture)

;------------------------------------------------------------------------------ compute-topo-order

(deftest compute-topo-order-anomaly-happy-path
  (testing "successful topo-order returns the result map, not an anomaly"
    (let [result (dag/compute-topo-order-anomaly *manager* *dag-id*)]
      (is (not (anomaly/anomaly? result)))
      (is (true? (:success result)))
      (is (= ["repo-a" "repo-b"] (:order result))))))

(deftest compute-topo-order-anomaly-not-found
  (testing "missing DAG yields :not-found anomaly"
    (let [result (dag/compute-topo-order-anomaly *manager* (random-uuid))]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result))))))

(deftest compute-topo-order-still-throws
  (testing "deprecated throwing variant still throws on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/compute-topo-order *manager* (random-uuid))))))

;------------------------------------------------------------------------------ affected-repos

(deftest affected-repos-anomaly-happy-path
  (testing "successful query returns downstream set, not an anomaly"
    (let [result (dag/affected-repos-anomaly *manager* *dag-id* "repo-a")]
      (is (not (anomaly/anomaly? result)))
      (is (= #{"repo-b"} result)))))

(deftest affected-repos-anomaly-not-found
  (testing "missing DAG yields :not-found anomaly"
    (let [result (dag/affected-repos-anomaly *manager* (random-uuid) "repo-a")]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result))))))

(deftest affected-repos-still-throws
  (testing "deprecated throwing variant still throws on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/affected-repos *manager* (random-uuid) "repo-a")))))

;------------------------------------------------------------------------------ upstream-repos

(deftest upstream-repos-anomaly-happy-path
  (testing "successful query returns upstream set, not an anomaly"
    (let [result (dag/upstream-repos-anomaly *manager* *dag-id* "repo-b")]
      (is (not (anomaly/anomaly? result)))
      (is (= #{"repo-a"} result)))))

(deftest upstream-repos-anomaly-not-found
  (testing "missing DAG yields :not-found anomaly"
    (let [result (dag/upstream-repos-anomaly *manager* (random-uuid) "repo-b")]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result))))))

(deftest upstream-repos-still-throws
  (testing "deprecated throwing variant still throws on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/upstream-repos *manager* (random-uuid) "repo-b")))))

;------------------------------------------------------------------------------ merge-order

(deftest merge-order-anomaly-happy-path
  (testing "successful query returns merge-order map, not an anomaly"
    (let [result (dag/merge-order-anomaly *manager* *dag-id* #{"repo-a" "repo-b"})]
      (is (not (anomaly/anomaly? result)))
      (is (true? (:success result)))
      (is (= ["repo-a" "repo-b"] (:order result))))))

(deftest merge-order-anomaly-not-found
  (testing "missing DAG yields :not-found anomaly"
    (let [result (dag/merge-order-anomaly *manager* (random-uuid) #{"repo-a"})]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result))))))

(deftest merge-order-still-throws
  (testing "deprecated throwing variant still throws on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/merge-order *manager* (random-uuid) #{"repo-a"})))))

;------------------------------------------------------------------------------ validate-dag

(deftest validate-dag-anomaly-happy-path
  (testing "successful validate returns the result map, not an anomaly"
    (let [result (dag/validate-dag-anomaly *manager* *dag-id*)]
      (is (not (anomaly/anomaly? result)))
      (is (true? (:valid? result)))
      (is (= [] (:errors result))))))

(deftest validate-dag-anomaly-not-found
  (testing "missing DAG yields :not-found anomaly"
    (let [result (dag/validate-dag-anomaly *manager* (random-uuid))]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result))))))

(deftest validate-dag-still-throws
  (testing "deprecated throwing variant still throws on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/validate-dag *manager* (random-uuid))))))
