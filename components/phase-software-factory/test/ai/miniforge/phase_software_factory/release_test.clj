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

(ns ai.miniforge.phase-software-factory.release-test
  "Unit tests for the release phase interceptor.

  Tests file writing, persistence validation, and failure modes.

  In the new environment model, code changes live in the execution
  environment's git working tree rather than being serialized into phase
  results. Tests that exercise file-based behavior write mock files to the
  test worktree before invoking the phase, simulating what the implement
  agent would do in production."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [babashka.fs :as fs]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase-software-factory.release :as release]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Test Fixtures

(defn create-temp-worktree
  "Create a temporary directory initialized as a real git repository.
   Required for git-dirty-files to work in the new environment model."
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "release-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    ;; Initialize a real git repository so git status --porcelain works
    (shell/sh "git" "init" :dir (.getPath temp-dir))
    (.getPath temp-dir)))

(defn cleanup-temp-worktree
  [dir-path]
  (when dir-path
    (try
      (fs/delete-tree dir-path)
      (catch Exception _e nil))))

(defn with-test-worktree
  [f]
  (let [worktree (create-temp-worktree)]
    (try
      (f worktree)
      (finally
        (cleanup-temp-worktree worktree)))))

;------------------------------------------------------------------------------ Mock Data

(def mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest t (is true))"
                 :action :create}]
   :code/language "clojure"})

(def mock-implement-result
  "New-model implement phase result: carries environment reference and summary,
   NOT serialized :code/files. Code changes live in the worktree."
  {:status         :success
   :environment-id "test-environment-id"
   :summary        "Implemented feature: Add feature (2 files)"
   :metrics        {:tokens 1500 :duration-ms 3200}})

(defn write-mock-files-to-worktree!
  "Write mock code artifact files to the test worktree.
   Simulates what the implement phase does in production: writing code
   directly to the execution environment's working directory."
  [worktree]
  (doseq [{:keys [path content]} (:code/files mock-code-artifact)]
    (let [file (io/file worktree path)]
      (io/make-parents file)
      (spit file content))))

(defn create-base-context
  "Create a test context with mock files already written to the test worktree.
   In the new environment model, code changes are in the worktree (not phase
   results). Most tests use this context."
  [worktree]
  ;; Write mock files to the worktree, simulating what the implement phase does
  (write-mock-files-to-worktree! worktree)
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result mock-implement-result}}
   :worktree-path worktree})

(defn create-empty-context
  "Create a test context with NO files written to the worktree.
   Used to test zero-files detection (no git dirty files in environment)."
  [worktree]
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result mock-implement-result}}
   :worktree-path worktree})

;------------------------------------------------------------------------------ Layer 0: Defaults Tests

(deftest default-config-test
  (testing "release phase has correct default configuration"
    (is (= :releaser (:agent release/default-config)))
    (is (= [:release-ready] (:gates release/default-config)))
    (is (map? (:budget release/default-config)))
    (is (= 5000 (get-in release/default-config [:budget :tokens])))
    (is (= 2 (get-in release/default-config [:budget :iterations])))
    (is (= 180 (get-in release/default-config [:budget :time-seconds])))))

(deftest phase-defaults-registration-test
  (testing "release phase defaults are registered"
    (let [defaults (phase/phase-defaults :release)]
      (is (some? defaults))
      (is (= :releaser (:agent defaults)))
      (is (= [:release-ready] (:gates defaults))))))

;------------------------------------------------------------------------------ File Writing Tests

(deftest release-writes-files-to-temp-directory-test
  (testing "release phase writes files to temporary worktree"
    (with-test-worktree
      (fn [worktree]
      (with-redefs [release-executor/execute-release-phase
                    (fn [workflow-state exec-context _opts]
                      ;; Write files directly to temp worktree (skip git staging)
                      (let [worktree (:worktree-path exec-context)
                            code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                            files (mapcat :code/files code-artifacts)]
                        (doseq [{:keys [path content]} files]
                          (let [file (io/file worktree path)]
                            (io/make-parents file)
                            (spit file content)))
                        {:success? true
                         :artifacts [{:artifact/id (random-uuid)
                                      :artifact/type :release
                                      :artifact/content {:files-written (count files)
                                                         :branch "test-branch"
                                                         :commit-sha "abc123"}}]
                         :metrics {:files-written (count files)}}))]
        (let [ctx (create-base-context worktree)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (phase/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)]

          (is (= :success (get-in result [:phase :result :status]))
              "Release phase should succeed")
          (is (.exists (io/file worktree "src/feature.clj"))
              "Source file should exist on disk")
          (is (.exists (io/file worktree "test/feature_test.clj"))
              "Test file should exist on disk")))))))

