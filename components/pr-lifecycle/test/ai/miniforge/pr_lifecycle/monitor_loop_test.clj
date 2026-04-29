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

(ns ai.miniforge.pr-lifecycle.monitor-loop-test
  (:require
   [ai.miniforge.pr-lifecycle.monitor-loop :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest actionable-comments-test
  (testing "change requests and questions are routed, approvals are not"
    (let [classified {:change-requests [{:comment {:id 1}}]
                      :questions [{:comment {:id 2}}]
                      :approvals [{:comment {:id 3}}]}]
      (is (= [1 2]
             (mapv #(get-in % [:comment :id])
                   (#'sut/actionable-comments classified)))))))

(deftest time-budget-stop-result-test
  (testing "time budget exhaustion yields a stopped cycle result"
    (let [result (#'sut/time-budget-stop-result 42)]
      (is (= 0 (:processed result)))
      (is (true? (:stopped? result)))
      (is (= :time-budget-exhausted (:stop-reason result))))))
