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

(ns ai.miniforge.agent.planner-test
  "Tests for the Planner agent."
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Regression-floor constants

(def ^:private min-stagnation-threshold-ms
  "Floor for the planner main-turn :stagnation-threshold-ms. Below
   this, Opus's pre-first-chunk think on heavy planner prompts trips
   stagnation before the first structured-EDN chunk lands. This is the
   regression floor PR #781 establishes."
  180000)

(def ^:private min-total-budget-ms
  "Floor for the planner main-turn :max-total-ms. Covers the longest
   historical successful planner run."
  600000)

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-plan
  {:plan/id (random-uuid)
   :plan/name "test-plan"
   :plan/tasks [{:task/id (random-uuid)
                 :task/description "Implement feature"
                 :task/type :implement
                 :task/acceptance-criteria ["Feature works"]
                 :task/estimated-effort :medium}]
   :plan/estimated-complexity :medium
   :plan/risks ["None identified"]})

(def minimal-plan
  {:plan/id (random-uuid)
   :plan/name "minimal"
   :plan/tasks []})

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-planner-test
  (testing "creates planner with default config"
    (let [agent (planner/create-planner)]
      (is (some? agent))
      (is (= :planner (:role agent)))
      (is (string? (:system-prompt agent)))
      (is (= {:tokens 100000 :cost-usd 5.0}
             (get-in agent [:config :budget])))))

  (testing "creates planner with custom config"
    (let [agent (planner/create-planner {:config {:temperature 0.5}})]
      (is (= 0.5 (get-in agent [:config :temperature])))))

  (testing "creates planner with logger"
    (let [[logger _] (log/collecting-logger)
          agent (planner/create-planner {:logger logger})]
      (is (some? (:logger agent))))))

;------------------------------------------------------------------------------ Layer 2
;; Invoke tests

