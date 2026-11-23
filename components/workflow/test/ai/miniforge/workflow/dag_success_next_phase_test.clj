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
(ns ai.miniforge.workflow.dag-success-next-phase-test
  "After a successful DAG run, the plan phase advances to the workflow's
   actual next phase. The DAG's work stands in for :implement, and is
   recorded under that name, only when :implement is that next phase."
  (:require
   [ai.miniforge.workflow.context :as context]
   [ai.miniforge.workflow.execution-dag :as dag]
   [ai.miniforge.workflow.isolation-test-support :as isolation]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} after-dag-success
  "Apply a successful, empty DAG result to a fresh context whose plan
   phase (the first of `phases`) is active. A phase is a keyword or a
   full phase config map."
  [phases]
  (let [workflow {:workflow/id :dag-success-next-phase
                  :workflow/version "1.0.0"
                  :workflow/pipeline (mapv (fn [phase]
                                             (if (map? phase) phase {:phase phase}))
                                           phases)}
        planned (assoc (context/create-context workflow {:task "Test"} {})
                       :execution/attempt-phase :plan)]
    (dag/apply-dag-success planned
                           {:artifacts [] :worktree-paths []
                            :metrics {:tokens 7 :cost-usd 0.0 :duration-ms 3}}
                           nil
                           context/transition-to-completed
                           context/transition-to-failed)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} plan-then-done-runs-done-test
  (testing "a [:plan :done] workflow advances to :done instead of completing past it"
    (let [result (after-dag-success [:plan :done])]
      (is (= :running (:execution/status result)))
      (is (= :done (:execution/current-phase result)))
      (testing "and records no result for an :implement phase it does not have"
        (is (not (contains? (:execution/phase-results result) :implement))))
      (testing "while keeping the DAG's result and spend"
        (is (some? (:execution/dag-result result)))
        (is (= 7 (get-in result [:execution/metrics :tokens])))))))

(deftest ^{:stratum 1} plan-then-implement-skips-implement-test
  (testing "when :implement follows :plan, the DAG's work stands in for it"
    (let [result (after-dag-success [:plan :implement :done])]
      (is (= :running (:execution/status result)))
      (is (= :done (:execution/current-phase result)))
      (is (= :completed (get-in result [:execution/phase-results :implement :status]))))))

(deftest ^{:stratum 1} plan-routed-to-implement-skips-implement-test
  (testing "the next phase is plan's :on-success target, not the next in pipeline order"
    ;; Pipeline order after :plan is :verify, but plan succeeds into
    ;; :implement, the phase the DAG's work stands in for.
    (let [result (after-dag-success [{:phase :plan :on-success :implement}
                                     {:phase :verify}
                                     {:phase :implement :on-fail :verify}
                                     {:phase :done}])]
      (is (= :running (:execution/status result)))
      (is (= :done (:execution/current-phase result)))
      (is (= :completed (get-in result [:execution/phase-results :implement :status]))))))

(use-fixtures :once isolation/with-isolated-host)
