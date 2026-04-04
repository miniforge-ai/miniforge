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

(ns ai.miniforge.agent.budget-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.budget :as budget]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical policy tests

(deftest role-budget-test
  (testing "returns canonical role budgets"
    (is (= {:tokens 100000 :cost-usd 5.0}
           (budget/role-budget :planner)))
    (is (= {:tokens 100000 :cost-usd 5.0}
           (budget/role-budget :implementer))))

  (testing "maps specialized role aliases to canonical role budgets"
    (is (= {:tokens 20000 :cost-usd 1.0}
           (budget/role-budget :releaser))))

  (testing "falls back to implementer budget for unknown roles"
    (is (= {:tokens 100000 :cost-usd 5.0}
           (budget/role-budget :unknown-role)))))

(deftest resolve-cost-budget-usd-test
  (testing "prefers explicit config budget"
    (is (= 0.75
           (budget/resolve-cost-budget-usd
            :planner
            {:budget {:cost-usd 0.75}}
            {:budget {:cost-usd 1.25}}))))

  (testing "falls back to context budget when config is unset"
    (is (= 1.25
           (budget/resolve-cost-budget-usd
            :planner
            {:max-tokens 1234}
            {:budget {:cost-usd 1.25}}))))

  (testing "falls back to canonical role budget when neither config nor context sets one"
    (is (= 2.0
           (budget/resolve-cost-budget-usd :tester {} {}))))

  (testing "uses canonical alias mapping before falling back"
    (is (= 1.0
           (budget/resolve-cost-budget-usd :releaser {} {}))))

  (testing "falls back to implementer budget for unknown roles"
    (is (= 5.0
           (budget/resolve-cost-budget-usd :unknown-role {} {})))))

(deftest apply-default-budget-test
  (testing "adds the canonical role budget when config does not provide one"
    (is (= {:model "claude-sonnet-4-6"
            :budget {:tokens 40000 :cost-usd 2.0}}
           (budget/apply-default-budget
            :tester
            {:model "claude-sonnet-4-6"}))))

  (testing "preserves explicit config budget"
    (is (= {:budget {:tokens 10 :cost-usd 0.1}}
           (budget/apply-default-budget
            :planner
            {:budget {:tokens 10 :cost-usd 0.1}})))))
