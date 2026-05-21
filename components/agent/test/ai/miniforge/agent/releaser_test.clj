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

(ns ai.miniforge.agent.releaser-test
  "Tests for the Releaser agent."
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.releaser :as releaser]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-release-artifact
  {:release/id (random-uuid)
   :release/branch-name "feature/add-user-auth"
   :release/commit-message "feat: add user authentication\n\nImplements login flow."
   :release/pr-title "feat: add user authentication"
   :release/pr-description "## Summary\nAdds auth.\n\n## Changes\n- Added login"
   :release/files-summary "1 file created"})

(def minimal-release-artifact
  {:release/id (random-uuid)
   :release/branch-name "feature/update"
   :release/commit-message "chore: update"
   :release/pr-title "chore: update"
   :release/pr-description "## Summary\nUpdates."})

(def code-artifact-input
  {:code-artifact {:code/id (random-uuid)
                   :code/files [{:path "src/auth/login.clj"
                                 :content "(ns auth.login)"
                                 :action :create}
                                {:path "src/auth/session.clj"
                                 :content "(ns auth.session)"
                                 :action :create}]
                   :code/summary "Added authentication module"}
   :task-description "Implement user authentication"})

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-releaser-test
  (testing "creates releaser with default config"
    (let [agent (releaser/create-releaser)]
      (is (some? agent))
      (is (= :releaser (:role agent)))
      (is (string? (:system-prompt agent)))
      (is (= {:tokens 20000 :cost-usd 1.0}
             (get-in agent [:config :budget])))))

  (testing "creates releaser with custom config"
    (let [agent (releaser/create-releaser {:config {:temperature 0.1}})]
      (is (= 0.1 (get-in agent [:config :temperature])))))

  (testing "creates releaser with logger"
    (let [[logger _] (log/collecting-logger)
          agent (releaser/create-releaser {:logger logger})]
      (is (some? (:logger agent))))))

;------------------------------------------------------------------------------ Layer 2
;; Invoke tests

(deftest releaser-invoke-test
  (testing "fails explicitly without LLM backend (no silent fallback)"
    (let [agent (releaser/create-releaser)
          result (core/invoke agent {} code-artifact-input)]
      (is (= :error (:status result)))
      (is (some? (:error result)))))

  (testing "accepts parseable release content even when backend flags failure"
    (let [fake-llm-client {:type :fake}
          parsed-release {:release/id (random-uuid)
                          :release/branch-name "feature/already-ready"
                          :release/commit-message "feat: ship"
                          :release/pr-title "feat: ship"
                          :release/pr-description "## Summary\nReady"
                          :release/files-summary "No file changes required"}
          agent (releaser/create-releaser {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context _body-fn]
                      {:llm-result {:success false
                                    :content (pr-str parsed-release)
                                    :error {:message "Adaptive timeout"}}
                       :artifact nil
                       :worktree-artifacts {}
                       :session-mode :host})
                    llm/success? :success
                    llm/get-content :content
                    llm/get-error :error]
        (let [result (core/invoke agent {:llm-backend fake-llm-client}
                                  code-artifact-input)]
          (is (= :success (:status result)))
          (is (= "feature/already-ready"
                 (get-in result [:output :release/branch-name]))))))))

(deftest releaser-system-prompt-includes-behavior-addendum-test
  ;; Pins the wiring that lets the releaser agent see the standards
  ;; rules `phase/load-and-filter-behaviors` produces for the :release
  ;; phase. Without this, conventional-commit / branch-naming /
  ;; localization rules that target the release phase stay invisible
  ;; to the releaser, the same way the reviewer was blind to them
  ;; before #945.
  (testing ":task/behavior-addendum on the input is appended to the LLM :system opt"
    (let [captured (atom nil)
          fake-llm-client {:type :fake}
          parsed-release {:release/id (random-uuid)
                          :release/branch-name "feature/x"
                          :release/commit-message "feat: x"
                          :release/pr-title "feat: x"
                          :release/pr-description "## Summary\nx"}
          addendum "\n\n## Policy Rules — Required Behaviors\n\n1. Test release rule body."
          agent (releaser/create-releaser {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      (let [session {:mcp-config-path nil
                                     :mcp-allowed-tools []
                                     :supervision nil}
                            llm-result (body-fn session)]
                        {:llm-result llm-result
                         :artifact nil
                         :worktree-artifacts {}
                         :session-mode :host}))
                    llm/chat (fn [_client _prompt opts]
                               (reset! captured opts)
                               {:success? true
                                :content (pr-str parsed-release)
                                :tokens 1})
                    llm/success? :success?
                    llm/get-content :content]
        (core/invoke agent {:llm-backend fake-llm-client}
                     (assoc code-artifact-input
                            :task/behavior-addendum addendum))
        (is (some? @captured) "LLM client should have been called")
        (let [system-prompt (:system @captured)]
          (is (string? system-prompt))
          (is (clojure.string/includes? system-prompt "Policy Rules")
              "appended addendum must surface in the LLM :system opt — the whole point of the wiring")
          (is (clojure.string/includes? system-prompt "Test release rule body.")
              "rule body text must reach the LLM verbatim"))))))

