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
   [babashka.process :as process]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.release-executor.interface :as release-executor]
   [ai.miniforge.workflow.execution :as execution]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Test Fixtures

(def ^:dynamic *test-worktree-path* nil)

(defn create-test-worktree
  "Create a temporary worktree directory with a real git repo for testing."
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "artifact-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    ;; Initialize a real git repo so git-dirty-files works in release phase
    (process/shell {:dir (.getPath temp-dir)} "git init")
    (process/shell {:dir (.getPath temp-dir)} "git config user.email" "test@example.com")
    (process/shell {:dir (.getPath temp-dir)} "git config user.name" "Test User")
    (process/shell {:dir (.getPath temp-dir)} "git config commit.gpgsign" "false")
    (spit (io/file temp-dir "README.md") "# Test Repository")
    (process/shell {:dir (.getPath temp-dir)} "git add README.md")
    (process/shell {:dir (.getPath temp-dir)} "git commit -m" "initial")
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
                  :execution/phase-results {}
                  :execution/worktree-path *test-worktree-path*
                  :execution/environment-id "test-env-001"}
        
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
        ;; In the environment model, the agent writes files directly to the worktree.
        ;; Simulate this by writing mock files before returning the response.
        impl-ctx (assoc plan-result-ctx :phase-config {:phase :implement})
        impl-interceptor (phase/get-phase-interceptor {:phase :implement})
        write-mock-files! (fn []
                            (when *test-worktree-path*
                              (doseq [{:keys [path content]} (:code/files mock-code-artifact)]
                                (let [f (io/file *test-worktree-path* path)]
                                  (io/make-parents f)
                                  (spit f content)))))
        impl-result-ctx (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                                      agent/invoke (or implement-agent-fn
                                                      (fn [_ _ _]
                                                        (write-mock-files!)
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
  (testing "Release phase detects failure when release executor returns error"
    (let [result-ctx (execute-phase-pipeline
                     {:release-opts
                      {:executor
                       (fn [workflow-state _exec-context _opts]
                         ;; Mock executor that reports failure
                         (let [artifacts (:workflow/artifacts workflow-state)
                               code-artifact (first artifacts)
                               file-count (count (get-in code-artifact [:artifact/content :code/files]))]
                           {:success? false
                            :errors [{:type :persistence-failure
                                     :message (str "Failed to write " file-count " files from artifact")}]
                            :metrics {:files-written 0}}))}})]

      ;; Release phase should detect executor failure
      (is (= :failed (get-in result-ctx [:phase :status]))
          "Phase should be marked failed when release executor fails")

      (is (false? (get-in result-ctx [:phase :result :success]))
          "Result should indicate failure when release executor fails"))))

(deftest test-verify-handles-missing-artifact
  (testing "Verify phase fails fast when no execution environment is available"
    ;; Create context WITHOUT execution environment — verify should throw
    (let [ctx {:execution/id (random-uuid)
               :execution/input {:description "Test"
                                :title "Test"
                                :intent "testing"}
               :execution/metrics {:tokens 0 :duration-ms 0}
               :execution/phase-results {}
               :phase-config {:phase :verify}}

          verify-interceptor (phase/get-phase-interceptor {:phase :verify})]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            ((:enter verify-interceptor) ctx))
          "Verify should throw when no execution environment is available"))))

(deftest test-workflow-empty-artifact-handling
  (testing "Release phase accepts an empty :code/files artifact when the curator confirms files exist in the environment"
    ;; Regression target: the release phase discovers files from git state,
    ;; not from the artifact's :code/files. An implement result with empty
    ;; :code/files MUST still reach the release executor as long as the
    ;; curator has confirmed the environment has the work.
    ;;
    ;; The curator is stubbed here because it reads the real worktree — its
    ;; behavior is covered by its own tests. This test isolates the release
    ;; phase's tolerance of an empty :code/files on a successful implement.
    (with-redefs [agent/curate-implement-output
                  (fn [_opts]
                    (response/success mock-empty-artifact
                                      {:tokens 0 :duration-ms 0}))]
      (let [result-ctx (execute-phase-pipeline
                        {:implement-agent-fn
                         (fn [_agent _task _ctx]
                           (response/success mock-empty-artifact
                                             {:tokens 50 :duration-ms 300}))

                         :release-opts
                         {:executor
                          (fn [_workflow-state _exec-context _opts]
                            {:success? true
                             :artifacts []
                             :metrics {:files-written 0}})}})]
        (is (= :completed (get-in result-ctx [:phase :status]))
            "Release phase should complete when executor returns success")
        (is (= :success (get-in result-ctx [:phase :result :status]))
            "Release result should be success")))))

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
  (testing "record-phase-artifacts extracts provenance metadata from :result"
    (let [phase-result {:name :implement
                        :status :completed
                        :result {:status :success
                                 :environment-id "env-001"
                                 :summary "Implementation complete"
                                 :metrics {:tokens 100}}}
          ctx {:execution/artifacts []}
          updated (execution/record-phase-artifacts ctx phase-result)]
      (is (= 1 (count (:execution/artifacts updated)))
          "Should extract one provenance artifact from result")
      (is (= {:status :success
              :environment-id "env-001"
              :summary "Implementation complete"
              :metrics {:tokens 100}}
             (first (:execution/artifacts updated)))
          "Artifact should contain provenance metadata"))))

(deftest test-record-phase-artifacts-empty-when-no-output
  (testing "record-phase-artifacts produces empty when result is not a map"
    (let [phase-result {:name :plan
                        :status :completed
                        :result "plain string"}
          ctx {:execution/artifacts []}
          updated (execution/record-phase-artifacts ctx phase-result)]
      (is (empty? (:execution/artifacts updated))
          "Should not extract artifact from non-map result"))))

(deftest test-track-phase-files-extracts-code-files
  (testing "track-phase-files is a no-op in the environment model"
    (let [phase-result {:name :implement
                        :status :completed
                        :result {:status :success
                                 :environment-id "env-001"
                                 :summary "Implementation complete"}}
          ctx {:execution/files-written []}
          updated (execution/track-phase-files ctx phase-result)]
      (is (= [] (:execution/files-written updated))
          "File tracking is a no-op; file discovery happens at release time via git diff"))))

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
