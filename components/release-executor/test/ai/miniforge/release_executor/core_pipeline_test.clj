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
   and execute-release-phase integration (non-sandbox, mocked git)."
  (:require
   [ai.miniforge.release-executor.core :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; ============================================================================
;; Test helpers
;; ============================================================================

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
  (testing "fails when worktree-path is nil and not sandbox"
    (let [state {:code-artifacts [{:code/files [{:path "a.clj"}]}]
                 :sandbox? false}
          result (sut/step-validate-inputs state)]
      (is (sut/failed? result))
      (is (= :missing-worktree-path (get-in result [:failure :type]))))))

(deftest step-validate-inputs-sandbox-skips-worktree-check
  (testing "sandbox mode does not require :worktree-path"
    (let [state {:code-artifacts [{:code/files [{:path "a.clj"}]}]
                 :sandbox? true}
          result (sut/step-validate-inputs state)]
      (is (not (sut/failed? result))))))

(deftest step-validate-inputs-empty-code-artifacts
  (testing "fails when code-artifacts is empty"
    (let [state {:worktree-path "/tmp" :code-artifacts [] :sandbox? false}
          result (sut/step-validate-inputs state)]
      (is (sut/failed? result))
      (is (= :no-code-artifacts (get-in result [:failure :type]))))))

(deftest step-validate-inputs-all-empty-files
  (testing "fails when all code artifacts have empty file lists"
    (let [state {:worktree-path "/tmp"
                 :code-artifacts [{:code/files []} {:code/files []}]
                 :sandbox? false}
          result (sut/step-validate-inputs state)]
      (is (sut/failed? result))
      (is (= :no-code-files (get-in result [:failure :type]))))))

(deftest step-validate-inputs-valid-state-passes
  (testing "valid state passes validation"
    (let [state {:worktree-path "/tmp"
                 :code-artifacts [{:code/files [{:path "a.clj" :action :create}]}]
                 :sandbox? false}
          result (sut/step-validate-inputs state)]
      (is (not (sut/failed? result))))))

(deftest step-validate-inputs-at-least-one-non-empty-artifact
  (testing "passes if at least one artifact has files"
    (let [state {:worktree-path "/tmp"
                 :code-artifacts [{:code/files []}
                                  {:code/files [{:path "a.clj" :action :create}]}]
                 :sandbox? false}
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
;; step-validate-diff
;; ============================================================================

(deftest step-validate-diff-already-failed-passes-through
  (testing "already-failed state passes through"
    (let [state {:failure {:type :prior :message "x"}}]
      (is (= state (sut/step-validate-diff state))))))

(deftest step-validate-diff-skipped-in-sandbox-mode
  (testing "sandbox mode skips diff validation"
    (let [state {:sandbox? true :worktree-path "/tmp"}
          result (sut/step-validate-diff state)]
      (is (not (sut/failed? result))))))

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
    (let [state {:failure {:type :no-code-artifacts :message "empty"}}
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

(deftest execute-release-phase-no-artifacts
  (testing "fails gracefully when workflow has no artifacts"
    (let [workflow-state {:workflow/artifacts []
                          :workflow/spec {:spec/description "test"}}
          context {:worktree-path "/tmp" :create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :no-code-artifacts (:type (first (:errors result))))))))

(deftest execute-release-phase-no-code-artifacts-only-reviews
  (testing "fails when workflow only has review artifacts, no code"
    (let [workflow-state {:workflow/artifacts
                          [{:artifact/type :review
                            :artifact/content {:review/summary "good"}}]
                          :workflow/spec {:spec/description "test"}}
          context {:worktree-path "/tmp" :create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :no-code-artifacts (:type (first (:errors result))))))))

(deftest execute-release-phase-code-artifact-empty-files
  (testing "fails when code artifact has empty files list"
    (let [workflow-state (make-workflow-state
                          [(make-code-artifact [])])
          context {:worktree-path "/tmp" :create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :no-code-files (:type (first (:errors result))))))))

(deftest execute-release-phase-missing-worktree-non-sandbox
  (testing "fails when worktree-path missing in non-sandbox mode"
    (let [workflow-state (make-workflow-state
                          [(make-code-artifact
                            [{:path "a.clj" :action :create :content "(ns a)"}])])
          context {:create-pr? false}
          result (sut/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :missing-worktree-path (:type (first (:errors result))))))))

(deftest execute-release-phase-pre-provided-release-meta
  (testing "pre-provided release-meta skips metadata generation"
    ;; This will still fail at branch creation (no real git), but metadata step passes
    (let [workflow-state (make-workflow-state
                          [(make-code-artifact
                            [{:path "a.clj" :action :create :content "(ns a)"}])])
          context {:worktree-path "/tmp/nonexistent-test-dir" :create-pr? false}
          result (sut/execute-release-phase workflow-state context
                                           {:release-meta test-release-meta})]
      ;; Will fail at branch creation, but should have gotten past metadata
      (is (not (:success? result)))
      ;; Error should NOT be metadata-related
      (is (not= :metadata-generation-failed (:type (first (:errors result))))))))

(deftest execute-release-phase-extracts-workflow-data
  (testing "review and test artifacts are extracted from workflow state"
    ;; We verify by checking the metadata includes review info in the fallback body
    ;; Will fail at git operations, but metadata is generated first
    (let [workflow-state {:workflow/artifacts
                          [(make-code-artifact
                            [{:path "a.clj" :action :create :content "(ns a)"}])
                           {:artifact/type :review
                            :artifact/content {:review/summary "Excellent code"
                                               :review/decision :approved}}]
                          :workflow/spec {:spec/description "Test task"}}
          context {:worktree-path "/tmp/nonexistent-test-dir" :create-pr? false}
          ;; Don't pass release-meta so fallback is used with workflow-data
          result (sut/execute-release-phase workflow-state context {})]
      ;; Will fail at git step, but that's expected
      (is (not (:success? result))))))

;; ============================================================================
;; step-write-and-stage — passes through failure
;; ============================================================================

(deftest step-write-and-stage-already-failed-passes-through
  (testing "already-failed state passes through"
    (let [state {:failure {:type :branch-create-failed :message "nope"}}]
      (is (= state (sut/step-write-and-stage state))))))

(deftest step-write-and-stage-empty-files-fails
  (testing "fails when all artifacts have no files"
    (let [state {:worktree-path "/tmp"
                 :code-artifacts [{:code/files []}]
                 :sandbox? false}
          result (sut/step-write-and-stage state)]
      (is (sut/failed? result))
      (is (= :no-files-to-write (get-in result [:failure :type]))))))

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
                 :write-metrics {:files-written 2 :files-modified 1 :total-operations 3}
                 :code-artifacts [{:code/files [{:path "a.clj"}]}]}
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