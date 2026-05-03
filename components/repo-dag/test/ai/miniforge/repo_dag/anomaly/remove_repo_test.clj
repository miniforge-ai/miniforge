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

(ns ai.miniforge.repo-dag.anomaly.remove-repo-test
  "Coverage for `dag/remove-repo-anomaly` and its deprecated throwing
   sibling `dag/remove-repo`. Only failure mode is `:not-found` — DAG missing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.repo-dag.interface :as dag]))

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

(deftest remove-repo-anomaly-returns-updated-dag
  (testing "successful remove returns the updated DAG, not an anomaly"
    (let [d (dag/create-dag *manager* "test-dag")
          _ (dag/add-repo-anomaly *manager* (:dag/id d) repo-config)
          result (dag/remove-repo-anomaly *manager* (:dag/id d) "tf")]
      (is (not (anomaly/anomaly? result)))
      (is (= 0 (count (:dag/repos result)))))))

;------------------------------------------------------------------------------ Failure path

(deftest remove-repo-anomaly-not-found-when-dag-missing
  (testing "missing DAG yields :not-found anomaly"
    (let [missing-id (random-uuid)
          result (dag/remove-repo-anomaly *manager* missing-id "tf")]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= missing-id (get-in result [:anomaly/data :dag-id]))))))

;------------------------------------------------------------------------------ Throwing-variant compat

(deftest remove-repo-still-throws-on-missing-dag
  (testing "deprecated throwing variant still throws ex-info on missing DAG"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DAG not found"
          (dag/remove-repo *manager* (random-uuid) "tf")))))
