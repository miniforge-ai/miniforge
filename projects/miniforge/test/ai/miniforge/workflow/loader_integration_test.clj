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

(ns ai.miniforge.workflow.loader-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.loader :as loader]))

(deftest load-workflow-integration-test
  (testing "complete workflow loading and validation flow"
    (loader/clear-cache!)
    (let [result (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
      (is (map? result))
      (is (contains? result :workflow))
      (is (contains? result :source))
      (is (contains? result :validation))
      (let [workflow (:workflow result)]
        (is (= :canonical-sdlc-v1 (:workflow/id workflow)))
        (is (= "1.0.0" (:workflow/version workflow)))
        (is (= :feature (:workflow/type workflow)))
        (is (vector? (:workflow/phases workflow)))
        (is (seq (:workflow/phases workflow)))
        (is (keyword? (:workflow/entry-phase workflow)))
        (is (vector? (:workflow/exit-phases workflow))))
      (is (true? (get-in result [:validation :valid?])))
      (is (empty? (get-in result [:validation :errors])))
      (let [result2 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
        (is (= :cache (:source result2)))
        (is (= (:workflow result) (:workflow result2)))))))
