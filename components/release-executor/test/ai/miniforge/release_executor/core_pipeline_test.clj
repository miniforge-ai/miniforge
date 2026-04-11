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

(ns ai.miniforge.release-executor.core-pipeline-test
  "Unit tests for release-executor core pipeline helpers, step functions,
   and execute-release-phase integration.

   In the environment model, files live in the executor worktree — no code
   artifact file writes. Tests use pre-provided :release-meta to skip LLM."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.release-executor.core :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn create-failing-mock-executor
  "Mock executor that always fails, used to reach a specific pipeline stage."
  []
  (reify
    dag/TaskExecutor
    (executor-type [_] :mock-failing)
    (available? [_] (dag/ok {:available? true}))
    (acquire-environment! [_ _ _] (dag/ok {:environment-id "mock-env"}))
    (execute! [_ _env-id _command _opts]
      (dag/ok {:exit-code 1 :stdout "" :stderr "mock error"}))
    (copy-to! [_ _ _ _] (dag/ok {}))
    (copy-from! [_ _ _ _] (dag/ok {}))
    (release-environment! [_ _] (dag/ok {:released? true}))
    (environment-status [_ _] (dag/ok {:status :running}))))

(def test-release-meta
  {:release/branch-name "mf/test-change-abc12345"
   :release/commit-message "Test change"
   :release/pr-title "Test change"
   :release/pr-description "## Summary\nTest change"
   :release/pr-body "## Summary\nTest change"})

(defn make-code-artifact
  "Create a minimal code artifact with files."
  [files]
  {:artifact/type :code
   :artifact/content {:code/id (random-uuid)
                      :code/files files}})

(defn make-workflow-state
  "Create a workflow state with artifacts."
  [artifacts & {:keys [description]
                :or {description "test task"}}]
  {:workflow/artifacts artifacts
   :workflow/spec {:spec/description description}})

;; ============================================================================
;; failed? and fail
;; ============================================================================

(deftest failed?-returns-false-for-normal-state
  (testing "state without :failure is not failed"
    (is (not (sut/failed? {:worktree-path "/tmp/foo"})))))

(deftest failed?-returns-true-for-failed-state
  (testing "state with :failure key is failed"
    (is (sut/failed? {:failure {:type :test :message "boom"}}))))

(deftest fail-adds-failure-map
  (testing "fail sets :failure with type and message"
    (let [result (sut/fail {} :test-error "something broke")]
      (is (sut/failed? result))
      (is (= :test-error (get-in result [:failure :type])))
      (is (= "something broke" (get-in result [:failure :message]))))))

(deftest fail-with-hint
  (testing "fail can include a hint"
    (let [result (sut/fail {} :auth-error "not logged in" :hint "Run: gh auth login")]
      (is (= "Run: gh auth login" (get-in result [:failure :hint]))))))

(deftest fail-without-hint-has-no-hint-key
  (testing "fail without hint omits :hint from failure map"
    (let [result (sut/fail {} :some-error "msg")]
      (is (not (contains? (:failure result) :hint))))))

;; ============================================================================
;; extract-code-artifacts
;; ============================================================================

(deftest extract-code-artifacts-from-artifact-type
  (testing "extracts content from :artifact/type :code"
    (let [artifacts [(make-code-artifact [{:path "a.clj" :action :create}])]
          result (sut/extract-code-artifacts artifacts)]
      (is (= 1 (count result)))
      (is (= [{:path "a.clj" :action :create}]
             (:code/files (first result)))))))

(deftest extract-code-artifacts-from-type
  (testing "extracts content from :type :code"
    (let [artifacts [{:type :code :content {:code/files [{:path "b.clj" :action :modify}]}}]
          result (sut/extract-code-artifacts artifacts)]
      (is (= 1 (count result)))
      (is (= [{:path "b.clj" :action :modify}]
             (:code/files (first result)))))))

(deftest extract-code-artifacts-ignores-non-code
  (testing "non-code artifacts are excluded"
    (let [artifacts [{:artifact/type :review :artifact/content {:review/summary "ok"}}
                     {:artifact/type :test :artifact/content {:test/results :passed}}
                     (make-code-artifact [{:path "a.clj" :action :create}])]
          result (sut/extract-code-artifacts artifacts)]
      (is (= 1 (count result))))))

