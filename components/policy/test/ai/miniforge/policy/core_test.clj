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

(ns ai.miniforge.policy.core-test
  "Tests for policy core functionality."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.policy.core :as policy]))

(deftest calculate-budget-usage-test
  (testing "Budget within limits"
    (let [result (policy/calculate-budget-usage 5000 50000)]
      (is (= 5000 (:used result)))
      (is (= 50000 (:limit result)))
      (is (= 45000 (:remaining result)))
      (is (= 10.0 (:percentage-used result)))
      (is (true? (:within-budget? result)))))

  (testing "Budget exceeded"
    (let [result (policy/calculate-budget-usage 60000 50000)]
      (is (= 60000 (:used result)))
      (is (= 50000 (:limit result)))
      (is (= -10000 (:remaining result)))
      (is (= 120.0 (:percentage-used result)))
      (is (false? (:within-budget? result)))))

  (testing "Zero limit"
    (let [result (policy/calculate-budget-usage 100 0)]
      (is (= 100.0 (:percentage-used result)))
      (is (false? (:within-budget? result))))))

(deftest check-budget-test
  (testing "Token budget check within limits"
    (let [context {:tokens-used 5000
                   :budget {:tokens 50000}}
          result (policy/check-budget context :tokens)]
      (is (true? (:within-budget? result)))
      (is (= 5000 (:used result)))
      (is (= 45000 (:remaining result)))))

  (testing "Token budget check exceeded"
    (let [context {:tokens-used 60000
                   :budget {:tokens 50000}}
          result (policy/check-budget context :tokens)]
      (is (false? (:within-budget? result)))
      (is (= -10000 (:remaining result))))))

(deftest enforce-policy-test
  (testing "Max file size policy violation"
    (let [artifact {:artifact/type :code
                    :artifact/lines 600}
          rules [{:type :max-file-size :limit 500}]
          result (policy/enforce-policy artifact rules)]
      (is (false? (:compliant? result)))
      (is (= 1 (count (:violations result))))
      (is (= :max-file-size (-> result :violations first :rule)))))

  (testing "No policy violations"
    (let [artifact {:artifact/type :code
                    :artifact/lines 400}
          rules [{:type :max-file-size :limit 500}]
          result (policy/enforce-policy artifact rules)]
      (is (true? (:compliant? result)))
      (is (empty? (:violations result)))))

  (testing "Multiple policy violations"
    (let [artifact {:artifact/type :code
                    :artifact/lines 600
                    :artifact/content "(defn foo [] ;; TODO: implement\n  42)"}
          rules [{:type :max-file-size :limit 500}
                 {:type :no-todos}]
          result (policy/enforce-policy artifact rules)]
      (is (false? (:compliant? result)))
      (is (= 2 (count (:violations result)))))))

(deftest create-budget-test
  (testing "Create budget configuration"
    (let [budget (policy/create-budget :tokens 50000)]
      (is (= :tokens (:budget/type budget)))
      (is (= 50000 (:budget/limit budget)))
      (is (:budget/created-at budget)))))
