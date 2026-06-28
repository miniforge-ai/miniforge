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

(ns ai.miniforge.phase-software-factory.pr-monitor-test
  (:require
   [ai.miniforge.phase-software-factory.pr-monitor :as sut]
   [ai.miniforge.workflow.interface.dag-prs :as dag-prs]
   [clojure.test :refer [deftest is testing]]))

(deftest enter-pr-monitor-returns-structured-failure-data
  (testing "monitor failure is a response value, not a synthetic exception"
    (let [failed-prs [{:pr/number 42 :reason :ci-failed}]
          train-state {:train-id "train-1"}]
      (with-redefs [dag-prs/create-train-from-dag-result
                    (fn [_dag-result _plan-tasks & _opts] train-state)
                    dag-prs/monitor-dag-prs
                    (fn [_train-state _pr-infos _opts]
                      {:success? false
                       :merged-prs []
                       :failed-prs failed-prs})]
        (let [result (sut/enter-pr-monitor
                      {:execution/dag-pr-infos [{:pr/number 42}]
                       :execution/dag-result {:tasks []}})
              phase-result (get-in result [:phase :result])]
          (is (= :failed (get-in result [:phase :status])))
          (is (= false (:success phase-result)))
          (is (= "PR monitoring failed" (get-in phase-result [:error :message])))
          (is (= failed-prs (get-in phase-result [:error :data :failed-prs])))
          (is (= train-state (:execution/train result))))))))

(deftest enter-pr-monitor-defaults-missing-failed-prs
  (testing "monitor failure keeps failed-prs as a vector when the monitor omits it"
    (let [train-state {:train-id "train-1"}]
      (with-redefs [dag-prs/create-train-from-dag-result
                    (fn [_dag-result _plan-tasks & _opts] train-state)
                    dag-prs/monitor-dag-prs
                    (fn [_train-state _pr-infos _opts]
                      {:success? false
                       :merged-prs []})]
        (let [result (sut/enter-pr-monitor
                      {:execution/dag-pr-infos [{:pr/number 42}]
                       :execution/dag-result {:tasks []}})]
          (is (= [] (get-in result [:phase :result :error :data :failed-prs]))))))))
