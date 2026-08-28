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
(ns ai.miniforge.deliberation-workspace.termination-test
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.termination :as termination]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} obj [id type & {:keys [status]}]
  (cond-> (object/new-object {:id id :type type :statement (str "statement " id)
                              :role :proposer :activation "act-1" :version 1})
    status (assoc :object/status status)))

(defn- ^{:stratum 0} workspace [objects & {:as extra}]
  (merge {:workspace/version 10
          :workspace/objects (into {} (map (juxt :object/id identity)) objects)
          :workspace/roles [:proposer :skeptic :synthesizer]
          :workspace/eligibility {}
          :workspace/log []}
         extra))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} success-closes-before-budget-exhaustion
  (let [ws (workspace [(obj "goal-1" :goal :status :accepted)]
                      :workspace/budget {:activations 5}
                      :workspace/spent {:activations 5})]
    (is (= :success (:termination/rule (termination/closing-rule ws))))))

(deftest ^{:stratum 1} budget-exhaustion-forces-a-synthesis
  (let [ws (workspace [(obj "goal-1" :goal)]
                      :workspace/budget {:activations 5}
                      :workspace/spent {:activations 5})
        result (termination/closing-rule ws)]
    (is (= :budget-boundary (:termination/rule result)))
    (is (= :activations (:termination/detail result)))
    (is (:termination/forced-synthesis result))))

(deftest ^{:stratum 1} quiescence-closes-a-run-that-stopped-producing
  (let [ws (workspace [(obj "goal-1" :goal)] :workspace/quiet-rounds 3)]
    (is (= :quiescence (:termination/rule (termination/closing-rule ws))))))

(deftest ^{:stratum 1} a-live-run-does-not-terminate
  (let [ws (workspace [(obj "goal-1" :goal)] :workspace/log [{:tx/role :proposer}])]
    (is (nil? (termination/closing-rule ws)))))

(deftest ^{:stratum 1} deadlock-closes-a-run-with-no-eligible-role
  (let [ws (workspace [(obj "goal-1" :goal)] :workspace/roles [])]
    (is (= :deadlock (:termination/rule (termination/closing-rule ws))))))

(deftest ^{:stratum 1} cost-exhaustion-closes-the-run-the-same-way
  (testing "the cost ceiling is a budget dimension like any other"
    (let [ws (workspace [(obj "goal-1" :goal)]
                        :workspace/budget {:cost 25.0}
                        :workspace/spent {:cost 25.0})
          result (termination/closing-rule ws)]
      (is (= :budget-boundary (:termination/rule result)))
      (is (= :cost (:termination/detail result)))
      (is (:termination/forced-synthesis result))))
  (testing "spend below the ceiling does not close the run"
    (let [ws (workspace [(obj "goal-1" :goal)]
                        :workspace/budget {:cost 25.0}
                        :workspace/spent {:cost 24.0}
                        :workspace/log [{:tx/role :proposer}])]
      (is (nil? (termination/closing-rule ws)))))
  (testing "activations are checked before cost, so the detail is unambiguous"
    (let [ws (workspace [(obj "goal-1" :goal)]
                        :workspace/budget {:activations 5 :cost 25.0}
                        :workspace/spent {:activations 5 :cost 25.0})]
      (is (= :activations (:termination/detail (termination/closing-rule ws)))))))
