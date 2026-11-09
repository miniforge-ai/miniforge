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
(ns ai.miniforge.workflow.cost-unavailable-metrics-test
  "Spend with no known price marks the workflow's metrics
   :cost-unavailable?, through phase merges, DAG roll-ups and a real
   pipeline run, so the summed :cost-usd is not read as the measured cost."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.context :as context]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.execution-dag :as execution-dag]
   [ai.miniforge.workflow.isolation-test-support :as isolation]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} start {:tokens 0 :cost-usd 0.0 :duration-ms 0})

(def ^{:stratum 0} probe-budget {:tokens 1 :iterations 1 :time-seconds 1})

(deftest ^{:stratum 0} pipeline-run-reports-unpriced-spend-test
  (testing "a run whose phase spent unpriced tokens ends with the mark"
    (let [result (runner/run-pipeline {:workflow/id :unpriced
                                       :workflow/version "1.0.0"
                                       :workflow/pipeline [{:phase :unpriced-spend-probe}]}
                                      {:task "probe"})]
      (is (= :completed (:execution/status result)))
      (is (pos? (get-in result [:execution/metrics :tokens])))
      (is (true? (get-in result [:execution/metrics :cost-unavailable?]))))))

;------------------------------------------------------------------------------ Layer 1

(defmethod ^{:stratum 1} phase/get-phase-interceptor-method :unpriced-spend-probe
  [config]
  {:name ::unpriced-spend-probe
   :config (phase/merge-with-defaults config)
   :enter #(phase/enter-context % :unpriced-spend-probe :workflow-tester []
                                probe-budget (System/currentTimeMillis) nil)
   ;; A phase whose agent reported tokens but no cost (an unpriced model).
   :leave #(-> %
               (assoc-in [:phase :status] :completed)
               (assoc-in [:phase :metrics] {:tokens 2400 :duration-ms 5})
               (assoc-in [:phase :result] (phase/success nil "ok" {:tokens 2400})))
   :error (fn [ctx _ex] ctx)})

(deftest ^{:stratum 1} merge-metrics-marks-unpriced-spend-test
  (testing "tokens without a cost leave the total incomplete"
    (let [merged (context/merge-metrics start {:tokens 2400 :duration-ms 5})]
      (is (true? (:cost-unavailable? merged)))
      (is (= 2400 (:tokens merged)))
      (is (= 0.0 (:cost-usd merged)))))
  (testing "a later priced phase does not clear the mark"
    (let [merged (-> start
                     (context/merge-metrics {:tokens 2400 :duration-ms 5})
                     (context/merge-metrics {:tokens 100 :cost-usd 0.01 :duration-ms 5}))]
      (is (true? (:cost-unavailable? merged)))
      (is (= 0.01 (:cost-usd merged)))))
  (testing "a rolled-up run that carries the mark passes it on"
    (is (true? (:cost-unavailable? (context/merge-metrics
                                    start {:tokens 10 :cost-usd 0.5
                                           :cost-unavailable? true})))))
  (testing "priced spend, and phases that spent nothing, leave no mark"
    (is (not (contains? (context/merge-metrics start {:tokens 100 :cost-usd 0.01})
                        :cost-unavailable?)))
    (is (not (contains? (context/merge-metrics start {:tokens 0 :duration-ms 9})
                        :cost-unavailable?)))))

(deftest ^{:stratum 1} dag-roll-up-carries-the-mark-test
  (testing "one unpriced task marks the DAG result and the parent run"
    (let [agg (dag-orch/aggregate-results
               {:t1 (dag/ok {:metrics {:tokens 100 :cost-usd 0.01 :duration-ms 1}})
                :t2 (dag/err :task-execution-failed "boom"
                             {:metrics {:tokens 900 :cost-usd 0.0 :duration-ms 1
                                        :cost-unavailable? true}})})
          dag-result (dag-plan/dag-execution-result 1 1 [] agg)
          rolled (#'execution-dag/roll-dag-metrics-into-execution
                  {:execution/metrics start} dag-result)]
      (is (true? (:cost-unavailable? agg)))
      (is (true? (get-in dag-result [:metrics :cost-unavailable?])))
      (is (true? (get-in rolled [:execution/metrics :cost-unavailable?])))
      (is (= 1000 (get-in rolled [:execution/metrics :tokens])))))
  (testing "all-priced tasks leave no mark"
    (let [agg (dag-orch/aggregate-results
               {:t1 (dag/ok {:metrics {:tokens 100 :cost-usd 0.01 :duration-ms 1}})})]
      (is (not (contains? agg :cost-unavailable?)))
      (is (not (contains? (:metrics (dag-plan/dag-execution-result 1 0 [] agg))
                          :cost-unavailable?))))))

(phase/register-phase-defaults! :unpriced-spend-probe
                                {:phase :unpriced-spend-probe :agent :workflow-tester
                                 :gates [] :budget probe-budget})

(use-fixtures :once isolation/with-isolated-host)
