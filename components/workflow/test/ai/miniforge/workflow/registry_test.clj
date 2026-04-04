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

(ns ai.miniforge.workflow.registry-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.registry :as registry]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)
    (registry/clear-registry!)))

(deftest discover-workflows-from-resources-test
  (testing "workflow discovery reflects the workflows on the active classpath"
    (let [workflow-ids (->> (registry/discover-workflows-from-resources)
                            (map :workflow/id)
                            set)]
      (is (contains? workflow-ids :financial-etl))
      (is (contains? workflow-ids :simple-test-v1))
      (is (contains? workflow-ids :canonical-sdlc-v1)))))

(deftest initialize-registry!-test
  (testing "registry initialization no longer depends on a shared registry config file"
    (let [count (registry/initialize-registry!)
          workflow-ids (set (registry/list-workflow-ids))]
      (is (pos? count))
      (is (contains? workflow-ids :financial-etl))
      (is (contains? workflow-ids :standard-sdlc))
      (is (contains? workflow-ids :simple)))))
