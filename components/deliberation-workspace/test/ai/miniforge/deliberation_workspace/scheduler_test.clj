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
(ns ai.miniforge.deliberation-workspace.scheduler-test
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.scheduler :as scheduler]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private roles [:proposer :skeptic :synthesizer])

(def ^{:stratum 0} ^:private eligibility
  {:conflict [:skeptic]
   :blocked-goal [:synthesizer]
   :stale-question [:proposer]})

(defn- ^{:stratum 0} obj [id type & {:keys [status links touched-at role]}]
  (cond-> (object/new-object {:id id :type type :statement (str "statement " id)
                              :role (or role :proposer) :activation "act-1"
                              :version 1 :links links})
    status (assoc :object/status status)
    touched-at (assoc :object/touched-at touched-at)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} workspace [objects & {:as extra}]
  (merge {:workspace/version 10
          :workspace/objects (into {} (map (juxt :object/id identity)) objects)
          :workspace/roles roles
          :workspace/eligibility eligibility
          :workspace/log []}
         extra))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} conflicts-outrank-everything
  (let [ws (workspace [(obj "conflict-1" :conflict)
                       (obj "question-1" :question :touched-at 1)])
        next (scheduler/next-activation ws)]
    (is (= :skeptic (:activation/role next)))
    (is (= :conflict (:activation/reason next)))
    (is (= "conflict-1" (:activation/target next)))))

(deftest ^{:stratum 2} blocked-goals-outrank-stale-questions
  (let [ws (workspace [(obj "goal-1" :goal)
                       (obj "blocker-1" :blocker :links {:depends-on #{"goal-1"}})
                       (obj "question-1" :question :touched-at 1)])]
    (is (= :blocked-goal (:activation/reason (scheduler/next-activation ws))))))

(deftest ^{:stratum 2} stale-questions-fire-only-past-the-threshold
  (testing "a question touched recently is not stale"
    (let [ws (workspace [(obj "question-1" :question :touched-at 9)])]
      (is (= :round-robin (:activation/reason (scheduler/next-activation ws))))))
  (testing "one untouched for the threshold is"
    (let [ws (workspace [(obj "question-1" :question :touched-at 6)])]
      (is (= :stale-question (:activation/reason (scheduler/next-activation ws))))))
  (testing "the threshold is manifest-configurable"
    (let [ws (workspace [(obj "question-1" :question :touched-at 6)]
                        :workspace/staleness-threshold 9)]
      (is (= :round-robin (:activation/reason (scheduler/next-activation ws)))))))

(deftest ^{:stratum 2} terminal-objects-never-trigger
  (let [ws (workspace [(obj "conflict-1" :conflict :status :resolved)
                       (obj "question-1" :question :status :answered
                            :touched-at 1)])]
    (is (= :round-robin (:activation/reason (scheduler/next-activation ws))))))

(deftest ^{:stratum 2} round-robin-follows-the-last-actor
  (let [ws (workspace [] :workspace/log [{:tx/role :proposer}])]
    (is (= :skeptic (:activation/role (scheduler/next-activation ws)))))
  (testing "it wraps around the role list"
    (let [ws (workspace [] :workspace/log [{:tx/role :synthesizer}])]
      (is (= :proposer (:activation/role (scheduler/next-activation ws)))))))

(deftest ^{:stratum 2} selection-is-deterministic
  (let [objects [(obj "conflict-2" :conflict) (obj "conflict-1" :conflict)]
        forward (workspace objects)
        reversed (workspace (reverse objects))]
    (is (= (scheduler/next-activation forward)
           (scheduler/next-activation reversed)))
    (is (= "conflict-1" (:activation/target (scheduler/next-activation forward)))
        "lowest id wins, not map iteration order")))