(deftest planner-invoke-test
  (testing "throws when no LLM backend provided"
    (let [agent (planner/create-planner)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No LLM backend"
            (core/invoke agent {} "Build a user login system")))))

  (testing "throws with context but no LLM backend"
    (let [agent (planner/create-planner)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No LLM backend"
            (core/invoke agent {:codebase {:has-tests? true}}
                         "Add feature to existing codebase")))))

  (testing "writes existing files into the planner context cache"
    (let [cached-files (atom nil)
          fake-llm-client {:type :fake}
          fake-plan {:plan/id (random-uuid)
                     :plan/name "cache-test"
                     :plan/tasks []}
          input {:description "Plan this"
                 :task/existing-files [{:path "src/foo.clj"
                                        :content "(ns foo)"
                                        :truncated? false}]}
          agent (planner/create-planner {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    artifact-session/write-context-cache-for-session!
                    (fn [session files]
                      (reset! cached-files files)
                      session)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      {:llm-result (body-fn {:dir "/tmp/fake-session"
                                             :workdir "/tmp/fake-workdir"
                                             :mcp-config-path "/tmp/fake-session/mcp-config.json"
                                             :mcp-allowed-tools []
                                             :supervision {}
                                             :pre-session-snapshot {}})
                       :artifact nil
                       :worktree-artifacts {}
                       :context-misses nil
                       :pre-session-snapshot {}
                       :session-mode :host})
                    llm/success? (constantly true)
                    llm/get-content (constantly (str "```clojure\n" (pr-str fake-plan) "\n```"))
                    llm/chat (fn [_client _prompt _opts] {:status :success})
                    llm/chat-stream (fn [_client _prompt _on-chunk _opts] {:status :success})]
        (core/invoke agent {:llm-backend fake-llm-client} input)
        (is (= {"src/foo.clj" "(ns foo)"} @cached-files)))))

  (testing "accepts a submitted artifact even when the LLM response is classified as failure"
    (let [fake-llm-client {:type :fake}
          submitted-plan {:plan/id (random-uuid)
                          :plan/name "artifact-wins"
                          :plan/tasks []}
          agent (planner/create-planner {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context _body-fn]
                      {:llm-result {:status :error}
                       :artifact submitted-plan
                       :worktree-artifacts {}
                       :context-misses nil
                       :pre-session-snapshot {}
                       :session-mode :host})
                    llm/success? (constantly false)
                    llm/get-content (constantly "")
                    llm/get-error (constantly {:message "Adaptive timeout"})]
        (let [result (core/invoke agent {:llm-backend fake-llm-client}
                                  {:description "Plan this"})]
          (is (= :success (:status result)))
          (is (= "artifact-wins" (get-in result [:output :plan/name]))))))

  (testing "accepts parseable EDN from error stdout"
    (let [fake-llm-client {:type :fake}
          stdout-plan {:plan/id (random-uuid)
                       :plan/name "stdout-plan"
                       :plan/tasks []}
          agent (planner/create-planner {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      {:llm-result (body-fn {:dir "/tmp/fake-session"
                                             :workdir "/tmp/fake-workdir"
                                             :mcp-config-path "/tmp/fake-session/mcp-config.json"
                                             :mcp-allowed-tools []
                                             :supervision {}
                                             :pre-session-snapshot {}})
                       :artifact nil
                       :worktree-artifacts {}
                       :context-misses nil
                       :pre-session-snapshot {}
                       :session-mode :host})
                    llm/chat (fn [_client _prompt _opts]
                               {:status :error
                                :type "adaptive_timeout"
                                :message "Adaptive timeout"
                                :stdout (str "```clojure\n" (pr-str stdout-plan) "\n```")})
                    llm/success? #(= :success (:status %))
                    llm/get-content :content
                    llm/get-error identity]
        (let [result (core/invoke agent {:llm-backend fake-llm-client}
                                  {:description "Plan this"})]
          (is (= :success (:status result)))
          (is (= "stdout-plan" (get-in result [:output :plan/name])))))))

  (testing "retries once with submission-only prompt when analysis exists but no plan was submitted"
    (let [fake-llm-client {:type :fake}
          prompts (atom [])
          call-count (atom 0)
          submitted-plan {:plan/id (random-uuid)
                          :plan/name "retry-plan"
                          :plan/tasks []}
          first-response {:status :error
                          :type "adaptive_timeout"
                          :message "Adaptive timeout"
                          :stdout "Sufficient data. Architectural picture is clear.\n\nWriting the plan now."}
          second-response {:status :success
                           :content ""}
          responses (atom [first-response second-response])
          agent (planner/create-planner {:llm-backend fake-llm-client})]
      (with-redefs [model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      (let [n (swap! call-count inc)
                            llm-result (body-fn {:dir "/tmp/fake-session"
                                                 :workdir "/tmp/fake-workdir"
                                                 :mcp-config-path "/tmp/fake-session/mcp-config.json"
                                                 :mcp-allowed-tools []
                                                 :supervision {}
                                                 :pre-session-snapshot {}})]
                        {:llm-result llm-result
                         :artifact (when (= n 2) submitted-plan)
                         :worktree-artifacts {}
                         :context-misses nil
                         :pre-session-snapshot {}
                         :session-mode :host}))
                    llm/chat (fn [_client prompt _opts]
                               (swap! prompts conj prompt)
                               (let [response (first @responses)]
                                 (swap! responses rest)
                                 response))
                    llm/success? #(= :success (:status %))
                    llm/get-content :content
                    llm/get-error identity]
        (let [result (core/invoke agent {:llm-backend fake-llm-client}
                                  {:description "Plan this"})]
          (is (= :success (:status result)))
          (is (= "retry-plan" (get-in result [:output :plan/name])))
          (is (= 2 @call-count))
          (is (= 2 (count @prompts)))
          (is (not (str/includes? (first @prompts) "artifact submission MCP tool")))
          (is (str/includes? (first @prompts) ".miniforge/plan.edn"))
          (is (str/includes? (second @prompts) "Do NOT explore further"))
          (is (str/includes? (second @prompts) "Write `.miniforge/plan.edn`"))))))))

;------------------------------------------------------------------------------ Layer 3
;; Validation tests

