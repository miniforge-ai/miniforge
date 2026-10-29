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
(ns ai.miniforge.agent.pre-tool-use-reply-test
  "hook-eval answers Claude's PreToolUse hook in the current documented
   output form (https://code.claude.com/docs/en/hooks, PreToolUse decision
   control): a deny is hookSpecificOutput with hookEventName PreToolUse,
   permissionDecision deny and a permissionDecisionReason. The top-level
   decision/reason pair is deprecated for PreToolUse."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.tool-supervisor :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private permission-decisions
  "The permissionDecision values the docs list for PreToolUse."
  #{"allow" "deny" "ask" "defer"})

(defn- ^{:stratum 0} hook-reply
  "Run hook-eval on one PreToolUse request and return its stdout, parsed."
  [tool-name tool-input]
  (let [payload (json/generate-string {:tool_name tool-name :tool_input tool-input})]
    (json/parse-string
     (with-out-str
       (binding [*in* (java.io.StringReader. payload)]
         (sut/hook-eval-stdin!))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} deny-uses-the-current-pre-tool-use-output-test
  (testing "a denied Bash command gets hookSpecificOutput, not the deprecated decision/reason"
    (let [reply  (hook-reply "Bash" {:command "rm -rf /"})
          output (get reply "hookSpecificOutput")]
      (is (= #{"hookSpecificOutput"} (set (keys reply)))
          "no deprecated top-level decision or reason")
      (is (= "PreToolUse" (get output "hookEventName")))
      (is (contains? permission-decisions (get output "permissionDecision")))
      (is (= "deny" (get output "permissionDecision")))
      (is (not (str/blank? (get output "permissionDecisionReason")))))))

(deftest ^{:stratum 1} allow-reports-no-decision-test
  (testing "an allowed tool call gets an empty object, so Claude's permission flow applies"
    (is (= {} (hook-reply "Read" {:file_path "x.clj"})))))
