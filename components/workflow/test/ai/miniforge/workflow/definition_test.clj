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

(ns ai.miniforge.workflow.definition-test
  (:require
   [ai.miniforge.workflow.definition :as definition]
   [clojure.test :refer [deftest is testing]]))

(deftest execution-pipeline-test
  (testing "legacy phase definitions normalize into execution pipeline"
    (let [workflow {:workflow/entry-phase :plan
                    :workflow/phases
                    [{:phase/id :plan
                      :phase/gates [:syntax-valid]
                      :phase/next [{:target :implement}]}
                     {:phase/id :implement
                      :phase/next [{:target :done}]}
                     {:phase/id :done
                      :phase/next []}]}
          pipeline (definition/execution-pipeline workflow)]
      (is (= [{:phase :plan
               :gates [:syntax-valid]
               :terminal? false
               :on-success :implement}
              {:phase :implement
               :gates []
               :terminal? false
               :on-success :done}
              {:phase :done
               :gates []
               :terminal? true}]
             pipeline))))

  (testing "declared entry phase controls normalization order"
    (let [workflow {:workflow/entry :start
                    :workflow/phases
                    [{:phase/id :end
                      :phase/next []}
                     {:phase/id :start
                      :phase/next [{:target :end}]}]}
          pipeline (definition/execution-pipeline workflow)]
      (is (= [:start :end] (mapv :phase pipeline)))))

  (testing "unvisited phases are appended once without duplicating the primary path"
    (let [workflow {:workflow/entry :start
                    :workflow/phases
                    [{:phase/id :extra
                      :phase/next []}
                     {:phase/id :start
                      :phase/next [{:target :middle}]}
                     {:phase/id :middle
                      :phase/next [{:target :done}]}
                     {:phase/id :done
                      :phase/next []}]}
          pipeline (definition/execution-pipeline workflow)]
      (is (= [:start :middle :done :extra]
             (mapv :phase pipeline)))))

  (testing "existing pipeline is preserved"
    (let [workflow {:workflow/pipeline [{:phase :plan} {:phase :done}]}]
      (is (= (:workflow/pipeline workflow)
             (definition/execution-pipeline workflow))))))
