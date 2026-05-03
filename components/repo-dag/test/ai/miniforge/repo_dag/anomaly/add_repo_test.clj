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

(ns ai.miniforge.repo-dag.anomaly.add-repo-test
  "Coverage for `dag/add-repo-anomaly` and its deprecated throwing
   sibling `dag/add-repo`. Three anomaly classes:
   - `:not-found`     — DAG missing
   - `:invalid-input` — `repo-config` fails schema validation (missing
                        `:repo/type`, malformed `:repo/url`, …)
   - `:conflict`      — repo with same name already exists"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.repo-dag.interface :as dag]
            [ai.miniforge.repo-dag.schema :as schema]))

(def ^:dynamic *manager* nil)

(defn manager-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (f)))

(use-fixtures :each manager-fixture)

(def repo-config
  {:repo/url "https://github.com/acme/tf"
   :repo/name "tf"
   :repo/type :terraform-module})

;------------------------------------------------------------------------------ Happy path

(deftest add-repo-anomaly-returns-updated-dag
  (testing "successful add returns the updated DAG, not an anomaly"
    (let [d (dag/create-dag *manager* "test-dag")
          result (dag/add-repo-anomaly *manager* (:dag/id d) repo-config)]
      (is (not (anomaly/anomaly? result)))
      (is (= 1 (count (:dag/repos result))))
      (is (= "tf" (-> result :dag/repos first :repo/name))))))

;------------------------------------------------------------------------------ Failure path: DAG not found

(deftest add-repo-anomaly-not-found-when-dag-missing
  (testing "missing DAG yields :not-found anomaly"
    (let [missing-id (random-uuid)
          result (dag/add-repo-anomaly *manager* missing-id repo-config)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= missing-id (get-in result [:anomaly/data :dag-id]))))))

;------------------------------------------------------------------------------ Failure path: schema-invalid repo-config

(deftest add-repo-anomaly-invalid-input-on-missing-repo-type
  (testing "repo-config without :repo/type yields :invalid-input anomaly —
            schema rejection short-circuits via make-repo-node-anomaly
            before the node is appended. Pre-fix this path threw
            ExceptionInfo via the deprecated throwing validate-schema and
            silently violated the anomaly-returning contract."
    (let [d (dag/create-dag *manager* "test-dag")
          bad-config {:repo/url "https://github.com/acme/x"
                      :repo/name "x"} ;; missing :repo/type
          result (dag/add-repo-anomaly *manager* (:dag/id d) bad-config)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (some? (get-in result [:anomaly/data :errors])))
      (is (= schema/RepoNode (get-in result [:anomaly/data :schema]))))))

;------------------------------------------------------------------------------ Failure path: duplicate

(deftest add-repo-anomaly-conflict-on-duplicate
  (testing "duplicate repo name yields :conflict anomaly"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo-anomaly *manager* (:dag/id d) repo-config)
          result (dag/add-repo-anomaly *manager* (:dag/id d) repo-config)]
      (is (anomaly/anomaly? result))
      (is (= :conflict (:anomaly/type result)))
      (is (= "tf" (get-in result [:anomaly/data :repo-name]))))))

;------------------------------------------------------------------------------ Throwing-variant compat

(deftest add-repo-still-throws-on-missing-dag
  (testing "deprecated throwing variant still throws ex-info on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/add-repo *manager* (random-uuid) repo-config)))))

(deftest add-repo-still-throws-on-duplicate
  (testing "deprecated throwing variant still throws on duplicate repo"
    (let [d (dag/create-dag *manager* "test-dag")]
      (dag/add-repo *manager* (:dag/id d) repo-config)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Repo already exists"
            (dag/add-repo *manager* (:dag/id d) repo-config))))))