(deftest extract-code-artifacts-empty-list
  (testing "empty artifact list returns empty seq"
    (is (empty? (sut/extract-code-artifacts [])))))

(deftest extract-code-artifacts-nil-content-filtered
  (testing "artifacts with nil content are filtered out"
    (let [artifacts [{:artifact/type :code :artifact/content nil}]
          result (sut/extract-code-artifacts artifacts)]
      (is (empty? result)))))

;; ============================================================================
;; extract-workflow-data
;; ============================================================================

(deftest extract-workflow-data-separates-reviews-and-tests
  (testing "returns a map with :review-artifacts and :test-artifacts"
    (let [artifacts [{:artifact/type :review :artifact/content {:review/summary "good"}}
                     {:artifact/type :test :artifact/content {:test/results :passed}}
                     {:artifact/type :code :artifact/content {:code/files []}}]
          result (sut/extract-workflow-data artifacts)]
      (is (= 1 (count (:review-artifacts result))))
      (is (= 1 (count (:test-artifacts result))))
      (is (= "good" (:review/summary (first (:review-artifacts result)))))
      (is (= :passed (:test/results (first (:test-artifacts result))))))))

(deftest extract-workflow-data-empty-artifacts
  (testing "empty artifacts produce empty review and test lists"
    (let [result (sut/extract-workflow-data [])]
      (is (empty? (:review-artifacts result)))
      (is (empty? (:test-artifacts result))))))

;; ============================================================================
;; step-validate-inputs
;; ============================================================================

(deftest step-validate-inputs-already-failed-passes-through
  (testing "already-failed state is returned unchanged"
    (let [state {:failure {:type :prior :message "earlier"}}]
      (is (= state (sut/step-validate-inputs state))))))

(deftest step-validate-inputs-missing-worktree-path
  (testing "fails when :worktree-path is absent"
    (let [state {:executor :some-executor :environment-id "env-1"}
          result (sut/step-validate-inputs state)]
      (is (sut/failed? result))
      (is (= :missing-worktree-path (get-in result [:failure :type]))))))

(deftest step-validate-inputs-missing-executor
  (testing "fails when :executor is absent"
    (let [state {:worktree-path "/tmp/wt" :environment-id "env-1"}
          result (sut/step-validate-inputs state)]
      (is (sut/failed? result))
      (is (= :missing-executor (get-in result [:failure :type]))))))

(deftest step-validate-inputs-missing-environment-id
  (testing "fails when :environment-id is absent"
    (let [state {:worktree-path "/tmp/wt" :executor :some-executor}
          result (sut/step-validate-inputs state)]
      (is (sut/failed? result))
      (is (= :missing-environment-id (get-in result [:failure :type]))))))

(deftest step-validate-inputs-valid-state-passes
  (testing "state with worktree-path, executor, and environment-id passes"
    (let [state {:worktree-path "/tmp/wt"
                 :executor :some-executor
                 :environment-id "env-1"}
          result (sut/step-validate-inputs state)]
      (is (not (sut/failed? result))))))

;; ============================================================================
;; step-check-gh-auth
;; ============================================================================

(deftest step-check-gh-auth-skipped-when-not-creating-pr
  (testing "gh auth check is skipped when create-pr? is false"
    (let [state {:create-pr? false}
          result (sut/step-check-gh-auth state)]
      (is (not (sut/failed? result))))))

(deftest step-check-gh-auth-skipped-when-already-failed
  (testing "already-failed state passes through"
    (let [state {:failure {:type :prior :message "x"} :create-pr? true}]
      (is (sut/failed? (sut/step-check-gh-auth state))))))

;; ============================================================================
;; step-generate-metadata
;; ============================================================================

(deftest step-generate-metadata-already-failed-passes-through
  (testing "already-failed state passes through"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-generate-metadata state))))))

(deftest step-generate-metadata-uses-pre-provided-meta
  (testing "pre-provided release-meta is used as-is"
    (let [state {:release-meta test-release-meta}
          result (sut/step-generate-metadata state)]
      (is (= test-release-meta (:release-meta result))))))

