(ns ai.miniforge.workflow.artifact-persistence-test
  "Tests for artifact persistence flow.

  Unit tests for execution namespace artifact/file extraction, plus
  integration tests that validate files are actually written to disk.

  These tests validate:
  - execution/record-phase-artifacts extracts from nested [:result :output]
  - execution/track-phase-files extracts :code/files paths from output
  - runner/extract-output returns non-empty artifacts
  - Files are written to filesystem
  - Zero-file writes are detected and fail
  - Empty artifacts cause failures
  - Success flags are validated

  This test suite should have caught the bug discovered during dogfooding
  where workflows completed with :files-written 0."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.release-executor.interface :as release-executor]
   [ai.miniforge.workflow.execution :as execution]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Test Fixtures

(def ^:dynamic *test-worktree-path* nil)

(defn create-test-worktree
  "Create a temporary worktree directory for testing."
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "artifact-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    ;; Initialize minimal git repo
    (let [git-dir (io/file temp-dir ".git")]
      (.mkdirs git-dir)
      (spit (io/file git-dir "config") "[core]\n\trepositoryformatversion = 0")
      (spit (io/file temp-dir "README.md") "# Test Repository"))
    (.getPath temp-dir)))

(defn cleanup-test-worktree
  "Delete test worktree directory and all contents."
  [dir-path]
  (when dir-path
    (try
      (fs/delete-tree dir-path)
      (catch Exception _e
        ;; Ignore cleanup errors
        nil))))

(defn worktree-fixture
  "Test fixture that creates and cleans up a test worktree."
  [f]
  (let [worktree (create-test-worktree)]
    (binding [*test-worktree-path* worktree]
      (try
        (f)
        (finally
          (cleanup-test-worktree worktree))))))

(use-fixtures :each worktree-fixture)

;------------------------------------------------------------------------------ Mock Data

(def mock-code-artifact
  "Mock code artifact with multiple files."
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test (:require [clojure.test :refer :all]))\n(deftest test-new-feature (is true))"
                 :action :create}]
   :code/language "clojure"
   :code/summary "Implemented new feature"})

(def mock-empty-artifact
  "Mock artifact with no files."
  {:code/id (random-uuid)
   :code/files []
   :code/language "clojure"
   :code/summary "Empty implementation"})

(def mock-plan-result
  {:plan/id (random-uuid)
   :plan/tasks [{:task "Implement feature"
                 :file "src/feature.clj"}]})

;------------------------------------------------------------------------------ Test Helpers

(defn file-exists-in-worktree?
  "Check if a file exists in the test worktree."
  [relative-path]
  (when *test-worktree-path*
    (.exists (io/file *test-worktree-path* relative-path))))

(defn read-worktree-file
  "Read content of a file from the test worktree."
  [relative-path]
  (when *test-worktree-path*
    (let [file (io/file *test-worktree-path* relative-path)]
      (when (.exists file)
        (slurp file)))))

(defn count-files-in-worktree
  "Count files in test worktree (excluding .git)."
  []
  (when *test-worktree-path*
    (->> (file-seq (io/file *test-worktree-path*))
         (filter #(.isFile %))
         (remove #(str/includes? (.getPath %) ".git"))
         count)))

(defn execute-phase-pipeline
  "Execute a phase pipeline: plan -> implement -> verify -> release."
  [opts]
  (let [{:keys [implement-agent-fn verify-agent-fn release-opts]} opts
        
        ;; Execute plan phase
        plan-ctx {:execution/id (random-uuid)
                  :execution/input {:description "Test feature"
                                   :title "Add feature"
                                   :intent "testing"}
                  :execution/metrics {:tokens 0 :duration-ms 0}
                  :execution/phase-results {}}
        
        plan-interceptor (phase/get-phase-interceptor {:phase :plan})
        plan-result-ctx (-> plan-ctx
                           (assoc :phase-config {:phase :plan})
                           ((:enter plan-interceptor))
                           ((:leave plan-interceptor)))
        
        ;; Store plan result
        plan-result-ctx (assoc-in plan-result-ctx 
                                 [:execution/phase-results :plan]
                                 (:phase plan-result-ctx))
        
        ;; Execute implement phase
        impl-ctx (assoc plan-result-ctx :phase-config {:phase :implement})
        impl-interceptor (phase/get-phase-interceptor {:phase :implement})
        impl-result-ctx (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                                      agent/invoke (or implement-agent-fn
                                                      (fn [_ _ _]
                                                        (response/success mock-code-artifact
                                                                        {:tokens 100 :duration-ms 500})))]
                         (-> impl-ctx
                             ((:enter impl-interceptor))
                             ((:leave impl-interceptor))))
        
        ;; Store implement result
        impl-result-ctx (assoc-in impl-result-ctx
                                 [:execution/phase-results :implement]
                                 (:phase impl-result-ctx))
        
        ;; Execute verify phase (optional)
        verify-result-ctx (when verify-agent-fn
                           (let [verify-ctx (assoc impl-result-ctx :phase-config {:phase :verify})
                                 verify-interceptor (phase/get-phase-interceptor {:phase :verify})]
                             (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                                          agent/invoke verify-agent-fn]
                               (-> verify-ctx
                                   ((:enter verify-interceptor))
                                   ((:leave verify-interceptor))))))
        
        ;; Execute release phase
        release-ctx (or verify-result-ctx impl-result-ctx)
        release-ctx (assoc release-ctx 
                          :phase-config {:phase :release}
                          :worktree-path *test-worktree-path*)
        release-interceptor (phase/get-phase-interceptor {:phase :release})]
    
    (if release-opts
      ;; Custom release executor for testing
      (with-redefs [release-executor/execute-release-phase (:executor release-opts)]
        (-> release-ctx
            ((:enter release-interceptor))
            ((:leave release-interceptor))))
      ;; Normal release execution
      (-> release-ctx
          ((:enter release-interceptor))
          ((:leave release-interceptor))))))

