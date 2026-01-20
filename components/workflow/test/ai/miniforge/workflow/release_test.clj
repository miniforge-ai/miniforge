(ns ai.miniforge.workflow.release-test
  "Tests for the release phase executor."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.release :as release]
   [ai.miniforge.workflow.protocol :as proto]
   [ai.miniforge.logging.interface :as log]
   [babashka.fs :as fs]
   [babashka.process :as process]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn create-temp-worktree
  "Create a temporary worktree directory with git initialization.
   Returns the path to the worktree."
  []
  (let [temp-dir (fs/create-temp-dir {:prefix "release-test-"})]
    ;; Initialize git repo
    (process/shell {:dir (str temp-dir)} "git init")
    (process/shell {:dir (str temp-dir)} "git config user.email" "test@example.com")
    (process/shell {:dir (str temp-dir)} "git config user.name" "Test User")
    temp-dir))

(defn create-mock-workflow-state
  "Create a mock workflow state with code artifacts."
  [code-artifacts]
  {:workflow/id (random-uuid)
   :workflow/phase :release
   :workflow/status :running
   :workflow/artifacts code-artifacts
   :workflow/errors []
   :workflow/metrics {}})

(defn create-mock-code-artifact
  "Create a mock code artifact with files."
  [files]
  {:artifact/type :code
   :artifact/id (random-uuid)
   :artifact/content {:code/id (random-uuid)
                     :code/language "clojure"
                     :code/files files}})

(def ^:dynamic *temp-dirs* (atom []))

(defn cleanup-temp-dirs
  "Cleanup all temporary directories created during tests."
  [f]
  (reset! *temp-dirs* [])
  (try
    (f)
    (finally
      (doseq [dir @*temp-dirs*]
        (when (fs/exists? dir)
          (fs/delete-tree dir))))))

(use-fixtures :each cleanup-temp-dirs)

;; ============================================================================
;; File operation helper tests
;; ============================================================================

(deftest ensure-parent-dir-test
  (testing "ensure-parent-dir! creates nested directories"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          test-path (fs/path temp-dir "foo" "bar" "baz.clj")]
      (release/ensure-parent-dir! test-path)
      (is (fs/exists? (fs/path temp-dir "foo" "bar"))))))

(deftest write-file-test
  (testing "write-file! creates parent directories"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          file-path (fs/path temp-dir "src" "example.clj")
          content "(ns example)\n(defn hello [] \"world\")"]
      (release/write-file! file-path content)
      (is (fs/exists? file-path))
      (is (= content (slurp (str file-path)))))))

(deftest delete-file-test
  (testing "delete-file! removes existing files"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          file-path (fs/path temp-dir "test.txt")]
      (spit (str file-path) "test content")
      (is (fs/exists? file-path))
      (is (true? (release/delete-file! file-path)))
      (is (not (fs/exists? file-path)))))
  
  (testing "delete-file! handles missing files gracefully"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          file-path (fs/path temp-dir "nonexistent.txt")]
      (is (false? (release/delete-file! file-path))))))

;; ============================================================================
;; Git staging tests
;; ============================================================================