(deftest release-returns-correct-files-written-count-test
  (testing "release phase returns correct :files-written count"
    (with-test-worktree
      (fn [worktree]
      (with-redefs [release-executor/execute-release-phase
                    (fn [workflow-state exec-context _opts]
                      (let [worktree (:worktree-path exec-context)
                            code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                            files (mapcat :code/files code-artifacts)]
                        (doseq [{:keys [path content]} files]
                          (let [file (io/file worktree path)]
                            (io/make-parents file)
                            (spit file content)))
                        {:success? true
                         :artifacts [{:artifact/id (random-uuid)
                                      :artifact/type :release
                                      :artifact/content {:files-written (count files)}}]
                         :metrics {:files-written (count files)}}))]
        (let [ctx (create-base-context worktree)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (phase/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)
              files-written (get-in result [:phase :result :output :release/metrics :files-written])]
          (is (= 2 files-written)
              "Should report 2 files written (src + test)")))))))

(deftest release-fails-when-write-fails-test
  (testing "release phase fails when file write operation fails"
    (with-test-worktree
      (fn [worktree]
      (with-redefs [release-executor/execute-release-phase
                    (fn [_workflow-state _exec-context _opts]
                      {:success? false
                       :errors [{:type :file-write-failed
                                 :message "Permission denied writing to src/feature.clj"
                                 :file "src/feature.clj"}]
                       :metrics {:files-written 0}})]
        (let [ctx (create-base-context worktree)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (phase/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)]
          (is (false? (get-in result [:phase :result :success]))
              "Release should fail when write fails")
          (is (some? (get-in result [:phase :result :error]))
              "Error should be present in result")))))))

