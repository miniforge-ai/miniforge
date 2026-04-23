;; Title: Miniforge.ai
;; Subtitle: career workflow catalog composition test
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

(ns ai.miniforge.workflow-career.catalog-test
  (:require [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.registry :as registry]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  (fn [f]
    (loader/clear-cache!)
    (registry/clear-registry!)
    (f)
    (loader/clear-cache!)
    (registry/clear-registry!)))

(deftest test-career-profile-loads-from-resource
  (testing "career-profile workflow resource is loadable through the shared loader"
    (let [workflow (loader/load-from-resource :career-profile "1.0.0")]
      (is (some? workflow))
      (is (= :career-profile (:workflow/id workflow)))
      (is (= "1.0.0" (:workflow/version workflow)))
      (is (= ["kg.entity_lookup"
              "kg.datalog_query"
              "kg.fulltext_search"]
             (get-in workflow [:workflow/config :tool-surface :required-tools])))
      (is (= :career/profile-packet
             (get-in workflow [:workflow/config :output :artifact/type]))))))

(deftest test-career-profile-is-discoverable
  (testing "registry discovery sees career-profile when the component is on the classpath"
    (let [workflow-ids (->> (registry/discover-workflows-from-resources)
                            (map :workflow/id)
                            set)]
      (is (contains? workflow-ids :career-profile)))))