(deftest step-generate-metadata-generates-fallback
  (testing "generates fallback metadata when no release-meta or releaser"
    (let [state {:code-artifacts [{:code/files [{:path "a.clj" :action :create}]}]
                 :task-description "Add feature X"
                 :context {}}
          result (sut/step-generate-metadata state)]
      (is (not (sut/failed? result)))
      (is (contains? (:release-meta result) :release/branch-name))
      (is (= "Add feature X" (:release/pr-title (:release-meta result)))))))

(deftest step-generate-metadata-with-workflow-data
  (testing "workflow-data review artifacts appear in generated metadata"
    (let [state {:code-artifacts [{:code/files [{:path "a.clj" :action :create}]}]
                 :task-description "Fix"
                 :context {}
                 :workflow-data {:review-artifacts
                                [{:review/summary "Clean code"
                                  :review/decision :approved}]}}
          result (sut/step-generate-metadata state)
          body (get-in result [:release-meta :release/pr-body])]
      (is (str/includes? body "## Review"))
      (is (str/includes? body "Clean code")))))

;; ============================================================================
;; step-stage-dirty-files — passes through failure
;; ============================================================================

(deftest step-stage-dirty-files-already-failed-passes-through
  (testing "already-failed state passes through"
    (let [state {:failure {:type :branch-create-failed :message "nope"}}]
      (is (= state (sut/step-stage-dirty-files state))))))

;; ============================================================================
;; step-validate-diff
;; ============================================================================

(deftest step-validate-diff-already-failed-passes-through
  (testing "already-failed state passes through"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-validate-diff state))))))

;; ============================================================================
;; pipeline->result
;; ============================================================================

(deftest pipeline-result-on-failure
  (testing "failure state produces phase-failure result"
    (let [state {:failure {:type :branch-create-failed
                           :message "could not create branch"
                           :hint "check git"}
                 :write-metrics {:files-written 2}}
          result (sut/pipeline->result state)]
      (is (not (:success? result)))
      (is (= [] (:artifacts result)))
      (is (seq (:errors result)))
      (is (= :branch-create-failed (:type (first (:errors result)))))
      (is (= {:files-written 2} (:metrics result))))))

(deftest pipeline-result-on-success
  (testing "success state produces phase-success result with metrics"
    (let [artifact {:artifact/id (random-uuid) :artifact/type :release}
          state {:release-artifact artifact
                 :write-metrics {:files-written 3 :files-modified 1}
                 :branch "mf/test"
                 :commit-sha "abc123"
                 :pr-number 42
                 :pr-url "https://github.com/o/r/pull/42"}
          result (sut/pipeline->result state)]
      (is (:success? result))
      (is (= [artifact] (:artifacts result)))
      (is (= [] (:errors result)))
      (is (= 3 (get-in result [:metrics :files-written])))
      (is (= 42 (get-in result [:metrics :pr-number])))
      (is (= "abc123" (get-in result [:metrics :commit-sha])))
      (is (= "mf/test" (get-in result [:metrics :branch]))))))

(deftest pipeline-result-failure-without-metrics
  (testing "failure without write-metrics uses empty map"
    (let [state {:failure {:type :no-files-to-stage :message "empty"}}
          result (sut/pipeline->result state)]
      (is (not (:success? result)))
      (is (= {} (:metrics result))))))

(deftest pipeline-result-success-nil-pr-fields
  (testing "success without PR fields has nil pr-number and pr-url"
    (let [state {:release-artifact {:artifact/type :release}
                 :write-metrics {}
                 :branch "mf/test"
                 :commit-sha "def456"}
          result (sut/pipeline->result state)]
      (is (:success? result))
      (is (nil? (get-in result [:metrics :pr-number])))
      (is (nil? (get-in result [:metrics :pr-url]))))))

;; ============================================================================
;; execute-release-phase — integration (short-circuit paths)
;; ============================================================================

(deftest execute-release-phase-missing-worktree-path
  (testing "fails when :worktree-path is absent"
    (let [workflow-state (make-workflow-state [])
          context {:executor :some-executor :environment-id "env-1" :create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :missing-worktree-path (:type (first (:errors result))))))))

