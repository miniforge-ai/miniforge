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

(ns ai.miniforge.repo-dag.anomaly.add-edge-test
  "Coverage for `dag/add-edge-anomaly` and its deprecated throwing
   sibling `dag/add-edge`. Five failure modes:
   - `:not-found` when DAG missing
   - `:not-found` when from-repo missing
   - `:not-found` when to-repo missing
   - `:invalid-input` on self-loop
   - `:conflict` on duplicate edge
   - `:conflict` when adding the edge would introduce a cycle"
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
      (binding [*dag-id* (:dag/id d)]
        (f)))))

(use-fixtures :each manager-and-dag-fixture)

;------------------------------------------------------------------------------ Happy path

(deftest add-edge-anomaly-returns-updated-dag
  (testing "successful add returns the updated DAG, not an anomaly"
    (let [result (dag/add-edge-anomaly *manager* *dag-id*
                                       "repo-a" "repo-b"
                                       :library-before-consumer :sequential)]
      (is (not (anomaly/anomaly? result)))
      (is (= 1 (count (:dag/edges result)))))))

;------------------------------------------------------------------------------ Failure: DAG not found

(deftest add-edge-anomaly-not-found-when-dag-missing
  (testing "missing DAG yields :not-found anomaly"
    (let [missing-id (random-uuid)
          result (dag/add-edge-anomaly *manager* missing-id
                                       "repo-a" "repo-b"
                                       :library-before-consumer :sequential)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= missing-id (get-in result [:anomaly/data :dag-id]))))))

;------------------------------------------------------------------------------ Failure: from-repo not found

(deftest add-edge-anomaly-not-found-when-from-repo-missing
  (testing "missing from-repo yields :not-found anomaly"
    (let [result (dag/add-edge-anomaly *manager* *dag-id*
                                       "ghost" "repo-b"
                                       :library-before-consumer :sequential)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= "ghost" (get-in result [:anomaly/data :repo-name]))))))

;------------------------------------------------------------------------------ Failure: to-repo not found

(deftest add-edge-anomaly-not-found-when-to-repo-missing
  (testing "missing to-repo yields :not-found anomaly"
    (let [result (dag/add-edge-anomaly *manager* *dag-id*
                                       "repo-a" "ghost"
                                       :library-before-consumer :sequential)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= "ghost" (get-in result [:anomaly/data :repo-name]))))))

;------------------------------------------------------------------------------ Failure: self-loop

(deftest add-edge-anomaly-invalid-input-on-self-loop
  (testing "self-loop yields :invalid-input anomaly — input shape rejected"
    (let [result (dag/add-edge-anomaly *manager* *dag-id*
                                       "repo-a" "repo-a"
                                       :library-before-consumer :sequential)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "repo-a" (get-in result [:anomaly/data :repo-name]))))))

;------------------------------------------------------------------------------ Failure: duplicate edge

(deftest add-edge-anomaly-conflict-on-duplicate
  (testing "duplicate edge yields :conflict anomaly"
    (dag/add-edge-anomaly *manager* *dag-id*
                          "repo-a" "repo-b"
                          :library-before-consumer :sequential)
    (let [result (dag/add-edge-anomaly *manager* *dag-id*
                                       "repo-a" "repo-b"
                                       :library-before-consumer :sequential)]
      (is (anomaly/anomaly? result))
      (is (= :conflict (:anomaly/type result)))
      (is (= "repo-a" (get-in result [:anomaly/data :from])))
      (is (= "repo-b" (get-in result [:anomaly/data :to]))))))

;------------------------------------------------------------------------------ Failure: cycle

(deftest add-edge-anomaly-conflict-on-cycle
  (testing "cycle-introducing edge yields :conflict anomaly carrying cycle-nodes"
    (dag/add-edge-anomaly *manager* *dag-id*
                          "repo-a" "repo-b"
                          :library-before-consumer :sequential)
    (let [result (dag/add-edge-anomaly *manager* *dag-id*
                                       "repo-b" "repo-a"
                                       :library-before-consumer :sequential)]
      (is (anomaly/anomaly? result))
      (is (= :conflict (:anomaly/type result)))
      (is (set? (get-in result [:anomaly/data :cycle-nodes]))))))

;------------------------------------------------------------------------------ Throwing-variant compat

(deftest add-edge-still-throws-on-missing-from-repo
  (testing "deprecated throwing variant still throws ex-info on missing from-repo"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"From repo not found"
          (dag/add-edge *manager* *dag-id* "ghost" "repo-b"
                        :library-before-consumer :sequential)))))

(deftest add-edge-still-throws-on-self-loop
  (testing "deprecated throwing variant still throws on self-loop"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Self-loop not allowed"
          (dag/add-edge *manager* *dag-id* "repo-a" "repo-a"
                        :library-before-consumer :sequential)))))

(deftest add-edge-still-throws-on-cycle
  (testing "deprecated throwing variant still throws on cycle introduction"
    (dag/add-edge *manager* *dag-id* "repo-a" "repo-b"
                  :library-before-consumer :sequential)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create cycle"
          (dag/add-edge *manager* *dag-id* "repo-b" "repo-a"
                        :library-before-consumer :sequential)))))