;------------------------------------------------------------------------------ Integration Tests

(deftest test-full-workflow-writes-files
  (testing "Complete workflow writes files to filesystem"
    (with-redefs [agent/create-planner (fn [_] {:type :mock-planner})
                  agent/invoke (fn [agent _ _]
                                (if (= :mock-planner (:type agent))
                                  (response/success mock-plan-result {:tokens 50 :duration-ms 200})
                                  (response/success {:result :ok} {:tokens 0 :duration-ms 0})))]
      
      (let [initial-file-count (count-files-in-worktree)
            
            ;; Execute full pipeline with direct file writing (no git staging)
            result-ctx (execute-phase-pipeline
                       {:release-opts
                        {:executor
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
                                         :artifact/content {:files-written (count files)
                                                          :branch "test-branch"
                                                          :commit-sha "abc123"}}]
                              :metrics {:files-written (count files)}}))}})
            
            final-file-count (count-files-in-worktree)]
        
        ;; Verify files were written
        (is (> final-file-count initial-file-count)
            "Files should have been written to disk")
        
        (is (file-exists-in-worktree? "src/feature.clj")
            "Source file should exist on filesystem")
        
        (is (file-exists-in-worktree? "test/feature_test.clj")
            "Test file should exist on filesystem")
        
        ;; Verify file contents
        (let [feature-content (read-worktree-file "src/feature.clj")]
          (is (some? feature-content)
              "Should be able to read written file")
          (is (str/includes? feature-content "new-feature")
              "File should contain expected content"))
        
        ;; Verify phase completed successfully
        (is (= :completed (get-in result-ctx [:phase :status]))
            "Release phase should complete")
        
        (is (= :success (get-in result-ctx [:phase :result :status]))
            "Release result should be success")))))

(deftest test-release-fails-with-zero-files-written
  (testing "Release phase fails when zero files are written despite artifact having files"
    (let [result-ctx (execute-phase-pipeline
                     {:release-opts
                      {:executor
                       (fn [workflow-state _exec-context _opts]
                         ;; Mock executor that claims success but wrote 0 files
                         (let [artifacts (:workflow/artifacts workflow-state)
                               code-artifact (first artifacts)
                               file-count (count (get-in code-artifact [:artifact/content :code/files]))]
                           (if (pos? file-count)
                             ;; Artifact has files, but we're simulating write failure
                             {:success? false
                              :errors [{:type :persistence-failure
                                       :message (str "Failed to write " file-count " files from artifact")}]
                              :metrics {:files-written 0}}
                             {:success? true
                              :artifacts []
                              :metrics {:files-written 0}})))}})]
      
      ;; Verify release phase detected the failure
      (is (= :failed (get-in result-ctx [:phase :status]))
          "Phase should be marked failed when release persistence fails")
      
      (is (false? (get-in result-ctx [:phase :result :success]))
          "Result should indicate failure when no files written")
      
      ;; Verify no files were actually written
      (is (not (file-exists-in-worktree? "src/feature.clj"))
          "No files should exist when write fails"))))

