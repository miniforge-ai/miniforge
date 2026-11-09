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
(ns ai.miniforge.workflow.dag-monitor-cancel-test
  "A cancel ends PR-train monitoring: the poll wait ends on it, and no
   further merge is attempted."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.workflow.dag-monitor :as dag-monitor]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} poll-ms
  "A poll wait the cancel must cut short."
  10000)

(def ^{:stratum 0 :private true} prompt-ms
  "Upper bound for a monitor that a cancel ended."
  5000)

(def ^{:stratum 0 :private true} train-state
  {:train-id :train :manager :manager})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-cancel-before-the-poll-wait-ends-monitoring-test
  (testing "the cancel lands during a cycle; the 10 s wait after it does not run"
    (let [cs          (es/create-control-state)
          controllers {1 (atom {:status :monitoring-ci})}
          started     (System/currentTimeMillis)]
      (with-redefs [pr-train/get-ready-to-merge (fn [& _] (es/cancel! cs) [])
                    pr-train/get-train (constantly {:train/id :train})]
        (let [result (dag-monitor/monitor-pr-train
                      train-state controllers
                      {:control-state cs :monitor-poll-interval-ms poll-ms :max-monitor-cycles 1})]
          (is (< (- (System/currentTimeMillis) started) prompt-ms) "the 10 s poll wait was skipped")
          (is (false? (:success? result)))
          (is (true? (:stopped? result))))))))

(deftest ^{:stratum 1} a-run-with-no-stop-or-every-pr-done-is-reported-as-before-test
  (with-redefs [pr-train/get-train (constantly {:train/id :train})]
    (testing "no control state: every PR terminal gives the ordinary result"
      (let [result (dag-monitor/monitor-pr-train
                    train-state {1 (atom {:status :merged})} {:control-state nil})]
        (is (true? (:success? result)))
        (is (= [1] (:merged-prs result)))
        (is (nil? (:stopped? result)))))
    (testing "a stop after every PR finished does not change the result"
      (let [result (dag-monitor/monitor-pr-train
                    train-state {1 (atom {:status :merged})}
                    {:control-state (doto (es/create-control-state) es/cancel!)})]
        (is (true? (:success? result)))
        (is (nil? (:stopped? result)))))))

(deftest ^{:stratum 1} a-cancel-during-a-cycle-stops-further-merges-test
  (testing "the cancel lands while the first of two ready PRs merges"
    (let [cs          (es/create-control-state)
          controllers {1 (atom {:status :ready-to-merge})
                       2 (atom {:status :ready-to-merge})}
          attempts    (atom [])]
      (with-redefs [pr-train/get-ready-to-merge (constantly [1 2])
                    pr-train/get-train (constantly {:train/id :train})
                    pr-train/complete-merge (fn [& _] nil)
                    pr-lifecycle/attempt-merge! (fn [ctrl]
                                                  (swap! attempts conj ctrl)
                                                  (es/cancel! cs)
                                                  (swap! ctrl assoc :status :merged))]
        (let [result (dag-monitor/monitor-pr-train
                      train-state controllers
                      {:control-state cs :monitor-poll-interval-ms poll-ms :max-monitor-cycles 1})]
          (is (= 1 (count @attempts)) "PR 2 is not merged after the cancel")
          (is (true? (:stopped? result)))
          (is (= [1] (:merged-prs result)) "the PR merged before the cancel is reported"))))))