(deftest validate-plan-test
  (testing "valid plan passes validation"
    (let [result (planner/validate-plan valid-plan)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "minimal plan passes validation"
    (let [result (planner/validate-plan minimal-plan)]
      (is (:valid? result))))

  (testing "missing required fields fails validation"
    (let [result (planner/validate-plan {:plan/name "no-id"})]
      (is (not (:valid? result)))
      (is (some? (:errors result)))))

  (testing "invalid task dependencies fail validation"
    (let [bad-plan {:plan/id (random-uuid)
                    :plan/name "bad-deps"
                    :plan/tasks [{:task/id (random-uuid)
                                  :task/description "Task"
                                  :task/type :implement
                                  :task/dependencies [(random-uuid)]}]}
          result (planner/validate-plan bad-plan)]
      (is (not (:valid? result)))
      (is (contains? (:errors result) :dependencies))))

  (testing "self-dependency fails validation"
    (let [task-id (random-uuid)
          self-dep-plan {:plan/id (random-uuid)
                         :plan/name "self-dep"
                         :plan/tasks [{:task/id task-id
                                       :task/description "Task"
                                       :task/type :implement
                                       :task/dependencies [task-id]}]}
          result (planner/validate-plan self-dep-plan)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 3.5
;; Already-satisfied validation tests

(deftest validate-already-satisfied-test
  (testing "rejects when no evidence provided"
    (let [result (planner/validate-already-satisfied
                  {:plan/status :already-satisfied :plan/evidence []}
                  ["A function named `export-capsule-artifacts!` exists"])]
      (is (not (:valid? result)))
      (is (str/includes? (:reason result) "No evidence"))))

  (testing "rejects when acceptance criteria names function not in evidence"
    (let [result (planner/validate-already-satisfied
                  {:plan/status :already-satisfied
                   :plan/evidence [{:requirement "Export artifacts"
                                    :satisfied-by "runner.clj"
                                    :proof "release-execution-environment! in finally block"}]}
                  ["A function named `export-capsule-artifacts!` exists in runner.clj"])]
      (is (not (:valid? result)))
      (is (str/includes? (:reason result) "not found in evidence"))))

  (testing "accepts when evidence mentions the required function"
    (let [result (planner/validate-already-satisfied
                  {:plan/status :already-satisfied
                   :plan/evidence [{:requirement "Export artifacts"
                                    :satisfied-by "runner.clj"
                                    :proof "export-capsule-artifacts! function at line 320"}]}
                  ["A function named `export-capsule-artifacts!` exists in runner.clj"])]
      (is (:valid? result))))

  (testing "accepts when no acceptance criteria reference function names"
    (let [result (planner/validate-already-satisfied
                  {:plan/status :already-satisfied
                   :plan/evidence [{:requirement "Feature works"
                                    :satisfied-by "core.clj"
                                    :proof "Implementation complete"}]}
                  ["Feature is implemented" "Tests pass"])]
      (is (:valid? result))))

  (testing "accepts with nil acceptance criteria"
    (let [result (planner/validate-already-satisfied
                  {:plan/status :already-satisfied
                   :plan/evidence [{:requirement "Done" :satisfied-by "x.clj" :proof "yes"}]}
                  nil)]
      (is (:valid? result)))))

;------------------------------------------------------------------------------ Layer 3.75
;; Schema backward-compat & new-field tests

(deftest validate-plan-new-fields-test
  (testing "plan with task/component, exclusive-files, stratum validates"
    (let [plan {:plan/id (random-uuid)
                :plan/name "multi-component"
                :plan/tasks [{:task/id (random-uuid)
                              :task/description "Agent changes"
                              :task/type :implement
                              :task/component "agent"
                              :task/exclusive-files ["components/agent/src/foo.clj"]
                              :task/stratum 0}
                             {:task/id (random-uuid)
                              :task/description "Workflow changes"
                              :task/type :implement
                              :task/component "workflow"
                              :task/exclusive-files ["components/workflow/src/bar.clj"]
                              :task/stratum 0}]}
          result (planner/validate-plan plan)]
      (is (:valid? result))))

  (testing "plan without new fields still validates (backward compat)"
    (let [result (planner/validate-plan valid-plan)]
      (is (:valid? result))))

  (testing "invalid stratum type fails validation"
    (let [plan {:plan/id (random-uuid)
                :plan/name "bad-stratum"
                :plan/tasks [{:task/id (random-uuid)
                              :task/description "Task"
                              :task/type :implement
                              :task/stratum -1}]}
          result (planner/validate-plan plan)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 4
;; Utility tests

(deftest plan-summary-test
  (testing "returns plan summary"
    (let [summary (planner/plan-summary valid-plan)]
      (is (uuid? (:id summary)))
      (is (string? (:name summary)))
      (is (number? (:task-count summary)))
      (is (keyword? (:complexity summary)))
      (is (number? (:risk-count summary))))))

(deftest task-dependency-order-test
  (testing "returns tasks in dependency order"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-c-id (random-uuid)
          plan {:plan/tasks [{:task/id task-b-id
                              :task/description "B depends on A"
                              :task/type :test
                              :task/dependencies [task-a-id]}
                             {:task/id task-a-id
                              :task/description "A is first"
                              :task/type :implement}
                             {:task/id task-c-id
                              :task/description "C depends on B"
                              :task/type :review
                              :task/dependencies [task-b-id]}]}
          ordered (planner/task-dependency-order plan)]
      (is (= 3 (count ordered)))
      ;; A should come before B, B before C
      (is (< (.indexOf (map :task/id ordered) task-a-id)
             (.indexOf (map :task/id ordered) task-b-id)))
      (is (< (.indexOf (map :task/id ordered) task-b-id)
             (.indexOf (map :task/id ordered) task-c-id)))))

  (testing "handles tasks with no dependencies"
    (let [plan {:plan/tasks [{:task/id (random-uuid)
                              :task/description "Independent A"
                              :task/type :implement}
                             {:task/id (random-uuid)
                              :task/description "Independent B"
                              :task/type :test}]}
          ordered (planner/task-dependency-order plan)]
      (is (= 2 (count ordered))))))

;------------------------------------------------------------------------------ Layer 5
;; Full cycle tests

(deftest planner-cycle-test
  (testing "full invoke-validate cycle throws without LLM"
    (let [agent (planner/create-planner)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No LLM backend"
            (core/cycle-agent agent {} "Create a REST API endpoint"))))))

;------------------------------------------------------------------------------ Layer 6
;; Tool-disallow-list — role-scoped restriction so the planner cannot
;; bypass the context MCP by using native filesystem tools.

(deftest planner-disallowed-tools-contents-test
  (testing "planner-disallowed-tools names the native file/shell tools"
    (let [dt @#'planner/planner-disallowed-tools]
      (is (vector? dt))
      (is (every? string? dt))
      (doseq [t ["Read" "Bash" "Grep" "Glob" "Agent" "LS"]]
        (is (some #{t} dt)
            (str "expected " t " in disallowed-tools")))))

  (testing "planner-disallowed-tools does NOT include Write"
    ;; Write is the container-promotion submission path — .miniforge/plan.edn.
    (is (nil? (some #{"Write"} @#'planner/planner-disallowed-tools))))

  (testing "planner-disallowed-tools does NOT include the MCP context tools"
    ;; Those are the replacement for the native tools. Disallowing them
    ;; would leave the planner with no way to read files at all.
    (doseq [t ["mcp__context__context_read"
               "mcp__context__context_grep"
               "mcp__context__context_glob"]]
      (is (nil? (some #{t} @#'planner/planner-disallowed-tools))
          (str "MCP context tool " t " must NOT be disallowed"))))

  (testing "planner-disallowed-tools does NOT (yet) include WebSearch/WebFetch"
    ;; Disabling these without a cached-MCP replacement regresses
    ;; capability — see work/planner-convergence-and-artifact-submission
    ;; GROUP 2B. They ship with the web-cache MCP base, not here.
    (doseq [t ["WebSearch" "WebFetch"]]
      (is (nil? (some #{t} @#'planner/planner-disallowed-tools))
          (str t " should not be disallowed until GROUP 2B ships")))))

(deftest planner-passes-disallowed-tools-to-llm-test
  (testing ":disallowed-tools reaches the LLM client via mcp-opts"
    (let [captured (atom nil)
          fake-llm-client {:type :fake}
          fake-plan {:plan/id (random-uuid)
                     :plan/name "stub"
                     :plan/tasks [{:task/id (random-uuid)
                                   :task/description "t"
                                   :task/type :implement
                                   :task/acceptance-criteria ["ok"]
                                   :task/estimated-effort :small}]}]
      (with-redefs [llm/success? (constantly true)
                    llm/get-content (constantly (str "```clojure\n"
                                                     (pr-str fake-plan)
                                                     "\n```"))
                    llm/chat (fn [_client _prompt opts]
                               (reset! captured opts)
                               {:status :success})
                    llm/chat-stream (fn [_client _prompt _on-chunk opts]
                                      (reset! captured opts)
                                      {:status :success})
                    model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      ;; Minimal session stub — just enough for body-fn to
                      ;; produce mcp-opts and call the (redefd) llm fns.
                      (let [result (body-fn {:dir "/tmp/fake-session"
                                             :workdir "/tmp/fake-workdir"
                                             :mcp-config-path "/tmp/fake-session/mcp-config.json"
                                             :mcp-allowed-tools []
                                             :supervision {}
                                             :pre-session-snapshot {}})]
                        {:llm-result result
                         :artifact nil
                         :worktree-artifacts {}
                         :context-misses nil
                         :pre-session-snapshot {}
                         :session-mode :host}))]
        (let [agent (planner/create-planner {:llm-backend fake-llm-client})]
          (try
            (core/invoke agent {:llm-backend fake-llm-client
                                :title "t"
                                :description "t"
                                :intent "t"}
                         "build a thing")
            (catch Exception _))
          (is (some? @captured) "LLM client should have been called")
          (is (vector? (:disallowed-tools @captured)))
          (is (= @#'planner/planner-disallowed-tools
                 (:disallowed-tools @captured))
              ":disallowed-tools opt must equal the planner's role-scoped list"))))))

(deftest planner-progress-monitor-thresholds-loaded-test
  ;; Guards the 2026-05-04 stagnation-threshold fix at the planner
  ;; boundary: a regression in prompt loading or
  ;; create-planner-progress-monitor would otherwise let the threshold
  ;; values silently drift back to a too-tight default. Asserts the
  ;; loaded :progress-monitor opt carries the resources/prompts/planner.edn
  ;; values that give Opus room to think on heavy first turns.
  (testing ":progress-monitor passed to LLM reflects planner.edn thresholds"
    (let [captured (atom nil)
          fake-llm-client {:type :fake}
          fake-plan {:plan/id (random-uuid)
                     :plan/name "stub"
                     :plan/tasks [{:task/id (random-uuid)
                                   :task/description "t"
                                   :task/type :implement
                                   :task/acceptance-criteria ["ok"]
                                   :task/estimated-effort :small}]}]
      (with-redefs [llm/success? (constantly true)
                    llm/get-content (constantly (str "```clojure\n"
                                                     (pr-str fake-plan)
                                                     "\n```"))
                    llm/chat (fn [_client _prompt opts]
                               (reset! captured opts)
                               {:status :success})
                    llm/chat-stream (fn [_client _prompt _on-chunk opts]
                                      (reset! captured opts)
                                      {:status :success})
                    model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    artifact-session/with-session
                    (fn [_context body-fn]
                      (let [result (body-fn {:dir "/tmp/fake-session"
                                             :workdir "/tmp/fake-workdir"
                                             :mcp-config-path "/tmp/fake-session/mcp-config.json"
                                             :mcp-allowed-tools []
                                             :supervision {}
                                             :pre-session-snapshot {}})]
                        {:llm-result result
                         :artifact nil
                         :worktree-artifacts {}
                         :context-misses nil
                         :pre-session-snapshot {}
                         :session-mode :host}))]
        (let [agent (planner/create-planner {:llm-backend fake-llm-client})]
          (try
            (core/invoke agent {:llm-backend fake-llm-client
                                :title "t"
                                :description "t"
                                :intent "t"}
                         "build a thing")
            (catch Exception _))
          (is (some? @captured) "LLM client should have been called")
          (let [monitor (:progress-monitor @captured)]
            (is (some? monitor)
                ":progress-monitor opt must reach the LLM client")
            (let [state @monitor]
              (is (>= (:stagnation-threshold-ms state)
                      min-stagnation-threshold-ms)
                  "Stagnation threshold must be ≥ min-stagnation-threshold-ms — Opus needs room for the pre-first-chunk think on heavy planner prompts")
              (is (>= (:max-total-ms state) min-total-budget-ms)
                  "Total budget must be ≥ min-total-budget-ms — covers the longest historical successful planner run"))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.planner-test)

  :leave-this-here)
