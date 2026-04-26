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

(ns ai.miniforge.workflow.comparison-test
  (:require
   [ai.miniforge.workflow.comparison :as comparison]
   [clojure.test :refer [deftest is testing]]))

(deftest execution-summary-test
  (testing "duration is derived from execution timestamps without workflow.state"
    (let [exec-state {:execution/id 1
                      :execution/workflow-id :wf
                      :execution/status :completed
                      :execution/metrics {:tokens 10 :cost-usd 1.0}
                      :execution/artifacts [{:artifact/id 1}]
                      :execution/errors []
                      :execution/started-at 100
                      :execution/ended-at 180}
          summary (comparison/execution-summary exec-state)]
      (is (= 80 (:execution/duration-ms summary)))
      (is (= 1 (:artifact-count summary)))
      (is (= 0 (:error-count summary))))))
