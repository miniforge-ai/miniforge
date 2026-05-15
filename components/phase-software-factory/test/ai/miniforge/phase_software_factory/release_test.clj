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
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase-software-factory.release :as release]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Test Fixtures

(def phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

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
              "Error should be present in result")
          (is (seq (get-in result [:phase :result :error :data :errors]))
              "Executor :errors must surface in :phase :result :error :data so the next dogfood run can diagnose without staring at the snapshot")))))))

(deftest release-surfaces-thrown-exception-in-result-test
  (testing "release phase preserves the exception class+message+ex-data when execute-release-phase throws"
    ;; Pre-2026-05-04 the (catch Exception e (response/failure e)) wrapped the
    ;; exception but emitted no log line, so the dogfood saw a 0ms phase fail
    ;; with no actionable detail. The diagnostic-logging change keeps the same
    ;; wrap-and-return behavior — we just guard that the exception-shaped
    ;; failure carries enough data on it for a post-mortem.
    (with-test-worktree
      (fn [worktree]
        (with-redefs [release-executor/execute-release-phase
                      (fn [_ws _ec _opts]
                        (throw (ex-info "release-executor blew up"
                                        {:reason :gh-cli-missing
                                         :tried [:gh-auth-status]})))]
          (let [ctx (create-base-context worktree)
                ctx-with-config (assoc ctx :phase-config {:phase :release})
                interceptor (phase/get-phase-interceptor {:phase :release})
                result ((:enter interceptor) ctx-with-config)
                error (get-in result [:phase :result :error])]
            (is (= "release-executor blew up" (:message error))
                "exception message must be preserved")
            (is (= :gh-cli-missing (get-in error [:data :reason]))
                "ex-data must be preserved on the wrapped failure")))))))

;------------------------------------------------------------------------------ Layer 1: Diagnostic-emission regression tests
;;
;; These tests pin the observability behavior added 2026-05-04. The
;; production fix for the dogfood release-phase silent fail is two-part:
;;   1. Ensure the release-executor receives a non-nil logger even when
;;      the workflow ctx is missing :execution/logger.
;;   2. Emit log/error entries around the failure paths in enter-release
;;      so the next dogfood run yields the actual cause instead of a
;;      generic :anomalies.phase/agent-failed.
;; A future refactor that drops either guarantee should fail here, not
;; only surface during the next dogfood post-mortem.

(defn- capturing-logger
  "Build a logger whose entries are appended to `entries-atom`. Used by
   the diagnostic-emission regression tests to assert log/error fires
   on the failure paths of enter-release."
  [entries-atom]
  (log/create-logger
    {:min-level :debug
     :output    (fn [entry] (swap! entries-atom conj entry))}))