(deftest execute-release-phase-missing-executor
  (testing "fails when :executor is absent"
    (let [workflow-state (make-workflow-state [])
          context {:worktree-path "/tmp/wt" :environment-id "env-1" :create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :missing-executor (:type (first (:errors result))))))))

(deftest execute-release-phase-missing-environment-id
  (testing "fails when :environment-id is absent"
    (let [workflow-state (make-workflow-state [])
          context {:worktree-path "/tmp/wt" :executor :some-executor :create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :missing-environment-id (:type (first (:errors result))))))))

(deftest execute-release-phase-pre-provided-release-meta
  (testing "pre-provided release-meta skips metadata generation"
    ;; Fails at branch creation (mock executor returns exit 1), but metadata step passes
    (let [workflow-state (make-workflow-state [])
          exec (create-failing-mock-executor)
          context {:worktree-path "/tmp/wt" :executor exec
                   :environment-id "env-1" :create-pr? false}
          result (sut/execute-release-phase workflow-state context
                                           {:release-meta test-release-meta})]
      ;; Error should NOT be metadata-related — pre-provided meta was used
      (is (not= :metadata-generation-failed (:type (first (:errors result))))))))

(deftest execute-release-phase-extracts-workflow-data
  (testing "review and test artifacts are extracted from workflow state"
    ;; step-generate-metadata uses workflow-data before any executor call
    (let [state {:code-artifacts []
                 :task-description "Test task"
                 :context {}
                 :workflow-data {:review-artifacts
                                [{:review/summary "Excellent code"
                                  :review/decision :approved}]}}
          result (sut/step-generate-metadata state)
          body (get-in result [:release-meta :release/pr-body])]
      ;; Review content appears in the generated PR body
      (is (str/includes? body "Excellent code")))))

;; ============================================================================
;; step-commit / step-push / step-create-pr — pass through failure
;; ============================================================================

(deftest step-commit-already-failed
  (testing "already-failed state passes through commit"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-commit state))))))

(deftest step-push-already-failed
  (testing "already-failed state passes through push"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-push state))))))

(deftest step-push-skipped-when-not-creating-pr
  (testing "push is skipped when create-pr? is false"
    (let [state {:create-pr? false :branch "mf/test"}
          result (sut/step-push state)]
      (is (not (sut/failed? result))))))

(deftest step-create-pr-already-failed
  (testing "already-failed state passes through PR creation"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-create-pr state))))))

(deftest step-create-pr-skipped-when-not-creating-pr
  (testing "PR creation is skipped when create-pr? is false"
    (let [state {:create-pr? false}
          result (sut/step-create-pr state)]
      (is (not (sut/failed? result))))))

;; ============================================================================
;; step-build-artifact
;; ============================================================================

(deftest step-build-artifact-already-failed
  (testing "already-failed state passes through artifact building"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-build-artifact state))))))

(deftest step-build-artifact-produces-artifact
  (testing "produces a release artifact from pipeline state"
    (let [state {:worktree-path "/tmp/wt"
                 :branch "mf/test"
                 :base-branch "main"
                 :commit-sha "abc123"
                 :create-pr? false
                 :release-meta test-release-meta
                 :write-metrics {:files-written 2 :total-operations 2}
                 :code-artifacts []}
          result (sut/step-build-artifact state)]
      (is (not (sut/failed? result)))
      (is (some? (:release-artifact result))))))

;; ============================================================================
;; step-save-artifact
;; ============================================================================

(deftest step-save-artifact-already-failed
  (testing "already-failed state passes through artifact saving"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-save-artifact state))))))

(deftest step-save-artifact-without-store
  (testing "gracefully handles nil artifact store"
    (let [state {:release-artifact {:artifact/type :release}}]
      (is (not (sut/failed? (sut/step-save-artifact state)))))))

;; ============================================================================
;; step-create-branch — passes through failure
;; ============================================================================

(deftest step-create-branch-already-failed
  (testing "already-failed state passes through branch creation"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-create-branch state))))))

