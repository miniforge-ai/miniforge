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
(ns ai.miniforge.bb-test-runner.coverage-paths-test
  "Unit tests for `coverage-paths`."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-test-runner.coverage-paths :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-classify-coverage-paths-excludes-resources
  (testing "merged classpath paths split into source and test roots"
    (let [classified (sut/classify-coverage-paths
                      ["tasks"
                       "test"
                       "mvp/src"
                       "components/agent/src"
                       "components/agent/resources"
                       "components/agent/test"])]
      (is (= ["test" "components/agent/test"] (:test-paths classified)))
      (is (= ["tasks" "mvp/src" "components/agent/src"] (:source-paths classified))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.coverage-paths-test)

  :leave-this-here)
