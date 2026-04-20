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

(ns ai.miniforge.agent.role-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.agent.role-config :as role-config]))

(deftest role-defaults-loaded-test
  (testing "EDN config loads at namespace load and contains expected roles"
    (is (map? role-config/role-defaults))
    (doseq [role [:planner :architect :implementer :tester :reviewer
                  :sre :security :release :historian :operator]]
      (is (contains? role-config/role-defaults role)
          (str "missing role: " role)))))

(deftest role-default-shape-test
  (testing "every role default carries :temperature, :max-tokens, :budget"
    (doseq [[role defaults] role-config/role-defaults]
      (is (number? (:temperature defaults)) (str role " :temperature"))
      (is (<= 0.0 (:temperature defaults) 1.0) (str role " temperature in [0,1]"))
      (is (pos-int? (:max-tokens defaults)) (str role " :max-tokens"))
      (let [budget (:budget defaults)]
        (is (map? budget) (str role " :budget map"))
        (is (pos-int? (:tokens budget)) (str role " budget :tokens"))
        (is (pos? (:cost-usd budget)) (str role " budget :cost-usd"))))))

(deftest role-default-lookup-test
  (testing "role-default returns the same map as direct lookup"
    (is (= (get role-config/role-defaults :planner)
           (role-config/role-default :planner)))))

(deftest role-default-unknown-test
  (testing "unknown role throws ex-info with known-roles in data"
    (try
      (role-config/role-default :nope)
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :nope (:role data)))
          (is (contains? (:known-roles data) :planner)))))))

(deftest roles-fn-test
  (testing "roles returns the set of declared roles"
    (is (= (set (keys role-config/role-defaults))
           (role-config/roles)))))