(deftest test-verify-handles-missing-artifact
  (testing "Verify phase fails fast when no code artifact is available"
    ;; Create context WITHOUT implement phase result
    (let [ctx {:execution/id (random-uuid)
               :execution/input {:description "Test"
                                :title "Test"
                                :intent "testing"}
               :execution/metrics {:tokens 0 :duration-ms 0}
               :execution/phase-results {}
               :phase-config {:phase :verify}}

          verify-interceptor (phase/get-phase-interceptor {:phase :verify})]

      (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                    agent/invoke (fn [_agent _task _ctx]
                                  (response/success {:result :ok} {:tokens 0 :duration-ms 0}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Verify phase received no code artifact"
                              ((:enter verify-interceptor) ctx))
            "Verify should throw when no code artifact is available")))))

(deftest test-workflow-empty-artifact-handling
  (testing "Workflow fails fast when code artifact has zero files"
    ;; With fail-fast validation, release phase throws on empty :code/files
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Release phase received code artifact with zero files"
                          (execute-phase-pipeline
                           {:implement-agent-fn
                            (fn [_agent _task _ctx]
                              ;; Implementer returns empty artifact
                              (response/success mock-empty-artifact {:tokens 50 :duration-ms 300}))

                            :release-opts
                            {:executor
                             (fn [_workflow-state _exec-context _opts]
                               {:success? true
                                :artifacts []
                                :metrics {:files-written 0}})}}))
        "Release should throw when code artifact has empty files")))

(deftest test-artifact-content-verification
  (testing "Files written to disk have correct content"
    (let [_result-ctx (execute-phase-pipeline
                      {:release-opts
                       {:executor
                        (fn [workflow-state exec-context _opts]
                          ;; Direct file writing (no git staging)
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
                             :metrics {:files-written (count files)}}))}})
          feature-content (read-worktree-file "src/feature.clj")
          test-content (read-worktree-file "test/feature_test.clj")]

      (is (= "(ns feature)\n(defn new-feature [] :implemented)"
             feature-content)
          "Source file content should match artifact")

      (is (str/includes? test-content "test-new-feature")
          "Test file should contain test function")

      (is (str/includes? test-content "clojure.test")
          "Test file should require clojure.test"))))

;------------------------------------------------------------------------------ Unit Tests: Artifact Extraction


(deftest test-record-phase-artifacts-extracts-nested-output
  (testing "record-phase-artifacts extracts artifact from [:result :output]"
    (let [code-output {:code/id (random-uuid)
                       :code/files [{:path "src/foo.clj" :content "(ns foo)"}]
                       :code/language "clojure"}
          phase-result {:name :implement
                        :status :completed
                        :result {:status :ok
                                 :output code-output}}
          ctx {:execution/artifacts []}
          updated (execution/record-phase-artifacts ctx phase-result)]
      (is (= 1 (count (:execution/artifacts updated)))
          "Should extract one artifact from nested output")
      (is (= code-output (first (:execution/artifacts updated)))
          "Artifact should be the output map"))))

(deftest test-record-phase-artifacts-empty-when-no-output
  (testing "record-phase-artifacts produces empty when no output"
    (let [phase-result {:name :plan
                        :status :completed
                        :result {:status :ok :output "plain string"}}
          ctx {:execution/artifacts []}
          updated (execution/record-phase-artifacts ctx phase-result)]
      (is (empty? (:execution/artifacts updated))
          "Should not extract artifact from non-map output"))))

(deftest test-track-phase-files-extracts-code-files
  (testing "track-phase-files extracts :code/files paths from output"
    (let [phase-result {:name :implement
                        :status :completed
                        :result {:status :ok
                                 :output {:code/files [{:path "src/a.clj" :content "a"}
                                                       {:path "src/b.clj" :content "b"}]}}}
          ctx {:execution/files-written []}
          updated (execution/track-phase-files ctx phase-result)]
      (is (= ["src/a.clj" "src/b.clj"] (:execution/files-written updated))
          "Should extract file paths from :code/files"))))

(deftest test-extract-output-includes-artifacts
  (testing "extract-output returns non-empty artifacts after fix"
    (let [code-output {:code/id (random-uuid)
                       :code/files [{:path "src/foo.clj" :content "(ns foo)"}]}
          ctx {:execution/artifacts [code-output]
               :execution/phase-results {:implement {:status :completed
                                                     :result {:status :ok :output code-output}}}
               :execution/current-phase :implement
               :execution/status :completed}
          result (runner/extract-output ctx)]
      (is (= 1 (count (get-in result [:execution/output :artifacts])))
          "Output should contain the artifact")
      (is (= :completed (get-in result [:execution/output :status]))
          "Output status should be :completed"))))
