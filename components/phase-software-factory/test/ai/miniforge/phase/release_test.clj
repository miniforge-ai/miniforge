(ns ai.miniforge.phase.release-test
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
   [babashka.fs :as fs]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.release :as release]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Test Fixtures

(def ^:dynamic *test-worktree* nil)

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

(defn worktree-fixture
  [f]
  (let [worktree (create-temp-worktree)]
    (binding [*test-worktree* worktree]
      (try
        (f)
        (finally
          (cleanup-temp-worktree worktree))))))

(use-fixtures :each worktree-fixture)

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
  []
  (doseq [{:keys [path content]} (:code/files mock-code-artifact)]
    (let [file (io/file *test-worktree* path)]
      (io/make-parents file)
      (spit file content))))

(defn create-base-context
  "Create a test context with mock files already written to the test worktree.
   In the new environment model, code changes are in the worktree (not phase
   results). Most tests use this context."
  []
  ;; Write mock files to the worktree, simulating what the implement phase does
  (write-mock-files-to-worktree!)
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result mock-implement-result}}
   :worktree-path *test-worktree*})

(defn create-empty-context
  "Create a test context with NO files written to the worktree.
   Used to test zero-files detection (no git dirty files in environment)."
  []
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result mock-implement-result}}
   :worktree-path *test-worktree*})

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
    (let [defaults (registry/phase-defaults :release)]
      (is (some? defaults))
      (is (= :releaser (:agent defaults)))
      (is (= [:release-ready] (:gates defaults))))))

;------------------------------------------------------------------------------ File Writing Tests

(deftest release-writes-files-to-temp-directory-test
  (testing "release phase writes files to temporary worktree"
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
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]

        ;; Verify phase completed successfully
        (is (= :success (get-in result [:phase :result :status]))
            "Release phase should succeed")

        ;; Verify files exist
        (is (.exists (io/file *test-worktree* "src/feature.clj"))
            "Source file should exist on disk")

        (is (.exists (io/file *test-worktree* "test/feature_test.clj"))
            "Test file should exist on disk")))))

(deftest release-returns-correct-files-written-count-test
  (testing "release phase returns correct :files-written count"
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
                                  :artifact/content {:files-written (count files)}}]
                       :metrics {:files-written (count files)}}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)
            files-written (get-in result [:phase :result :output :release/metrics :files-written])]
        
        (is (= 2 files-written)
            "Should report 2 files written (src + test)")))))

(deftest release-fails-when-write-fails-test
  (testing "release phase fails when file write operation fails"
    (with-redefs [release-executor/execute-release-phase
                  (fn [_workflow-state _exec-context _opts]
                    ;; Simulate write failure
                    {:success? false
                     :errors [{:type :file-write-failed
                              :message "Permission denied writing to src/feature.clj"
                              :file "src/feature.clj"}]
                     :metrics {:files-written 0}})]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]
        
        (is (false? (get-in result [:phase :result :success]))
            "Release should fail when write fails")

        (is (some? (get-in result [:phase :result :error]))
            "Error should be present in result")))))

(deftest release-verifies-files-exist-after-writing-test
  (testing "release phase verifies files exist on disk after writing"
    (let [verification-performed (atom false)]
      (with-redefs [release-executor/execute-release-phase
                    (fn [workflow-state exec-context _opts]
                      ;; Write files directly to temp worktree (skip git staging)
                      (let [worktree (:worktree-path exec-context)
                            code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                            files (mapcat :code/files code-artifacts)]
                        (doseq [{:keys [path content action]} files]
                          (when (#{:create :modify} action)
                            (let [file (io/file worktree path)]
                              (io/make-parents file)
                              (spit file content))))
                        ;; Verify files exist after write
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
        (let [ctx (create-base-context)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (registry/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)]

          (is @verification-performed
              "File verification should be performed")

          (is (= :success (get-in result [:phase :result :status]))
              "Release should succeed when verification passes"))))))

(deftest release-handles-zero-files-artifact-test
  (testing "release phase fails fast when no files changed in the environment"
    ;; With the new environment model, zero-file detection is via the git
    ;; working tree rather than :code/files in phase results.
    ;; create-empty-context does NOT write any files to the worktree.
    (let [ctx (create-empty-context)
          ctx-with-config (assoc ctx :phase-config {:phase :release})
          interceptor (registry/get-phase-interceptor {:phase :release})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Release phase received code artifact with zero files"
                            ((:enter interceptor) ctx-with-config))
          "Release should fail fast when environment has no changed files"))))

(deftest release-includes-pr-info-test
  (testing "release phase includes PR info in result"
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
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]
        
        (is (some? (get-in result [:workflow/pr-info]))
            "PR info should be available at top level")
        
        (let [pr-info (get-in result [:workflow/pr-info])]
          (is (= 42 (:pr-number pr-info))
              "PR number should be captured")
          (is (= "https://github.com/org/repo/pull/42" (:pr-url pr-info))
              "PR URL should be captured")
          (is (= "feature/test-123" (:branch pr-info))
              "Branch name should be captured"))))))

(deftest release-propagates-streaming-callback-test
  (testing "release phase passes event-stream-backed on-chunk callback to the executor"
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
        (let [ctx (assoc (create-base-context) :event-stream stream)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (registry/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)
              chunk-events (es/get-events stream {:event-type :agent/chunk})]
          (is (= :success (get-in result [:phase :result :status])))
          (is (= 1 (count chunk-events)))
          (is (= "release chunk" (:chunk/delta (first chunk-events))))
          (is (= :release (:agent/id (first chunk-events)))))))))

;------------------------------------------------------------------------------ Layer 2: Interceptor Leave Tests

(deftest leave-release-records-metrics-test
  (testing "leave-release records duration and completion"
    (with-redefs [release-executor/execute-release-phase
                  (fn [_workflow-state _exec-context _opts]
                    {:success? true
                     :artifacts [{:artifact/id (random-uuid)
                                :artifact/type :release
                                :artifact/content {:files-written 2}}]
                     :metrics {:files-written 2 :tokens 300 :duration-ms 800}})]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        
        (is (= :completed (get-in final-result [:phase :status]))
            "Phase status should be completed")
        
        (is (number? (get-in final-result [:phase :duration-ms]))
            "Duration should be recorded")
        
        (is (= 300 (get-in final-result [:phase :metrics :tokens]))
            "Token metrics should be recorded")
        
        (is (= :release (first (get-in final-result [:execution :phases-completed])))
            "Release should be added to phases-completed")))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase.release-test)
  :leave-this-here)
