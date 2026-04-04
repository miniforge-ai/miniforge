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

(ns ai.miniforge.artifact.interface-test
  "Unit tests for artifact core helpers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.artifact.interface :as artifact]))

(deftest build-artifact-test
  (testing "build-artifact assembles required fields"
    (let [id (random-uuid)
          artifact-map (artifact/build-artifact
                        {:id id
                         :type :plan
                         :version "1.0.0"
                         :content {:k :v}})]
      (is (= id (:artifact/id artifact-map)))
      (is (= :plan (:artifact/type artifact-map)))
      (is (= [] (:artifact/parents artifact-map)))
      (is (= {} (:artifact/metadata artifact-map))))))

(deftest parent-child-helpers-test
  (testing "add-parent and add-child append identifiers"
    (let [base (artifact/build-artifact
                {:id (random-uuid)
                 :type :code
                 :version "1.0.0"
                 :content {}})
          parent-id (random-uuid)
          child-id (random-uuid)
          with-parent (artifact/add-parent base parent-id)
          with-child (artifact/add-child base child-id)]
      (is (= [parent-id] (:artifact/parents with-parent)))
      (is (= [child-id] (:artifact/children with-child))))))
