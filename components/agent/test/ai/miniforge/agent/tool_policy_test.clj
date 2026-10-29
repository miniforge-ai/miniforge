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
(ns ai.miniforge.agent.tool-policy-test
  "The hook's tool policy is default-deny: a role gets only the tools
   config/agent/tool-policy.edn lists for it, and a missing or unknown role
   gets none."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.agent.tool-supervisor :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private session-roles
  "The roles that open an artifact session, and so run the hook."
  #{:planner :implementer :tester :reviewer :releaser})

(defn- ^{:stratum 0} decision
  "The decision string for `role` calling `tool-name` with `tool-input`."
  [role tool-name tool-input]
  (:decision (sut/evaluate-tool-use role tool-name tool-input)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unknown-tool-is-denied-test
  (testing "a tool the role's set does not list is refused, not allowed by default"
    (is (= "deny" (decision :implementer "SomeNewTool" {})))
    (is (= "deny" (decision :implementer "mcp__other__exfiltrate" {})))
    (is (= "deny" (decision :implementer nil {}))))
  (testing "tools that run commands outside the Bash checks are refused even to Bash roles"
    (doseq [role [:implementer :tester] tool ["Monitor" "PowerShell" "Agent" "Skill"]]
      (is (= "deny" (decision role tool {:command "printenv"})) (str role " " tool)))))

(deftest ^{:stratum 1} missing-or-unknown-role-denies-every-tool-test
  (testing "with no role, or one the policy does not list, nothing is allowed"
    (doseq [role [nil :curator "implementer"]]
      (is (= "deny" (decision role "mcp__context__context_read" {:path "x.clj"}))
          (str "role " (pr-str role))))))

(deftest ^{:stratum 1} roles-get-their-own-tools-test
  (testing "each role keeps the tools its job needs"
    (doseq [[role tool] [[:implementer "Write"] [:implementer "Bash"] [:planner "Write"]
                         [:tester "Bash"] [:releaser "Write"]]]
      (is (= "allow" (decision role tool {:command "bb test"})) (str role " " tool)))))

(deftest ^{:stratum 1} reviewer-has-no-write-path-test
  (testing "the reviewer inspects only: native and MCP writes and Bash are refused"
    (doseq [tool ["Write" "Edit" "MultiEdit" "Bash" "mcp__context__context_write"]]
      (is (= "deny" (decision :reviewer tool {})) tool))
    (is (= "allow" (decision :reviewer "mcp__context__context_read" {:path "x.clj"})))))

(deftest ^{:stratum 1} disallowed-native-tools-stay-denied-test
  (testing "every tool a role's --disallowedTools list removes is refused by the hook too"
    (doseq [[role tools] {:implementer @#'implementer/implementer-disallowed-tools
                          :planner     @#'planner/planner-disallowed-tools
                          :tester      @#'tester/tester-disallowed-tools
                          :reviewer    @#'reviewer/reviewer-disallowed-tools}
            tool tools]
      (is (= "deny" (decision role tool {})) (str role " " tool)))))

(deftest ^{:stratum 1} every-session-role-has-a-policy-test
  (testing "each role that opens a session is listed and gets the :common tools"
    (let [policy @sut/tool-policy]
      (doseq [role session-roles]
        (is (contains? (:roles policy) role) (str role))
        (is (every? (sut/role-tools policy role) (:common policy)) (str role))))))
