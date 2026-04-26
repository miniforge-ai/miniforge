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

(ns ai.miniforge.workflow.dag-resilience-resume-test
  "Tests for DAG resume context restoration from authoritative checkpoints."
  (:require
   [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [clojure.test :refer [deftest is testing]]))

(deftest resume-context-test
  (testing "checkpoint DAG state is restored as resume context"
    (let [workflow-id (str (random-uuid))
          checkpoint-data {:machine-snapshot
                           {:execution/dag-result
                            {:completed-task-ids [:task-a :task-c]
                             :artifacts [{:code/id "art-1"}]
                             :pause-reason :rate-limit}}}]
      (with-redefs [checkpoint-store/load-checkpoint-data
                    (fn [_workflow-run-id _opts] checkpoint-data)]
        (let [ctx (resilience/resume-context workflow-id)]
          (is (= #{:task-a :task-c} (:pre-completed-ids ctx)))
          (is (= [{:code/id "art-1"}] (:pre-completed-artifacts ctx)))
          (is (true? (:resumed? ctx)))
          (is (= :checkpoint (:resume-source ctx)))))))

  (testing "checkpoint without DAG progress returns a non-resumed context"
    (with-redefs [checkpoint-store/load-checkpoint-data
                  (fn [_workflow-run-id _opts]
                    {:machine-snapshot {:execution/status :paused}})]
      (let [ctx (resilience/resume-context (str (random-uuid)))]
        (is (= #{} (:pre-completed-ids ctx)))
        (is (= [] (:pre-completed-artifacts ctx)))
        (is (false? (:resumed? ctx))))))

  (testing "missing checkpoint data returns a non-resumed context"
    (with-redefs [checkpoint-store/load-checkpoint-data (fn [_workflow-run-id _opts] nil)]
      (let [ctx (resilience/resume-context (str (random-uuid)))]
        (is (= #{} (:pre-completed-ids ctx)))
        (is (= [] (:pre-completed-artifacts ctx)))
        (is (false? (:resumed? ctx)))))))