(deftest releaser-system-prompt-empty-when-no-addendum-test
  (testing "absent :task/behavior-addendum is treated as empty — no nil concat"
    (let [captured (atom nil)
          fake-llm-client {:type :fake}
          parsed-release {:release/id (random-uuid)
                          :release/branch-name "feature/x"
                          :release/commit-message "feat: x"
                          :release/pr-title "feat: x"
                          :release/pr-description "## Summary\nx"}
          agent (releaser/create-releaser {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      (let [session {:mcp-config-path nil
                                     :mcp-allowed-tools []
                                     :supervision nil}
                            llm-result (body-fn session)]
                        {:llm-result llm-result
                         :artifact nil
                         :worktree-artifacts {}
                         :session-mode :host}))
                    llm/chat (fn [_client _prompt opts]
                               (reset! captured opts)
                               {:success? true
                                :content (pr-str parsed-release)
                                :tokens 1})
                    llm/success? :success?
                    llm/get-content :content]
        (core/invoke agent {:llm-backend fake-llm-client} code-artifact-input)
        (is (some? @captured))
        (let [system-prompt (:system @captured)]
          (is (string? system-prompt)
              ":system must always be a string (default \"\" when no addendum)")
          (is (pos? (count system-prompt))))))))

;------------------------------------------------------------------------------ Layer 3
;; Validation tests

(deftest validate-release-artifact-test
  (testing "valid artifact passes validation"
    (let [result (releaser/validate-release-artifact valid-release-artifact)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "minimal artifact passes validation"
    (let [result (releaser/validate-release-artifact minimal-release-artifact)]
      (is (:valid? result))))

  (testing "missing required fields fails validation"
    (let [result (releaser/validate-release-artifact {:release/id (random-uuid)})]
      (is (not (:valid? result)))))

  (testing "branch name with spaces fails validation"
    (let [bad-artifact (assoc valid-release-artifact
                              :release/branch-name "feature/bad branch name")
          result (releaser/validate-release-artifact bad-artifact)]
      (is (not (:valid? result)))
      (is (= "Branch name cannot contain spaces" (:branch-name (:errors result))))))

  (testing "PR title exceeding 70 chars fails validation"
    (let [long-title (apply str (repeat 80 "x"))
          bad-artifact (assoc valid-release-artifact :release/pr-title long-title)
          result (releaser/validate-release-artifact bad-artifact)]
      ;; Schema validation catches this at the :max 70 constraint
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 4
;; Utility tests

(deftest release-summary-test
  (testing "returns release summary"
    (let [summary (releaser/release-summary valid-release-artifact)]
      (is (uuid? (:id summary)))
      (is (string? (:branch summary)))
      (is (string? (:pr-title summary)))
      (is (string? (:files-summary summary))))))

;------------------------------------------------------------------------------ Layer 5
;; Repair tests

(deftest releaser-repair-test
  (testing "repairs branch name with spaces"
    (let [agent (releaser/create-releaser)
          bad-artifact (assoc valid-release-artifact
                              :release/branch-name "feature/bad branch")
          result (core/repair agent bad-artifact {:branch-name ["spaces"]} {})]
      (is (response/success? result))
      (is (not (re-find #" " (get-in result [:output :release/branch-name]))))))

  (testing "truncates long PR title"
    (let [agent (releaser/create-releaser)
          long-title (apply str (repeat 80 "x"))
          bad-artifact (assoc valid-release-artifact :release/pr-title long-title)
          result (core/repair agent bad-artifact {:pr-title ["too long"]} {})]
      (is (response/success? result))
      (is (<= (count (get-in result [:output :release/pr-title])) 70))))

  (testing "adds missing ID"
    (let [agent (releaser/create-releaser)
          bad-artifact (dissoc valid-release-artifact :release/id)
          result (core/repair agent bad-artifact {:release/id ["required"]} {})]
      (is (response/success? result))
      (is (uuid? (get-in result [:output :release/id]))))))

;------------------------------------------------------------------------------ Layer 6
;; Full cycle tests

(deftest releaser-cycle-test
  (testing "full invoke-validate cycle fails without LLM (no silent fallback)"
    (let [agent (releaser/create-releaser)
          result (core/cycle-agent agent {} code-artifact-input)]
      (is (= :error (:status result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.releaser-test)

  :leave-this-here)