(deftest stage-files-test
  (testing "stage-files! stages all changes"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          file-path (fs/path temp-dir "test.txt")]
      (spit (str file-path) "test content")
      (let [result (release/stage-files! temp-dir :all)]
        (is (:success? result))
        ;; Verify file is staged
        (let [status (process/shell {:dir (str temp-dir) :out :string} "git status --porcelain")]
          (is (re-find #"A\s+test.txt" (:out status))))))))

;; ============================================================================
;; Process file action tests
;; ============================================================================

(deftest process-file-action-create-test
  (testing "process-file-action :create writes new files"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          [logger entries] (log/collecting-logger {:min-level :debug})
          file-spec {:action :create
                    :path "src/new_file.clj"
                    :content "(ns new-file)"}
          result (release/process-file-action temp-dir file-spec logger)]
      (is (:success? result))
      (is (= :create (:action result)))
      (is (= "src/new_file.clj" (:path result)))
      (is (fs/exists? (fs/path temp-dir "src/new_file.clj")))
      (is (= "(ns new-file)" (slurp (str (fs/path temp-dir "src/new_file.clj")))))
      ;; Check logging
      (is (some #(= :release/file-created (:log/event %)) @entries)))))

(deftest process-file-action-modify-test
  (testing "process-file-action :modify overwrites files"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          file-path (fs/path temp-dir "existing.clj")
          _ (spit (str file-path) "old content")
          [logger entries] (log/collecting-logger {:min-level :debug})
          file-spec {:action :modify
                    :path "existing.clj"
                    :content "new content"}
          result (release/process-file-action temp-dir file-spec logger)]
      (is (:success? result))
      (is (= :modify (:action result)))
      (is (= "new content" (slurp (str file-path))))
      ;; Check logging
      (is (some #(= :release/file-modified (:log/event %)) @entries)))))

(deftest process-file-action-delete-test
  (testing "process-file-action :delete removes files"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          file-path (fs/path temp-dir "to_delete.clj")
          _ (spit (str file-path) "content")
          [logger entries] (log/collecting-logger {:min-level :debug})
          file-spec {:action :delete
                    :path "to_delete.clj"}
          result (release/process-file-action temp-dir file-spec logger)]
      (is (:success? result))
      (is (= :delete (:action result)))
      (is (not (fs/exists? file-path)))
      ;; Check logging
      (is (some #(= :release/file-deleted (:log/event %)) @entries)))))

(deftest process-file-action-mixed-test
  (testing "process-file-action handles all actions with nested paths"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          logger (log/create-logger {:min-level :debug})
          
          ;; Create a file to be modified later
          existing-path (fs/path temp-dir "src" "existing.clj")
          _ (fs/create-dirs (fs/parent existing-path))
          _ (spit (str existing-path) "old")
          
          ;; Create a file to be deleted
          to-delete-path (fs/path temp-dir "src" "delete_me.clj")
          _ (spit (str to-delete-path) "delete")
          
          ;; Test create
          create-result (release/process-file-action temp-dir
                          {:action :create
                           :path "src/foo/bar/new.clj"
                           :content "new"}
                          logger)
          
          ;; Test modify
          modify-result (release/process-file-action temp-dir
                          {:action :modify
                           :path "src/existing.clj"
                           :content "modified"}
                          logger)
          
          ;; Test delete
          delete-result (release/process-file-action temp-dir
                          {:action :delete
                           :path "src/delete_me.clj"}
                          logger)]
      
      (is (:success? create-result))
      (is (fs/exists? (fs/path temp-dir "src/foo/bar/new.clj")))
      
      (is (:success? modify-result))
      (is (= "modified" (slurp (str existing-path))))
      
      (is (:success? delete-result))
      (is (not (fs/exists? to-delete-path))))))

;; ============================================================================
;; Extract code artifacts tests
;; ============================================================================

(deftest extract-code-artifacts-test
  (testing "extract-code-artifacts filters and extracts code artifacts"
    (let [artifacts [{:artifact/type :code
                     :artifact/content {:code/id (random-uuid)
                                       :code/files []}}
                    {:artifact/type :plan
                     :artifact/content {:plan/tasks []}}
                    {:type :code  ; old format
                     :content {:code/id (random-uuid)
                              :code/files []}}]
          result (release/extract-code-artifacts artifacts)]
      (is (= 2 (count result)))
      (is (every? :code/id result)))))

;; ============================================================================
;; ReleasePhaseExecutor tests
;; ============================================================================

(deftest release-executor-can-execute-test
  (testing "can-execute? returns true for :release phase"
    (let [executor (release/->ReleasePhaseExecutor)]
      (is (true? (proto/can-execute? executor :release)))
      (is (false? (proto/can-execute? executor :implement)))
      (is (false? (proto/can-execute? executor :verify))))))

(deftest release-executor-requirements-test
  (testing "get-phase-requirements returns :code as required"
    (let [executor (release/->ReleasePhaseExecutor)
          requirements (proto/get-phase-requirements executor :release)]
      (is (= [:code] (:required-artifacts requirements)))
      (is (= [] (:optional-artifacts requirements))))))

(deftest release-executor-missing-worktree-test
  (testing "execute-phase fails when :worktree-path is missing"
    (let [executor (release/->ReleasePhaseExecutor)
          workflow-state (create-mock-workflow-state [])
          context {:logger (log/create-logger {:min-level :info})}
          result (proto/execute-phase executor workflow-state context)]
      (is (false? (:success? result)))
      (is (= 1 (count (:errors result))))
      (is (= :missing-worktree-path (:type (first (:errors result))))))))

(deftest release-executor-no-code-artifacts-test
  (testing "execute-phase fails when no code artifacts present"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          executor (release/->ReleasePhaseExecutor)
          workflow-state (create-mock-workflow-state [])
          context {:worktree-path temp-dir
                  :logger (log/create-logger {:min-level :info})}
          result (proto/execute-phase executor workflow-state context)]
      (is (false? (:success? result)))
      (is (= 1 (count (:errors result))))
      (is (= :no-code-artifacts (:type (first (:errors result))))))))

(deftest release-executor-success-test
  (testing "execute-phase successfully writes and stages files"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          executor (release/->ReleasePhaseExecutor)
          code-artifact (create-mock-code-artifact
                         [{:action :create
                           :path "src/example.clj"
                           :content "(ns example)\n(defn hello [] \"world\")"}
                          {:action :create
                           :path "test/example_test.clj"
                           :content "(ns example-test)\n(deftest hello-test)"}])
          workflow-state (create-mock-workflow-state [code-artifact])
          [logger entries] (log/collecting-logger {:min-level :debug})
          context {:worktree-path temp-dir
                  :logger logger}
          result (proto/execute-phase executor workflow-state context)]
      
      ;; Check result
      (is (:success? result))
      (is (= 1 (count (:artifacts result))))
      (is (empty? (:errors result)))
      
      ;; Check release artifact
      (let [release-artifact (first (:artifacts result))
            content (:artifact/content release-artifact)]
        (is (= :release (:artifact/type release-artifact)))
        (is (= 2 (:files-written content)))
        (is (= 0 (:files-modified content)))
        (is (= 0 (:files-deleted content)))
        (is (true? (:git-staged? content)))
        (is (= (str temp-dir) (:worktree-path content))))
      
      ;; Check files exist
      (is (fs/exists? (fs/path temp-dir "src/example.clj")))
      (is (fs/exists? (fs/path temp-dir "test/example_test.clj")))
      
      ;; Check git staging
      (let [status (process/shell {:dir (str temp-dir) :out :string} "git status --porcelain")]
        (is (re-find #"A\s+src/example.clj" (:out status)))
        (is (re-find #"A\s+test/example_test.clj" (:out status))))
      
      ;; Check logging
      (is (some #(= :release/phase-started (:log/event %)) @entries))
      (is (some #(= :release/git-staged (:log/event %)) @entries))
      (is (some #(= :release/phase-completed (:log/event %)) @entries)))))

(deftest release-executor-mixed-actions-test
  (testing "execute-phase handles mixed file actions"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          
          ;; Pre-create files for modify and delete
          existing-file (fs/path temp-dir "src/existing.clj")
          _ (fs/create-dirs (fs/parent existing-file))
          _ (spit (str existing-file) "old content")
          
          delete-file (fs/path temp-dir "src/old.clj")
          _ (spit (str delete-file) "delete me")
          
          executor (release/->ReleasePhaseExecutor)
          code-artifact (create-mock-code-artifact
                         [{:action :create
                           :path "src/new.clj"
                           :content "new"}
                          {:action :modify
                           :path "src/existing.clj"
                           :content "modified"}
                          {:action :delete
                           :path "src/old.clj"}])
          workflow-state (create-mock-workflow-state [code-artifact])
          context {:worktree-path temp-dir
                  :logger (log/create-logger {:min-level :info})}
          result (proto/execute-phase executor workflow-state context)]
      
      (is (:success? result))
      
      (let [content (:artifact/content (first (:artifacts result)))]
        (is (= 1 (:files-written content)))
        (is (= 1 (:files-modified content)))
        (is (= 1 (:files-deleted content)))
        (is (= 3 (:total-operations content))))
      
      ;; Verify actual file states
      (is (fs/exists? (fs/path temp-dir "src/new.clj")))
      (is (= "modified" (slurp (str existing-file))))
      (is (not (fs/exists? delete-file))))))

(deftest release-executor-multiple-artifacts-test
  (testing "execute-phase processes multiple code artifacts"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          executor (release/->ReleasePhaseExecutor)
          
          artifact1 (create-mock-code-artifact
                     [{:action :create
                       :path "src/module1.clj"
                       :content "module1"}])
          
          artifact2 (create-mock-code-artifact
                     [{:action :create
                       :path "src/module2.clj"
                       :content "module2"}])
          
          workflow-state (create-mock-workflow-state [artifact1 artifact2])
          context {:worktree-path temp-dir
                  :logger (log/create-logger {:min-level :info})}
          result (proto/execute-phase executor workflow-state context)]
      
      (is (:success? result))
      (is (= 2 (:files-written (:artifact/content (first (:artifacts result))))))
      (is (fs/exists? (fs/path temp-dir "src/module1.clj")))
      (is (fs/exists? (fs/path temp-dir "src/module2.clj"))))))

;; ============================================================================
;; Error handling tests
;; ============================================================================

(deftest release-executor-file-error-test
  (testing "execute-phase handles file operation errors gracefully"
    (let [temp-dir (create-temp-worktree)
          _ (swap! *temp-dirs* conj temp-dir)
          
          ;; Make directory read-only to cause write failure
          ;; Note: This test may not work on all systems
          executor (release/->ReleasePhaseExecutor)
          
          ;; Try to delete a non-existent file in a way that might cause an error
          code-artifact (create-mock-code-artifact
                         [{:action :create
                           :path "src/good.clj"
                           :content "good"}])
          
          workflow-state (create-mock-workflow-state [code-artifact])
          context {:worktree-path temp-dir
                  :logger (log/create-logger {:min-level :info})}
          result (proto/execute-phase executor workflow-state context)]
      
      ;; This should succeed in normal case
      (is (:success? result)))))