;; ============================================================================
;; PR doc generation tests
;; ============================================================================

(deftest pr-doc-filename-test
  (testing "generates date-prefixed slugified filename"
    (let [filename (#'sut/pr-doc-filename "feat: add auth middleware")]
      (is (str/starts-with? filename "2026-"))
      (is (str/ends-with? filename ".md"))
      (is (str/includes? filename "feat-add-auth-middleware"))))

  (testing "handles nil title"
    (let [filename (#'sut/pr-doc-filename nil)]
      (is (str/ends-with? filename ".md"))
      (is (str/includes? filename "untitled")))))

(deftest render-pr-doc-test
  (testing "renders markdown with YAML attribution header"
    (let [doc (#'sut/render-pr-doc
               {:release/pr-title "feat: add auth"
                :release/pr-description "## Summary\nAdded auth."
                :release/commit-message "feat: add auth"}
               {:pr-number 42
                :pr-url "https://github.com/org/repo/pull/42"
                :branch "feat/add-auth"})]
      (is (str/includes? doc "Christopher Lester"))
      (is (str/includes? doc "# feat: add auth"))
      (is (str/includes? doc "#42"))
      (is (str/includes? doc "feat/add-auth"))
      (is (str/includes? doc "## Summary"))))

  (testing "renders without PR info when not available"
    (let [doc (#'sut/render-pr-doc
               {:release/pr-title "fix: bug"
                :release/pr-description "Fixed it."
                :release/commit-message "fix: bug"}
               {:pr-number nil :pr-url nil :branch nil})]
      (is (str/includes? doc "# fix: bug"))
      (is (str/includes? doc "Fixed it."))))

  (testing "step-write-pr-doc passes through on failure"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-write-pr-doc state))))))

;; ============================================================================
;; format-files-changed
;; ============================================================================