(deftest release-verifies-files-exist-after-writing-test
  (testing "release phase verifies files exist on disk after writing"
    (with-test-worktree
      (fn [worktree]
      (let [verification-performed (atom false)]
        (with-redefs [release-executor/execute-release-phase
                      (fn [workflow-state exec-context _opts]
                        (let [worktree (:worktree-path exec-context)
                              code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                              files (mapcat :code/files code-artifacts)]
                          (doseq [{:keys [path content action]} files]
                            (when (#{:create :modify} action)
                              (let [file (io/file worktree path)]
                                (io/make-parents file)
                                (spit file content))))
                          (doseq [{:keys [path action]} files]
                            (when (#{:create :modify} action)
                              (let [file-path (io/file worktree path)]
                                (reset! verification-performed true)
                                (when-not (.exists file-path)
                                  (throw (ex-info "File verification failed"
                                                  {:file path}))))))
                          {:success? true
                           :artifacts [{:artifact/id (random-uuid)
                                        :artifact/type :release
                                        :artifact/content {:files-written (count files)
                                                           :files-verified (count (filter #(#{:create :modify} (:action %)) files))}}]
                           :metrics {:files-written (count files)}}))]
          (let [ctx (create-base-context worktree)
                ctx-with-config (assoc ctx :phase-config {:phase :release})
                interceptor (phase/get-phase-interceptor {:phase :release})
                result ((:enter interceptor) ctx-with-config)]
            (is @verification-performed
                "File verification should be performed")
            (is (= :success (get-in result [:phase :result :status]))
                "Release should succeed when verification passes"))))))))

(deftest release-handles-zero-files-artifact-test
  (testing "release phase fails fast when no files changed in the environment"
    (with-test-worktree
      (fn [worktree]
      (let [ctx (create-empty-context worktree)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (phase/get-phase-interceptor {:phase :release})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Release phase received code artifact with zero files"
                              ((:enter interceptor) ctx-with-config))
            "Release should fail fast when environment has no changed files"))))))

(deftest release-ignores-non-substantive-paths-test
  (testing "iter-23 regression: a worktree dirty ONLY with .miniforge-session-id
            must not be treated as releasable work"
    (with-test-worktree
      (fn [worktree]
        ;; Simulate only a runtime session marker in the worktree.
        (spit (io/file worktree ".miniforge-session-id") "session-abc")
        (let [ctx (create-empty-context worktree)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (phase/get-phase-interceptor {:phase :release})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Release phase received code artifact with zero files"
                                ((:enter interceptor) ctx-with-config))
              "Session-marker-only diffs must filter to empty — no empty-diff PR"))))))

(deftest release-includes-pr-info-test
  (testing "release phase includes PR info in result"
    (with-test-worktree
      (fn [worktree]
      (with-redefs [release-executor/execute-release-phase
                    (fn [_workflow-state _exec-context _opts]
                      {:success? true
                       :artifacts [{:artifact/id (random-uuid)
                                    :artifact/type :release
                                    :artifact/content {:files-written 2
                                                       :branch "feature/test-123"
                                                       :commit-sha "def456"
                                                       :pr-number 42
                                                       :pr-url "https://github.com/org/repo/pull/42"}}]
                       :metrics {:files-written 2}})]
        (let [ctx (create-base-context worktree)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (phase/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)]
          (is (some? (get-in result [:workflow/pr-info]))
              "PR info should be available at top level")
          (let [pr-info (get-in result [:workflow/pr-info])]
            (is (= 42 (:pr-number pr-info))
                "PR number should be captured")
            (is (= "https://github.com/org/repo/pull/42" (:pr-url pr-info))
                "PR URL should be captured")
            (is (= "feature/test-123" (:branch pr-info))
                "Branch name should be captured"))))))))

(deftest release-propagates-streaming-callback-test
  (testing "release phase passes event-stream-backed on-chunk callback to the executor"
    (with-test-worktree
      (fn [worktree]
      (let [stream (es/create-event-stream {:sinks []})]
        (with-redefs [release-executor/execute-release-phase
                      (fn [_workflow-state exec-context _opts]
                        (is (fn? (:on-chunk exec-context))
                            "Release executor should receive an on-chunk callback")
                        ((:on-chunk exec-context) {:delta "release chunk" :done? false})
                        {:success? true
                         :artifacts [{:artifact/id (random-uuid)
                                      :artifact/type :release
                                      :artifact/content {:files-written 1}}]
                         :metrics {:files-written 1}})]
          (let [ctx (assoc (create-base-context worktree) :event-stream stream)
                ctx-with-config (assoc ctx :phase-config {:phase :release})
                interceptor (phase/get-phase-interceptor {:phase :release})
                result ((:enter interceptor) ctx-with-config)
                chunk-events (es/get-events stream {:event-type :agent/chunk})]
            (is (= :success (get-in result [:phase :result :status])))
            (is (= 1 (count chunk-events)))
            (is (= "release chunk" (:chunk/delta (first chunk-events))))
            (is (= :release (:agent/id (first chunk-events)))))))))))

;------------------------------------------------------------------------------ Regression: already-satisfied / nil implement status

(deftest release-skips-when-plan-already-satisfied-test
  (testing "release phase skips without NPE when plan returned already-satisfied (0 DAG tasks)"
    ;; When the planner detects specs are already satisfied, the DAG runs with
    ;; 0 tasks. The implement result has nil :status. The release phase must
    ;; short-circuit cleanly instead of throwing :release/zero-files and then
    ;; NPE-ing in leave-release on (- end-time nil).
    (with-test-worktree
      (fn [worktree]
      (let [ctx {:execution/id (random-uuid)
                 :execution/input {:description "Already done" :title "Noop"}
                 :execution/metrics {:tokens 0 :duration-ms 0}
                 :execution/phase-results {:implement {:result {:status nil}}}
                 :worktree-path worktree}
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (phase/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]
        (is (= :completed (get-in result [:phase :status]))
            "Release should complete (skip) when implement status is nil and no dirty files"))))))

(deftest release-skips-when-implement-already-implemented-test
  (testing "release phase skips when implement returned :already-implemented"
    (with-test-worktree
      (fn [worktree]
      (let [ctx {:execution/id (random-uuid)
                 :execution/input {:description "Already done" :title "Noop"}
                 :execution/metrics {:tokens 0 :duration-ms 0}
                 :execution/phase-results {:implement {:result {:status :already-implemented}}}
                 :worktree-path worktree}
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (phase/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]
        (is (= :completed (get-in result [:phase :status]))
            "Release should complete (skip) when implement status is :already-implemented"))))))

(deftest leave-release-handles-nil-start-time-test
  (testing "leave-release does not NPE when :started-at is nil"
    ;; When enter-release throws before setting :started-at, leave-release
    ;; must handle nil start-time gracefully.
    (let [interceptor (phase/get-phase-interceptor {:phase :release})
          ctx {:phase {:result {:status :error}
                       :budget {:iterations 2}}
               :execution/metrics {:tokens 0 :duration-ms 0}}
          result ((:leave interceptor) ctx)]
      (is (some? result) "leave-release should not throw")
      (is (= 0 (get-in result [:phase :duration-ms]))
          "Duration should default to 0 when start-time is nil"))))

;------------------------------------------------------------------------------ Layer 2: Interceptor Leave Tests

(deftest leave-release-records-metrics-test
  (testing "leave-release records duration and completion"
    (with-test-worktree
      (fn [worktree]
      (with-redefs [release-executor/execute-release-phase
                    (fn [_workflow-state _exec-context _opts]
                      {:success? true
                       :artifacts [{:artifact/id (random-uuid)
                                    :artifact/type :release
                                    :artifact/content {:files-written 2}}]
                       :metrics {:files-written 2 :tokens 300 :duration-ms 800}})]
        (let [ctx (create-base-context worktree)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (phase/get-phase-interceptor {:phase :release})
              enter-result ((:enter interceptor) ctx-with-config)
              final-result ((:leave interceptor) enter-result)]
          (is (= :completed (get-in final-result [:phase :status]))
              "Phase status should be completed")
          (is (number? (get-in final-result [:phase :duration-ms]))
              "Duration should be recorded")
          (is (= 300 (get-in final-result [:phase :metrics :tokens]))
              "Token metrics should be recorded")
          (is (= :release (first (get-in final-result [:execution :phases-completed])))
              "Release should be added to phases-completed")))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.release-test)
  :leave-this-here)
