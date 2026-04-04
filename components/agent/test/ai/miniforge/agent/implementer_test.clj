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

(ns ai.miniforge.agent.implementer-test
  "Tests for the Implementer agent."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [clojure.test :as test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/example/core.clj"
                 :content "(ns example.core)\n\n(defn hello [] \"world\")"
                 :action :create}]
   :code/dependencies-added []
   :code/tests-needed? true
   :code/language "clojure"
   :code/summary "Added example core"})

(def minimal-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/minimal.clj"
                 :content "(ns minimal)"
                 :action :create}]})

(defn- llm-response
  "Create a mock successful LLM response."
  [& {:keys [content tokens cost-usd tools-called]
      :or {content ""
           tokens 0
           cost-usd 0.0
           tools-called []}}]
  {:success true
   :content content
   :tokens tokens
   :cost-usd cost-usd
   :tools-called tools-called})

(defn- session-map
  "Create an artifact session map for tests."
  [& {:keys [pre-session-snapshot]
      :or {pre-session-snapshot {:untracked #{}
                                 :modified #{}
                                 :deleted #{}
                                 :added #{}}}}]
  {:dir "/tmp/miniforge-artifact-test"
   :mcp-config-path "/tmp/miniforge-artifact-test/mcp-config.json"
   :artifact-path "/tmp/miniforge-artifact-test/artifact.edn"
   :pre-session-snapshot pre-session-snapshot})

(defn- find-log-entry
  "Find a specific event in collected log entries."
  [entries event]
  (some #(when (= event (:log/event %)) %) @entries))

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-implementer-test
  (testing "creates implementer with default config"
    (let [agent (implementer/create-implementer)]
      (is (some? agent))
      (is (= :implementer (:role agent)))
      (is (string? (:system-prompt agent)))
      (is (= {:tokens 100000 :cost-usd 5.0}
             (get-in agent [:config :budget])))))

  (testing "creates implementer with custom config"
    (let [agent (implementer/create-implementer {:config {:temperature 0.1}})]
      (is (= 0.1 (get-in agent [:config :temperature])))))

  (testing "creates implementer with logger"
    (let [[logger _] (log/collecting-logger)
          agent (implementer/create-implementer {:logger logger})]
      (is (some? (:logger agent))))))

;------------------------------------------------------------------------------ Layer 2
;; Invoke tests

(deftest implementer-invoke-test
  (testing "fails explicitly without LLM backend (no silent fallback)"
    (let [agent (implementer/create-implementer)
          result (core/invoke agent
                              {:suggested-path "src/auth/login.clj"}
                              {:task/description "Implement user login"
                               :task/type :implement})]
      (is (= :error (:status result)))
      (is (some? (:error result)))))

  (testing "uses file artifact fallback when MCP artifact is missing"
    (let [[logger entries] (log/collecting-logger {:min-level :trace})
          fallback-artifact {:code/files [{:path "src/generated.clj"
                                           :content "(ns generated)"
                                           :action :create}]
                             :code/summary "Collected from working tree"
                             :code/language "clojure"
                             :code/tests-needed? true}
          response (llm-response :tokens 42 :cost-usd 0.12)
          result (with-redefs [artifact-session/create-session!
                               (fn [] (session-map))
                               artifact-session/write-mcp-config!
                               identity
                               artifact-session/read-artifact
                               (constantly nil)
                               artifact-session/read-context-misses
                               (constantly nil)
                               artifact-session/cleanup-session!
                               (constantly nil)
                               budget/resolve-cost-budget-usd
                               (fn [& _] 1.0)
                               llm/chat
                               (fn [& _] response)
                               llm/success?
                               :success
                               llm/get-content
                               :content
                               file-artifacts/collect-written-files
                               (fn [_ _] fallback-artifact)]
                   (@#'implementer/invoke-with-llm
                    nil "prompt" "system" {} {} nil logger []))
          fallback-log (find-log-entry entries :implementer/file-artifact-fallback)]
      (is (= :success (:status result)))
      (is (= [{:path "src/generated.clj"
               :content "(ns generated)"
               :action :create}]
             (get-in result [:output :code/files])))
      (is (= "Collected from working tree"
             (get-in result [:output :code/summary])))
      (is (= :warn (:log/level fallback-log)))
      (is (= 1 (get-in fallback-log [:data :file-count]))))))

;------------------------------------------------------------------------------ Layer 3
;; Validation tests

(deftest validate-code-artifact-test
  (testing "valid artifact passes validation"
    (let [result (implementer/validate-code-artifact valid-code-artifact)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "minimal artifact passes validation"
    (let [result (implementer/validate-code-artifact minimal-code-artifact)]
      (is (:valid? result))))

  (testing "missing required fields fails validation"
    (let [result (implementer/validate-code-artifact {:code/files []})]
      (is (not (:valid? result)))))

  (testing "empty content for :create fails validation"
    (let [bad-artifact {:code/id (random-uuid)
                        :code/files [{:path "src/empty.clj"
                                      :content ""
                                      :action :create}]}
          result (implementer/validate-code-artifact bad-artifact)]
      (is (not (:valid? result)))
      (is (contains? (:errors result) :files))))

  (testing "duplicate paths fail validation"
    (let [bad-artifact {:code/id (random-uuid)
                        :code/files [{:path "src/dup.clj"
                                      :content "(ns dup)"
                                      :action :create}
                                     {:path "src/dup.clj"
                                      :content "(ns dup2)"
                                      :action :modify}]}
          result (implementer/validate-code-artifact bad-artifact)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 4
;; Utility tests

(deftest code-summary-test
  (testing "returns code summary"
    (let [summary (implementer/code-summary valid-code-artifact)]
      (is (uuid? (:id summary)))
      (is (number? (:file-count summary)))
      (is (map? (:actions summary)))
      (is (string? (:language summary)))
      (is (boolean? (:tests-needed? summary))))))

(deftest files-by-action-test
  (testing "groups files by action"
    (let [artifact {:code/files [{:path "a.clj" :content "" :action :create}
                                 {:path "b.clj" :content "" :action :modify}
                                 {:path "c.clj" :content "" :action :create}]}
          grouped (implementer/files-by-action artifact)]
      (is (= 2 (count (:create grouped))))
      (is (= 1 (count (:modify grouped)))))))

(deftest total-lines-test
  (testing "counts total lines of code"
    (let [artifact {:code/files [{:path "a.clj"
                                  :content "line1\nline2\nline3"
                                  :action :create}
                                 {:path "b.clj"
                                  :content "line1\nline2"
                                  :action :modify}]}
          lines (implementer/total-lines artifact)]
      (is (= 5 lines))))

  (testing "excludes deleted files from count"
    (let [artifact {:code/files [{:path "a.clj"
                                  :content "line1\nline2"
                                  :action :create}
                                 {:path "b.clj"
                                  :content "should not count"
                                  :action :delete}]}
          lines (implementer/total-lines artifact)]
      (is (= 2 lines)))))

;------------------------------------------------------------------------------ Layer 5
;; task->text tests

(deftest task->text-basic-test
  (testing "string task passes through"
    (is (= "hello" (implementer/task->text "hello"))))

  (testing "map task uses :task/description"
    (is (= "Implement login"
           (implementer/task->text {:task/description "Implement login"}))))

  (testing "includes plan when present"
    (let [text (implementer/task->text {:task/description "Do thing"
                                        :task/plan "Step 1: foo\nStep 2: bar"})]
      (is (str/includes? text "Do thing"))
      (is (str/includes? text "## Plan"))
      (is (str/includes? text "Step 1: foo"))))

  (testing "includes intent when present"
    (let [text (implementer/task->text {:task/description "Do thing"
                                        :task/intent {:scope ["src/core.clj"]}})]
      (is (str/includes? text "## Intent"))
      (is (str/includes? text "src/core.clj")))))

(deftest task->text-review-feedback-test
  (testing "includes review feedback as string"
    (let [text (implementer/task->text {:task/description "Fix it"
                                        :task/review-feedback "Missing error handling in login"})]
      (is (str/includes? text "## Review Feedback (MUST FIX)"))
      (is (str/includes? text "Missing error handling in login"))))

  (testing "includes review feedback as map"
    (let [text (implementer/task->text {:task/description "Fix it"
                                        :task/review-feedback {:issues ["no validation" "no tests"]}})]
      (is (str/includes? text "## Review Feedback (MUST FIX)"))
      (is (str/includes? text "no validation")))))

(deftest task->text-verify-failures-test
  (testing "includes verify failures with test-results"
    (let [text (implementer/task->text
                 {:task/description "Fix failing tests"
                  :task/verify-failures
                  {:test-results {:all-passed? false
                                  :test-count 5
                                  :fail-count 2
                                  :error-count 0}
                   :test-output "FAIL in (login-test)\nexpected: 200\n  actual: 401"}})]
      (is (str/includes? text "## Test Failures (MUST FIX)"))
      (is (str/includes? text "Test results:"))
      (is (str/includes? text ":fail-count 2"))
      (is (str/includes? text "FAIL in (login-test)"))
      (is (str/includes? text "expected: 200"))))

  (testing "includes verify failures without test-results"
    (let [text (implementer/task->text
                 {:task/description "Fix it"
                  :task/verify-failures
                  {:error "Verification timed out"}})]
      (is (str/includes? text "## Test Failures (MUST FIX)"))
      (is (str/includes? text "Verify failure details:"))
      (is (str/includes? text "Verification timed out"))))

  (testing "includes both review feedback and verify failures"
    (let [text (implementer/task->text
                 {:task/description "Fix everything"
                  :task/review-feedback "Add input validation"
                  :task/verify-failures
                  {:test-results {:all-passed? false :fail-count 1}}})]
      (is (str/includes? text "## Review Feedback (MUST FIX)"))
      (is (str/includes? text "Add input validation"))
      (is (str/includes? text "## Test Failures (MUST FIX)"))
      (is (str/includes? text ":fail-count 1"))))

  (testing "no verify section when verify-failures is nil"
    (let [text (implementer/task->text {:task/description "Normal task"})]
      (is (not (str/includes? text "Test Failures"))))))

;------------------------------------------------------------------------------ Layer 6
;; Repair tests

(deftest implementer-repair-test
  (testing "repairs missing ID"
    (let [agent (implementer/create-implementer)
          bad-artifact {:code/files [{:path "src/test.clj"
                                      :content "(ns test)"
                                      :action :create}]}
          result (core/repair agent bad-artifact {:code/id ["required"]} {})]
      (is (= :success (:status result)))
      (is (uuid? (get-in result [:output :code/id])))))

  (testing "adds content to empty :create files"
    (let [agent (implementer/create-implementer)
          bad-artifact {:code/id (random-uuid)
                        :code/files [{:path "src/empty.clj"
                                      :content ""
                                      :action :create}]}
          result (core/repair agent bad-artifact {:files ["empty content"]} {})]
      (is (= :success (:status result)))
      (is (seq (get-in result [:output :code/files 0 :content]))))))

;------------------------------------------------------------------------------ Layer 6
;; Full cycle tests

(deftest implementer-cycle-test
  (testing "full invoke-validate cycle fails without LLM (no silent fallback)"
    (let [agent (implementer/create-implementer)
          result (core/cycle-agent agent {} {:task/description "Create a helper function"
                                              :task/type :implement})]
      (is (= :error (:status result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.implementer-test)

  :leave-this-here)
