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

(ns ai.miniforge.pr-lifecycle.monitor-budget-test
  (:require
   [ai.miniforge.pr-lifecycle.monitor-budget :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Budget tests

(deftest record-fix-attempt-test
  (testing "defaults are loaded from monitor config"
    (let [budget (sut/create-budget 42)]
      (is (= 3 (get-in budget [:limits :max-fix-attempts-per-comment])))
      (is (= 10 (get-in budget [:limits :max-total-fix-attempts-per-pr])))
      (is (= 72 (get-in budget [:limits :abandon-after-hours])))))

  (testing "increments comment-local and total attempt counters"
    (let [budget (-> (sut/create-budget 42)
                     (sut/record-fix-attempt 1001)
                     (sut/record-fix-attempt 1001)
                     (sut/record-fix-attempt 1002))]
      (is (= 2 (get-in budget [:comment-attempts 1001])))
      (is (= 1 (get-in budget [:comment-attempts 1002])))
      (is (= 3 (:total-attempts budget)))
      (is (= 1 (sut/comment-attempts-remaining budget 1001)))
      (is (= 7 (sut/total-attempts-remaining budget))))))

(deftest any-budget-exhausted-test
  (testing "reports comment limit when a single comment runs out of attempts"
    (let [budget (-> (sut/create-budget 42)
                     (sut/record-fix-attempt 1001)
                     (sut/record-fix-attempt 1001)
                     (sut/record-fix-attempt 1001))]
      (is (= :comment-limit (sut/any-budget-exhausted? budget 1001)))))

  (testing "reports PR limit when the total attempt budget is exhausted"
    (let [budget (reduce (fn [state comment-id]
                           (sut/record-fix-attempt state comment-id))
                         (sut/create-budget 42 {:max-total-fix-attempts-per-pr 2})
                         [1001 1002])]
      (is (= :pr-limit (sut/any-budget-exhausted? budget 1003)))))

  (testing "reports time limit when abandon-after-hours has elapsed"
    (let [budget (assoc (sut/create-budget 42 {:abandon-after-hours 1})
                        :started-at
                        (java.util.Date. (- (System/currentTimeMillis) (* 2 3600000))))]
      (is (= :time-limit (sut/any-budget-exhausted? budget 1001))))))