(deftest format-files-changed-test
  (testing "formats code artifact files as markdown bullet list"
    (let [artifacts [{:code/files [{:path "src/foo.clj" :action :create}
                                   {:path "src/bar.clj" :action :modify}]}]
          result (#'sut/format-files-changed artifacts)]
      (is (str/includes? result "- `src/foo.clj` (create)"))
      (is (str/includes? result "- `src/bar.clj` (modify)"))))

  (testing "returns placeholder when no files"
    (let [result (#'sut/format-files-changed [])]
      (is (str/includes? result "No file changes recorded"))))

  (testing "handles multiple artifacts"
    (let [artifacts [{:code/files [{:path "a.clj" :action :create}]}
                     {:code/files [{:path "b.clj" :action :delete}]}]
          result (#'sut/format-files-changed artifacts)]
      (is (str/includes? result "- `a.clj` (create)"))
      (is (str/includes? result "- `b.clj` (delete)")))))

;; ============================================================================
;; format-test-results
;; ============================================================================

(deftest format-test-results-test
  (testing "formats test artifact with result, counts, and summary"
    (let [artifacts [{:test/results :passed
                      :test/total 10
                      :test/passed 10
                      :test/failed 0
                      :test/summary "All tests green."}]
          result (#'sut/format-test-results artifacts)]
      (is (str/includes? result "**Result**: passed"))
      (is (str/includes? result "**Passed**: 10/10"))
      (is (str/includes? result "All tests green."))))

  (testing "includes failed count when failures exist"
    (let [artifacts [{:test/results :failed
                      :test/total 10
                      :test/passed 8
                      :test/failed 2}]
          result (#'sut/format-test-results artifacts)]
      (is (str/includes? result "**Result**: failed"))
      (is (str/includes? result "8/10"))
      (is (str/includes? result "2 failed"))))

  (testing "returns placeholder when no test artifacts"
    (let [result (#'sut/format-test-results [])]
      (is (str/includes? result "No test artifacts available"))))

  (testing "uses latest artifact when multiple present"
    (let [artifacts [{:test/results :failed :test/summary "First run"}
                     {:test/results :passed :test/summary "Retry succeeded"}]
          result (#'sut/format-test-results artifacts)]
      (is (str/includes? result "**Result**: passed"))
      (is (str/includes? result "Retry succeeded")))))

;; ============================================================================
;; format-review-decision
;; ============================================================================

(deftest format-review-decision-test
  (testing "formats review decision and summary"
    (let [artifacts [{:review/decision :approved
                      :review/summary "Clean code, well-structured."}]
          result (#'sut/format-review-decision artifacts)]
      (is (str/includes? result "**Decision**: approved"))
      (is (str/includes? result "Clean code, well-structured."))))

  (testing "formats rejected review"
    (let [artifacts [{:review/decision :rejected
                      :review/summary "Missing error handling."}]
          result (#'sut/format-review-decision artifacts)]
      (is (str/includes? result "**Decision**: rejected"))
      (is (str/includes? result "Missing error handling."))))

  (testing "returns placeholder when no review artifacts"
    (let [result (#'sut/format-review-decision [])]
      (is (str/includes? result "No review artifacts available"))))

  (testing "handles review with decision but no summary"
    (let [artifacts [{:review/decision :approved}]
          result (#'sut/format-review-decision artifacts)]
      (is (str/includes? result "**Decision**: approved"))
      (is (not (str/includes? result "null"))))))

;; ============================================================================
;; render-pr-doc-full
;; ============================================================================

(deftest render-pr-doc-full-test
  (testing "renders comprehensive doc with all sections"
    (let [doc (#'sut/render-pr-doc-full
               {:release/pr-title "feat: add auth"
                :release/pr-description "Added authentication middleware."
                :release/commit-message "feat: add auth"}
               {:pr-number 42
                :pr-url "https://github.com/org/repo/pull/42"
                :branch "mf/feat-add-auth"}
               [{:code/files [{:path "src/auth.clj" :action :create}
                              {:path "src/middleware.clj" :action :modify}]}]
               {:review-artifacts [{:review/decision :approved
                                    :review/summary "Looks good."}]
                :test-artifacts [{:test/results :passed
                                  :test/total 5
                                  :test/passed 5
                                  :test/failed 0
                                  :test/summary "All pass."}]})]
      ;; Header
      (is (str/includes? doc "Christopher Lester"))
      (is (str/includes? doc "# feat: add auth"))
      ;; PR info
      (is (str/includes? doc "#42"))
      (is (str/includes? doc "mf/feat-add-auth"))
      ;; Summary
      (is (str/includes? doc "## Summary"))
      (is (str/includes? doc "Added authentication middleware."))
      ;; Files Changed
      (is (str/includes? doc "## Files Changed"))
      (is (str/includes? doc "- `src/auth.clj` (create)"))
      (is (str/includes? doc "- `src/middleware.clj` (modify)"))
      ;; Test Results
      (is (str/includes? doc "## Test Results"))
      (is (str/includes? doc "**Result**: passed"))
      (is (str/includes? doc "5/5"))
      ;; Review Decision
      (is (str/includes? doc "## Review Decision"))
      (is (str/includes? doc "**Decision**: approved"))
      (is (str/includes? doc "Looks good."))))

  (testing "renders with missing optional data"
    (let [doc (#'sut/render-pr-doc-full
               {:release/pr-title "fix: bug"
                :release/commit-message "fix: bug"}
               {:pr-number nil :pr-url nil :branch nil}
               []
               {})]
      (is (str/includes? doc "# fix: bug"))
      (is (str/includes? doc "## Summary"))
      (is (str/includes? doc "No summary available"))
      (is (str/includes? doc "## Files Changed"))
      (is (str/includes? doc "No file changes recorded"))
      (is (str/includes? doc "## Test Results"))
      (is (str/includes? doc "No test artifacts available"))
      (is (str/includes? doc "## Review Decision"))
      (is (str/includes? doc "No review artifacts available")))))

;; ============================================================================
;; step-generate-pr-doc
;; ============================================================================

(deftest step-generate-pr-doc-already-failed
  (testing "already-failed state passes through"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-generate-pr-doc state))))))

(deftest step-generate-pr-doc-skipped-when-not-creating-pr
  (testing "skipped when create-pr? is false"
    (let [state {:create-pr? false}
          result (sut/step-generate-pr-doc state)]
      (is (not (sut/failed? result))))))
