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
(ns ai.miniforge.workflow.dag-task-execution-test
  "Tests for workflow-result->dag-result's error-shape handling: a plain
   string sub-workflow error vs. a v2 multi-parent merge anomaly map
   short-circuited by run-mini-workflow before the sub-workflow runs."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.dag-merge-anomaly :as merge-anomaly]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.dag-task-execution :as dag-task-execution]))

;------------------------------------------------------------------------------ Layer 0

;; workflow-result->dag-result tests
(deftest ^{:stratum 0} workflow-result->dag-result-string-error-test
  (testing "plain string error (the common sub-workflow-failed path) passes
            through unchanged as dag/err's message"
    (let [wf-result (dag-plan/workflow-failure "Sub-workflow failed" dag-plan/zero-metrics)
          result    (dag-task-execution/workflow-result->dag-result :task-a "desc" wf-result)]
      (is (= "Sub-workflow failed" (get-in result [:error :message])))
      (is (not (contains? (get-in result [:error :data]) :merge/anomaly))))))

(deftest ^{:stratum 0} workflow-result->dag-result-merge-anomaly-test
  (testing "a v2 multi-parent merge anomaly map (run-mini-workflow's
            short-circuit before the sub-workflow runs) is unpacked to its
            own :anomaly/message for dag/err's message slot — not passed
            through as a raw map, which would make unwrap-anomaly fall back
            to its generic 'Unwrap called on error result' text — while the
            full anomaly stays reachable under :merge/anomaly for
            :anomaly/category-based classification downstream."
    (let [anomaly   (merge-anomaly/branch-unresolvable-anomaly
                     {:task/id :parent-a} {:exit 128 :err "fatal: bad revision"})
          wf-result (dag-plan/workflow-failure anomaly nil)
          result    (dag-task-execution/workflow-result->dag-result :task-c "desc" wf-result)]
      (is (= (:anomaly/message anomaly) (get-in result [:error :message]))
          "message is the anomaly's own text, not the raw map")
      (is (= anomaly (get-in result [:error :data :merge/anomaly]))
          "the full typed anomaly survives for downstream :anomaly/category classification")
      (is (= :anomalies/dag-multi-parent-branch-unresolvable
             (get-in result [:error :data :merge/anomaly :anomaly/category]))))))