(defn- entry-events
  "Pull the :log/event keyword off every captured log entry — what the
   tests assert against."
  [entries-atom]
  (into #{} (keep :log/event) @entries-atom))

(deftest release-passes-non-nil-logger-to-executor-when-ctx-logger-absent-test
  (testing "build-executor-context falls back to the phase-local logger when :execution/logger is absent"
    ;; Pre-fix the release-executor ran with logger=nil whenever the
    ;; workflow ctx did not include :execution/logger, which silenced
    ;; the executor's entire phase-started → fail → phase-completed log
    ;; chain (observed: 2026-05-03 dogfood, release fail in 0ms with no
    ;; release-executor lines emitted at all).
    (with-test-worktree
      (fn [worktree]
        (let [captured-context (atom nil)]
          (with-redefs [release-executor/execute-release-phase
                        (fn [_ws ec _opts]
                          (reset! captured-context ec)
                          {:success? true
                           :artifacts [{:artifact/id (random-uuid)
                                        :artifact/type :release
                                        :artifact/content {}}]
                           :metrics {:files-written 0}})]
            (let [ctx (-> (create-base-context worktree)
                          (dissoc :execution/logger))
                  ctx-with-config (assoc ctx :phase-config {:phase :release})
                  interceptor (phase/get-phase-interceptor {:phase :release})]
              ((:enter interceptor) ctx-with-config)
              (is (some? @captured-context)
                  "executor must be invoked")
              (is (some? (:logger @captured-context))
                  ":logger must be non-nil even though the workflow ctx had no :execution/logger"))))))))

(deftest release-logs-executor-failure-test
  (testing ":release/executor-failed log/error fires when execute-release-phase returns {:success? false}"
    (with-test-worktree
      (fn [worktree]
        (let [entries (atom [])
              logger  (capturing-logger entries)]
          (with-redefs [release-executor/execute-release-phase
                        (fn [_ws _ec _opts]
                          {:success? false
                           :errors [{:type :gh-auth-failed
                                     :message "gh CLI not authenticated"}]
                           :metrics {:files-written 0}})]
            (let [ctx (-> (create-base-context worktree)
                          (assoc :execution/logger logger)
                          (assoc :phase-config {:phase :release}))
                  interceptor (phase/get-phase-interceptor {:phase :release})]
              ((:enter interceptor) ctx)
              (is (contains? (entry-events entries) :release/executor-failed)
                  "log/error :release/executor-failed must fire on the :success? false branch — guards the next dogfood post-mortem"))))))))

(deftest release-logs-thrown-exception-test
  (testing ":release/executor-threw log/error fires when execute-release-phase throws"
    (with-test-worktree
      (fn [worktree]
        (let [entries (atom [])
              logger  (capturing-logger entries)]
          (with-redefs [release-executor/execute-release-phase
                        (fn [_ws _ec _opts]
                          (throw (ex-info "release-executor blew up"
                                          {:reason :gh-cli-missing})))]
            (let [ctx (-> (create-base-context worktree)
                          (assoc :execution/logger logger)
                          (assoc :phase-config {:phase :release}))
                  interceptor (phase/get-phase-interceptor {:phase :release})]
              ((:enter interceptor) ctx)
              (is (contains? (entry-events entries) :release/executor-threw)
                  "log/error :release/executor-threw must fire in the catch block — without it, the dogfood loses the actual exception"))))))))

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

;------------------------------------------------------------------------------ Layer N: Boundary-commit rehydration regression
;;
;; Stage-3 dogfood (2026-05-07): plan/implement/verify/review all green,
;; files committed on the task branch by the implement-phase boundary,
;; but release threw `:release/zero-files` because `git-dirty-files`
;; saw a clean worktree. The fix mirrors review.clj/rehydrate-from-paths
;; — read content for the `:code/file-paths` recorded on the implement
;; artifact instead of relying on the worktree's dirty status.

(defn- sh-must-succeed!
  "Run `git` with `args`; throw if exit != 0. Test-helper guard so
   we don't silently fall back through git-dirty-files when the
   boundary-commit simulation actually didn't commit."
  [worktree args]
  (let [result (apply shell/sh (concat args [:dir worktree]))]
    (when-not (zero? (:exit result))
      (throw (ex-info "git command failed inside test fixture"
                      {:args args
                       :exit (:exit result)
                       :err  (:err result)
                       :out  (:out result)})))
    result))

(defn- write-and-commit-mock-files!
  "Write the mock files into the worktree, commit them on a real
   git history, and assert the worktree is clean afterwards.

   Simulates the post-implement-boundary state where the agent's
   files have landed on the task branch and `git status --porcelain`
   reports nothing. Asserting cleanliness here means the regression
   test cannot accidentally pass via the legacy `git-dirty-files`
   path — it forces the rehydrate-from-paths code under test.

   Defeats global GPG / signing configuration (1Password, GPG agents)
   with `error: <agent> returned an error`. With commit.gpgsign=false
   locally on this repo AND --no-gpg-sign --no-verify on the commit
   itself, the fixture runs hermetically regardless of the dev
   machine's signing setup. (Same fix also applied via PR #858.)"
  [worktree]
  (write-mock-files-to-worktree! worktree)
  (sh-must-succeed! worktree ["git" "config" "user.email" "test@example.com"])
  (sh-must-succeed! worktree ["git" "config" "user.name" "Test"])
  (sh-must-succeed! worktree ["git" "config" "commit.gpgsign" "false"])
  (sh-must-succeed! worktree ["git" "config" "tag.gpgsign" "false"])
  (sh-must-succeed! worktree ["git" "add" "."])
  (sh-must-succeed! worktree ["git" "commit" "--no-gpg-sign" "--no-verify"
                              "-m" "implement-phase-boundary commit"])
  (let [{:keys [out]} (sh-must-succeed! worktree ["git" "status" "--porcelain"])]
    (when-not (str/blank? out)
      (throw (ex-info "fixture left worktree dirty after commit"
                      {:porcelain out})))))

(defn- create-clean-context-with-recorded-paths
  "Test context that mirrors the dogfood-failure shape:
   - worktree clean at HEAD (implement boundary already committed)
   - implement phase artifact carries :code/file-paths so release can
     rehydrate the content even though git-dirty-files returns empty."
  [worktree]
  (write-and-commit-mock-files! worktree)
  (let [paths   (mapv :path (:code/files mock-code-artifact))
        actions (mapv :action (:code/files mock-code-artifact))]
    {:execution/id (random-uuid)
     :execution/input {:description "Test release after boundary commit"
                       :title "Add feature"
                       :intent "testing"}
     :execution/metrics {:tokens 0 :duration-ms 0}
     :execution/phase-results
     {:implement {:result   mock-implement-result
                  :artifact {:code/file-paths   paths
                             :code/file-actions actions
                             :code/file-count   (count paths)}}}
     :worktree-path worktree}))

(deftest release-rehydrates-from-implement-artifact-paths-test
  (testing "release reads files via :code/file-paths when worktree is clean post-boundary-commit"
    ;; Stage-3 dogfood-2026-05-07 regression guard: the dogfood failed
    ;; here because git-dirty-files returns empty after the implement
    ;; boundary commits, even though the files are present on HEAD.
    (with-test-worktree
      (fn [worktree]
        (let [;; Capture the workflow state the executor stub sees so
              ;; we can assert the rehydrated content actually reached
              ;; it — checking just :phase :result :status would also
              ;; pass on a (broken) dirty-file fallback.
              captured-files (atom nil)]
          (with-redefs [release-executor/execute-release-phase
                        (fn [workflow-state _exec-context _opts]
                          (let [files (->> (:workflow/artifacts workflow-state)
                                           (mapcat (comp :code/files :artifact/content))
                                           vec)]
                            (reset! captured-files files)
                            {:success? true
                             :artifacts [{:artifact/id (random-uuid)
                                          :artifact/type :release
                                          :artifact/content {:files-written-count (count files)
                                                             :files-written-paths (mapv :path files)}}]
                             :metrics {:files-written (count files)
                                       :duration-ms 50}}))]
            (let [ctx (create-clean-context-with-recorded-paths worktree)
                  ctx-with-config (assoc ctx :phase-config {:phase :release})
                  interceptor (phase/get-phase-interceptor {:phase :release})
                  result-ctx ((:enter interceptor) ctx-with-config)
                  expected-paths (set (mapv :path (:code/files mock-code-artifact)))
                  observed-paths (some-> @captured-files (->> (mapv :path) set))]
              (is (not= :failed (get-in result-ctx [:phase :status]))
                  "release must succeed when paths are recorded — clean-worktree fallback was the bug")
              (is (some? @captured-files)
                  "executor stub must have been called — release got past zero-files")
              (is (= expected-paths observed-paths)
                  "rehydrated files must match the recorded :code/file-paths exactly")
              (is (every? #(seq (:content %)) @captured-files)
                  "every rehydrated file must carry content read from disk"))))))))

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

(deftest leave-release-detects-zero-files-by-error-type-test
  (testing "zero-files failures do not depend on localized error text"
    (let [interceptor (phase/get-phase-interceptor {:phase :release})
          ctx {:phase {:started-at (System/currentTimeMillis)
                       :result {:status :success
                                :error {:message "translated message"
                                        :data {:type :release/zero-files
                                               :phase :release}}}
                       :budget {:iterations 2}
                       :iterations 1}
               :execution/metrics {:tokens 0 :duration-ms 0}}
          result ((:leave interceptor) ctx)]
      (is (= :failed (get-in result [:phase :status]))))))

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
