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

(ns ai.miniforge.tui-views.view.supervisory-domain-test
  "Unit tests for the supervisory projection builders — N5-delta §3-5.

   Tests Layer 0 (governance state derivation), Layer 1 (projection builders),
   and Layer 2 (attention derivation) in supervisory.clj.
   All functions are pure; no I/O, no side effects."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.tui-views.view.project.supervisory :as sut]))

;------------------------------------------------------------------------------ Helpers

(defn- make-eval
  "Build a minimal PolicyEvaluation for tests."
  ([pr-id result]
   (make-eval pr-id result (java.util.Date.)))
  ([pr-id result evaluated-at]
   {:eval/id           (random-uuid)
    :eval/pr-id        pr-id
    :eval/result       result
    :eval/evaluated-at evaluated-at}))

(defn- make-waiver [eval-id]
  {:waiver/id    (random-uuid)
   :waiver/eval-id eval-id})

(defn- make-wf
  "Build a minimal workflow row for model."
  ([id status] (make-wf id status nil))
  ([id status name]
   {:id         id
    :name       (or name (str "wf-" id))
    :status     status
    :phase      nil
    :started-at (java.util.Date.)
    :agents     {}}))

(defn- make-pr
  "Build a minimal PR item for model."
  ([repo number] (make-pr repo number {}))
  ([repo number extra]
   (merge {:pr/repo   repo
           :pr/number number}
          extra)))

;------------------------------------------------------------------------------ Layer 0: derive-governance-state

(deftest derive-governance-state-no-evals-test
  (testing "no evaluations → :not-evaluated"
    (is (= :not-evaluated
           (sut/derive-governance-state [:org/repo 1] [] [])))
    (is (= :not-evaluated
           (sut/derive-governance-state [:org/repo 1] nil nil)))))

(deftest derive-governance-state-passing-test
  (testing "latest eval is :pass → :policy-passing"
    (let [ev (make-eval [:org/repo 1] :pass)]
      (is (= :policy-passing
             (sut/derive-governance-state [:org/repo 1] [ev] []))))))

(deftest derive-governance-state-failing-test
  (testing "latest eval is :fail, no waiver → :policy-failing"
    (let [ev (make-eval [:org/repo 1] :fail)]
      (is (= :policy-failing
             (sut/derive-governance-state [:org/repo 1] [ev] []))))))

(deftest derive-governance-state-waived-test
  (testing "latest eval is :fail, matching waiver → :waived"
    (let [ev     (make-eval [:org/repo 1] :fail)
          waiver (make-waiver (:eval/id ev))]
      (is (= :waived
             (sut/derive-governance-state [:org/repo 1] [ev] [waiver]))))))

(deftest derive-governance-state-escalated-test
  (testing ":eval/escalated? flag → :escalated regardless of result"
    (let [ev (assoc (make-eval [:org/repo 1] :fail) :eval/escalated? true)]
      (is (= :escalated
             (sut/derive-governance-state [:org/repo 1] [ev] []))))))

(deftest derive-governance-state-uses-latest-test
  (testing "uses most-recent evaluated-at, not insertion order"
    (let [t1     (java.util.Date. 1000)
          t2     (java.util.Date. 2000)
          old-ev (make-eval [:org/repo 1] :fail t1)
          new-ev (make-eval [:org/repo 1] :pass t2)]
      ;; Insert old first, new second
      (is (= :policy-passing
             (sut/derive-governance-state [:org/repo 1] [old-ev new-ev] [])))
      ;; Insert new first, old second — result should be the same
      (is (= :policy-passing
             (sut/derive-governance-state [:org/repo 1] [new-ev old-ev] []))))))

(deftest derive-governance-state-filters-by-pr-id-test
  (testing "evals for other PRs are ignored"
    (let [other-ev (make-eval [:org/repo 99] :fail)
          my-ev    (make-eval [:org/repo 1] :pass)]
      (is (= :policy-passing
             (sut/derive-governance-state [:org/repo 1] [other-ev my-ev] []))))))

;------------------------------------------------------------------------------ Layer 1: workflow-ticker

(deftest workflow-ticker-empty-model-test
  (testing "empty model returns empty vector"
    (is (= [] (sut/workflow-ticker {})))
    (is (= [] (sut/workflow-ticker {:workflows []})))))

(deftest workflow-ticker-returns-vector-test
  (testing "always returns a vector"
    (let [result (sut/workflow-ticker {:workflows [(make-wf (random-uuid) :running)]})]
      (is (vector? result)))))

(deftest workflow-ticker-row-shape-test
  (testing "each row has required keys"
    (let [id     (random-uuid)
          result (sut/workflow-ticker {:workflows [(make-wf id :running "my-workflow")]})]
      (is (= 1 (count result)))
      (let [row (first result)]
        (is (contains? row :id))
        (is (contains? row :key))
        (is (contains? row :status))
        (is (contains? row :phase))
        (is (contains? row :duration))
        (is (contains? row :agent-msg))
        (is (= id (:id row)))
        (is (= :running (:status row)))))))

(deftest workflow-ticker-active-first-sort-test
  (testing "running workflows sort before completed ones"
    (let [done-id    (random-uuid)
          running-id (random-uuid)
          model {:workflows [(make-wf done-id :completed "done-wf")
                             (make-wf running-id :running "running-wf")]}
          result (sut/workflow-ticker model)]
      (is (= running-id (:id (first result))))
      (is (= done-id (:id (second result)))))))

;------------------------------------------------------------------------------ Layer 1: pr-train-strip

(deftest pr-train-strip-empty-model-test
  (testing "empty model returns safe defaults"
    (let [result (sut/pr-train-strip {})]
      (is (false? (:train-active? result)))
      (is (= 0 (:train-merged result)))
      (is (= 0 (:train-total result)))
      (is (= 0 (:fleet-open result)))
      (is (= 0 (:fleet-ready result)))
      (is (= 0 (:fleet-monitored result))))))

(deftest pr-train-strip-counts-open-prs-test
  (testing "fleet-open counts all pr-items"
    (let [prs    [(make-pr "org/r" 1) (make-pr "org/r" 2) (make-pr "org/r" 3)]
          result (sut/pr-train-strip {:pr-items prs})]
      (is (= 3 (:fleet-open result))))))

(deftest pr-train-strip-counts-ready-prs-test
  (testing "fleet-ready counts PRs with readiness/ready? true"
    (let [ready-pr   (make-pr "org/r" 1 {:pr/readiness {:readiness/ready? true}})
          unready-pr (make-pr "org/r" 2 {:pr/readiness {:readiness/ready? false}})
          result     (sut/pr-train-strip {:pr-items [ready-pr unready-pr]})]
      (is (= 1 (:fleet-ready result))))))

(deftest pr-train-strip-counts-monitored-prs-test
  (testing "fleet-monitored counts PRs with pr/monitor-active? true"
    (let [active-pr   (make-pr "org/r" 1 {:pr/monitor-active? true})
          inactive-pr (make-pr "org/r" 2)]
      (is (= 1 (:fleet-monitored (sut/pr-train-strip {:pr-items [active-pr inactive-pr]})))))))

(deftest pr-train-strip-active-train-test
  (testing "train-active? true when active-train-id matches a train"
    (let [train-id (random-uuid)
          train    {:train/id       train-id
                    :train/progress {:merged 3 :total 8}}
          model    {:active-train-id train-id
                    :trains          [train]
                    :pr-items        []}
          result   (sut/pr-train-strip model)]
      (is (true? (:train-active? result)))
      (is (= 3 (:train-merged result)))
      (is (= 8 (:train-total result))))))

;------------------------------------------------------------------------------ Layer 1: policy-health

(deftest policy-health-empty-prs-test
  (testing "no PRs → pass-rate 1.0, zeros"
    (let [result (sut/policy-health {})]
      (is (= 1.0 (:pass-rate result)))
      (is (= 0 (:total-evaluations result)))
      (is (= 0 (:passing-evaluations result)))
      (is (= {} (:violations-by-category result))))))

(deftest policy-health-pass-rate-calculation-test
  (testing "pass-rate = passing / total"
    (let [pr  (make-pr "org/r" 1)
          ev1 (make-eval [nil 1] :pass)
          ev2 (make-eval [nil 1] :fail)
          result (sut/policy-health {:pr-items       [pr]
                                     :policy-evaluations [ev1 ev2]
                                     :waivers        []})]
      (is (= 0.5 (:pass-rate result)))
      (is (= 2 (:total-evaluations result)))
      (is (= 1 (:passing-evaluations result))))))

(deftest policy-health-violation-categories-test
  (testing "violations-by-category aggregates across all evals"
    (let [pr  (make-pr "org/r" 1)
          ev1 (assoc (make-eval [nil 1] :fail)
                     :eval/violations [{:violation/id (random-uuid)
                                        :violation/category "lint"}
                                       {:violation/id (random-uuid)
                                        :violation/category "lint"}])
          ev2 (assoc (make-eval [nil 1] :fail)
                     :eval/violations [{:violation/id (random-uuid)
                                        :violation/category "security"}])
          result (sut/policy-health {:pr-items           [pr]
                                     :policy-evaluations [ev1 ev2]
                                     :waivers            []})]
      (is (= {"lint" 2 "security" 1} (:violations-by-category result))))))

(deftest policy-health-governance-counts-test
  (testing "governance-counts maps each state"
    (let [pr       (make-pr "org/r" 1)
          pr-id    [(:pr/repo pr) (:pr/number pr)]
          ev-pass  (make-eval pr-id :pass)
          result   (sut/policy-health {:pr-items           [pr]
                                       :policy-evaluations [ev-pass]
                                       :waivers            []})]
      (is (= 1 (get-in result [:governance-counts :policy-passing])))
      (is (= 0 (get-in result [:governance-counts :policy-failing]))))))

;------------------------------------------------------------------------------ Layer 2: attention

(deftest attention-empty-model-test
  (testing "empty model returns empty vector"
    (is (= [] (sut/attention {})))
    (is (vector? (sut/attention {})))))

(deftest attention-failed-workflow-test
  (testing "failed workflow generates :critical attention item"
    (let [wf     {:id (random-uuid) :name "test-wf" :status :failed}
          result (sut/attention {:workflows [wf]})]
      (is (= 1 (count result)))
      (is (= :critical (:attention/severity (first result))))
      (is (= :workflow (:attention/source-type (first result))))
      (is (str/includes? (:attention/summary (first result)) "test-wf")))))

(deftest attention-budget-exhausted-test
  (testing "budget-exhausted PR generates :critical attention item"
    (let [pr     (make-pr "org/r" 1 {:pr/monitor-budget-exhausted? true})
          result (sut/attention {:pr-items [pr]})]
      (is (= 1 (count result)))
      (is (= :critical (:attention/severity (first result))))
      (is (= :pr-monitor (:attention/source-type (first result)))))))

(deftest attention-budget-warning-test
  (testing "budget-warning PR generates :warning attention item"
    (let [pr     (make-pr "org/r" 1 {:pr/monitor-budget-warning? true})
          result (sut/attention {:pr-items [pr]})]
      (is (= 1 (count result)))
      (is (= :warning (:attention/severity (first result)))))))

(deftest attention-escalated-test
  (testing "escalated PR generates :critical attention item"
    (let [pr     (make-pr "org/r" 1 {:pr/monitor-escalated? true})
          result (sut/attention {:pr-items [pr]})]
      (is (= 1 (count result)))
      (is (= :critical (:attention/severity (first result)))))))

(deftest attention-explicit-items-test
  (testing "explicit :attention-items in model are included"
    (let [item {:attention/id       (random-uuid)
                :attention/severity :info
                :attention/summary  "Manual note"
                :attention/source-type :manual
                :attention/source-id nil}
          result (sut/attention {:attention-items [item]})]
      (is (= 1 (count result)))
      (is (= :info (:attention/severity (first result)))))))

(deftest attention-severity-ordering-test
  (testing "critical items sort before warnings which sort before info"
    (let [warn-pr   (make-pr "org/r" 1 {:pr/monitor-budget-warning? true})
          crit-wf   {:id (random-uuid) :name "fail-wf" :status :failed}
          info-item {:attention/id       (random-uuid)
                     :attention/severity :info
                     :attention/summary  "Info note"
                     :attention/source-type :manual
                     :attention/source-id nil}
          result (sut/attention {:workflows      [crit-wf]
                                  :pr-items       [warn-pr]
                                  :attention-items [info-item]})]
      (is (= 3 (count result)))
      (is (= :critical (:attention/severity (first result))))
      (is (= :warning  (:attention/severity (second result))))
      (is (= :info     (:attention/severity (nth result 2)))))))

(deftest attention-always-returns-vector-test
  (testing "all combinations return vectors, never nil"
    (are [model] (vector? (sut/attention model))
      {}
      {:workflows []}
      {:pr-items []}
      {:attention-items []}
      {:workflows [(make-wf (random-uuid) :running)]})))
