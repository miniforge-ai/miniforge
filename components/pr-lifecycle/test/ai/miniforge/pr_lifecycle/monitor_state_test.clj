;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.pr-lifecycle.monitor-state-test
  (:require
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-state :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest create-monitor-test
  (testing "shared defaults are loaded into the monitor config"
    (let [monitor (sut/create-monitor {:self-author "miniforge[bot]"})]
      (is (= 60000 (get-in @monitor [:config :poll-interval-ms])))
      (is (= "miniforge[bot]" (get-in @monitor [:config :self-author]))))))

(deftest load-budget-from-disk!-test
  (testing "persisted budgets are loaded and cached on the monitor"
    (let [monitor (atom {:config {} :budgets {}})
          persisted {:pr-number 42 :limits {} :comment-attempts {} :total-attempts 0
                     :started-at (java.util.Date.) :questions-answered 0 :fixes-pushed 0}]
      (with-redefs [budget/load-budget
                    (fn [pr-number]
                      (when (= 42 pr-number) persisted))]
        (is (= persisted (#'sut/load-budget-from-disk! monitor 42)))
        (is (= persisted (get-in @monitor [:budgets 42])))))))
