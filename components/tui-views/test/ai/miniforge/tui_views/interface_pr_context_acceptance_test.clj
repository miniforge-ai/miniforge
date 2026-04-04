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

(ns ai.miniforge.tui-views.interface-pr-context-acceptance-test
  "Acceptance tests for PR context rendering and action parsing
   in the TUI interface layer.

   Verifies:
   - build-pr-context-str produces correct text for various PR shapes
   - parse-actions round-trips with action-match->action
   - parse-risk-line handles edge cases in LLM output
   - handle-fetch-pr-diff correctly wires number coercion"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.persistence.github :as github]))

;; ---------------------------------------------------------------------------- build-pr-context-str acceptance

(deftest pr-context-str-includes-provider-detection-test
  (testing "GitHub provider detected for standard repo slug"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "acme/app" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open})]
      (is (str/includes? ctx "GitHub"))))

  (testing "GitLab provider detected for gitlab: prefix"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "gitlab:group/project" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open})]
      (is (str/includes? ctx "GitLab"))))

  (testing "GitHub is default for non-gitlab repos"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "bitbucket:org/repo" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open})]
      (is (str/includes? ctx "GitHub")))))

(deftest pr-context-str-risk-factors-rendering-test
  (testing "multiple risk factors are each rendered on a separate line"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/risk {:risk/level :high
                           :risk/score 0.9
                           :risk/factors [{:explanation "Large PR"}
                                          {:explanation "No tests"}
                                          {:explanation "Core module"}]}})]
      (is (str/includes? ctx "Large PR"))
      (is (str/includes? ctx "No tests"))
      (is (str/includes? ctx "Core module"))))

  (testing "no risk factors section when factors list is empty"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/risk {:risk/level :low :risk/factors []}})]
      (is (not (str/includes? ctx "Risk factors"))))))

(deftest pr-context-str-readiness-rendering-test
  (testing "readiness without ready? flag omits (ready) suffix"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/readiness {:readiness/score 50 :readiness/ready? false}})]
      (is (str/includes? ctx "Readiness score: 50"))
      (is (not (str/includes? ctx "(ready)"))))))

(deftest pr-context-str-change-size-with-files-count-test
  (testing "includes file count when changed-files-count is positive"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/additions 100 :pr/deletions 20
                 :pr/changed-files-count 8})]
      (is (str/includes? ctx "+100/-20"))
      (is (str/includes? ctx "120 total lines"))
      (is (str/includes? ctx "8 files"))))

  (testing "omits file count when changed-files-count is zero"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/additions 5 :pr/deletions 3
                 :pr/changed-files-count 0})]
      (is (str/includes? ctx "+5/-3"))
      (is (not (str/includes? ctx "files"))))))

(deftest pr-context-str-policy-packs-rendering-test
  (testing "shows pack names when packs-applied is present"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/policy {:evaluation/passed? true
                             :evaluation/packs-applied ["security" "performance"]}})]
      (is (str/includes? ctx "security"))
      (is (str/includes? ctx "performance"))))

  (testing "omits pack names when packs-applied is nil"
    (let [ctx (iface/build-pr-context-str
                {:pr/repo "r" :pr/number 1 :pr/title "T"
                 :pr/branch "b" :pr/status :open
                 :pr/policy {:evaluation/passed? false}})]
      (is (str/includes? ctx "Policy: FAILED"))
      (is (not (str/includes? ctx "packs:"))))))

;; ---------------------------------------------------------------------------- parse-actions round-trip

(deftest parse-actions-round-trip-test
  (testing "parse-actions → action-match->action produces correct action maps"
    (let [text (str "I analyzed the PR.\n"
                    "[ACTION: review | Run Policy | Evaluate against security pack]\n"
                    "[ACTION: open | View in Browser | Open PR URL in browser]\n"
                    "Let me know if you need more.")
          [clean actions] (iface/parse-actions text)]
      ;; Clean text
      (is (str/includes? clean "I analyzed the PR."))
      (is (str/includes? clean "Let me know"))
      (is (not (str/includes? clean "[ACTION:")))
      ;; Actions
      (is (= 2 (count actions)))
      (is (= :review (:action (first actions))))
      (is (= "Run Policy" (:label (first actions))))
      (is (= "Evaluate against security pack" (:description (first actions))))
      (is (= :open (:action (second actions)))))))

(deftest parse-actions-trims-whitespace-test
  (testing "labels and descriptions are trimmed"
    (let [[_ actions] (iface/parse-actions
                        "[ACTION: sync |  Refresh PRs  |  Reload from disk  ]")]
      (is (= 1 (count actions)))
      (is (= "Refresh PRs" (:label (first actions))))
      (is (= "Reload from disk" (:description (first actions)))))))

(deftest parse-actions-empty-input-test
  (testing "empty string returns empty clean and no actions"
    (let [[clean actions] (iface/parse-actions "")]
      (is (= "" clean))
      (is (empty? actions)))))

;; ---------------------------------------------------------------------------- parse-risk-line edge cases

(deftest parse-risk-line-whitespace-in-level-test
  (testing "trims whitespace from level"
    (let [r (iface/parse-risk-line "RISK: org/repo#10 |  high  | reason here")]
      (is (= "high" (:level r))))))

(deftest parse-risk-line-with-hash-in-repo-name-test
  (testing "handles repo names with org containing hyphens"
    (let [r (iface/parse-risk-line "RISK: my-org/my-repo#999 | critical | big change")]
      (is (= ["my-org/my-repo" 999] (:id r))))))

(deftest parse-risk-line-zero-pr-number-test
  (testing "handles PR number 0"
    (let [r (iface/parse-risk-line "RISK: r/r#0 | low | trivial")]
      (is (= ["r/r" 0] (:id r))))))

;; ---------------------------------------------------------------------------- handle-fetch-pr-diff number coercion

(deftest handle-fetch-pr-diff-large-number-test
  (testing "handles large PR numbers correctly"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [repo number]
                    {:diff "d" :detail {:title "T"} :repo repo :number number})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "r" :number 999999})]
        (is (= ["r" 999999] (:pr-id payload)))))))

(deftest handle-fetch-pr-diff-string-zero-test
  (testing "handles string '0' as PR number"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [repo number]
                    {:diff nil :detail nil :repo repo :number number})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "r" :number "0"})]
        (is (= ["r" 0] (:pr-id payload)))))))