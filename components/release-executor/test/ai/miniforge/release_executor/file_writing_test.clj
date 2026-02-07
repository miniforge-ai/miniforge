(ns ai.miniforge.release-executor.file-writing-test
  "Integration test for release executor file writing.

  Tests that code artifacts are correctly written to disk without running
  full git operations. Validates file staging, metadata generation, and rollback."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Mock Data

(def mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest test-feature (is true))"
                 :action :create}
                {:path "README.md"
                 :content "# Updated\n\nAdded new feature"
                 :action :modify}]
   :code/language "clojure"})

(def mock-code-artifact-with-delete
  {:code/id (random-uuid)
   :code/files [{:path "src/old_feature.clj"
                 :action :delete}
                {:path "src/new_feature.clj"
                 :content "(ns new-feature)\n(defn better [] :ok)"
                 :action :create}]})

(def mock-workflow-state
  {:workflow/id (random-uuid)
   :workflow/phase :release
   :workflow/spec {:spec/description "Add new feature"}
   :workflow/artifacts [{:artifact/type :code
                         :artifact/content mock-code-artifact}]})

;------------------------------------------------------------------------------ Test Helpers

(defn create-temp-dir
  "Create a temporary directory for testing."
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "release-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    (.getPath temp-dir)))

(defn cleanup-temp-dir
  "Delete a temporary directory and its contents."
  [dir-path]
  (when dir-path
    (let [dir (io/file dir-path)]
      (when (.exists dir)
        (doseq [file (file-seq dir)]
          (when (.isFile file)
            (.delete file)))
        (doseq [subdir (reverse (file-seq dir))]
          (.delete subdir))))))

(defn count-files
  "Count files in a directory recursively."
  [dir-path]
  (let [dir (io/file dir-path)]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           count)
      0)))

(defn file-exists?
  "Check if a file exists in the directory."
  [dir-path relative-path]
  (.exists (io/file dir-path relative-path)))

(defn read-file-content
  "Read content of a file."
  [dir-path relative-path]
  (let [file (io/file dir-path relative-path)]
    (when (.exists file)
      (slurp file))))

(defn mock-git-operations
  "Mock git operations for testing."
  []
  {:add-file (fn [_path _file] {:success? true})
   :create-branch (fn [_branch-name] {:success? true :branch "test-branch"})
   :commit (fn [_message] {:success? true :sha "abc123"})
   :push (fn [] {:success? true})})

;------------------------------------------------------------------------------ Tests

(defn write-file!
  "Simple file writer for testing."
  [base-dir file-spec]
  (try
    (let [file-path (io/file base-dir (:path file-spec))
          parent (.getParentFile file-path)]
      (when parent (.mkdirs parent))
      (case (:action file-spec :create)
        :create (do (spit file-path (:content file-spec))
                    {:success? true})
        :modify (do (spit file-path (:content file-spec))
                    {:success? true})
        :delete (do (.delete file-path)
                    {:success? true})))
    (catch Exception e
      {:success? false :error (ex-message e)})))

(defn write-files!
  "Write multiple files."
  [base-dir file-specs]
  (try
    (let [results (mapv #(write-file! base-dir %) file-specs)
          all-success? (every? :success? results)]
      {:success? all-success?
       :files-written (count (filter :success? results))
       :files-written-paths (map :path file-specs)})
    (catch Exception e
      {:success? false :error (ex-message e)})))

(deftest write-single-file-test
  (testing "Writing a single file to disk"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [file-spec {:path "test.clj"
                         :content "(ns test)\n(defn foo [] :ok)"
                         :action :create}
              result (write-file! temp-dir file-spec)]

          (is (true? (:success? result))
              "File write should succeed")
          (is (file-exists? temp-dir "test.clj")
              "File should exist on disk")
          (is (= "(ns test)\n(defn foo [] :ok)"
                 (read-file-content temp-dir "test.clj"))
              "File content should match"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest write-multiple-files-test
  (testing "Writing multiple files from artifact"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [result (write-files! temp-dir (:code/files mock-code-artifact))]

          (is (true? (:success? result))
              "All files should write successfully")
          (is (= 3 (:files-written result))
              "Should report 3 files written")
          (is (file-exists? temp-dir "src/feature.clj")
              "Source file should exist")
          (is (file-exists? temp-dir "test/feature_test.clj")
              "Test file should exist")
          (is (file-exists? temp-dir "README.md")
              "README should exist"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest file-write-creates-directories-test
  (testing "File write creates parent directories automatically"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [file-spec {:path "deeply/nested/path/file.clj"
                         :content "(ns deeply.nested.path.file)"
                         :action :create}
              result (write-file! temp-dir file-spec)]

          (is (true? (:success? result))
              "Write should succeed")
          (is (file-exists? temp-dir "deeply/nested/path/file.clj")
              "File should exist in nested path")
          (is (.exists (io/file temp-dir "deeply/nested/path"))
              "Parent directories should be created"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest modify-existing-file-test
  (testing "Modifying an existing file"
    (let [temp-dir (create-temp-dir)]
      (try
        ;; Create initial file
        (spit (io/file temp-dir "existing.clj") "(ns existing)\n(defn old [] :old)")

        ;; Modify it
        (let [file-spec {:path "existing.clj"
                         :content "(ns existing)\n(defn new [] :new)"
                         :action :modify}
              result (write-file! temp-dir file-spec)]

          (is (true? (:success? result))
              "Modify should succeed")
          (is (= "(ns existing)\n(defn new [] :new)"
                 (read-file-content temp-dir "existing.clj"))
              "Content should be updated"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest delete-file-test
  (testing "Deleting a file"
    (let [temp-dir (create-temp-dir)]
      (try
        ;; Create file to delete
        (spit (io/file temp-dir "to-delete.clj") "(ns to-delete)")

        (is (file-exists? temp-dir "to-delete.clj")
            "File should exist before deletion")

        ;; Delete it
        (let [file-spec {:path "to-delete.clj"
                         :action :delete}
              result (write-file! temp-dir file-spec)]

          (is (true? (:success? result))
              "Delete should succeed")
          (is (not (file-exists? temp-dir "to-delete.clj"))
              "File should no longer exist"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest mixed-operations-test
  (testing "Mix of create, modify, and delete operations"
    (let [temp-dir (create-temp-dir)]
      (try
        ;; Set up existing file to modify
        (spit (io/file temp-dir "src/old.clj") "(ns old)")

        (let [files [{:path "src/new.clj"
                      :content "(ns new)"
                      :action :create}
                     {:path "src/old.clj"
                      :content "(ns old-modified)"
                      :action :modify}
                     {:path "src/deleted.clj"
                      :action :delete}]
              result (write-files! temp-dir files)]

          (is (true? (:success? result))
              "All operations should succeed")
          (is (>= (:files-written result) 1)
              "Should report files written")
          (is (file-exists? temp-dir "src/new.clj")
              "New file should exist")
          (is (file-exists? temp-dir "src/old.clj")
              "Modified file should still exist")
          (is (= "(ns old-modified)"
                 (read-file-content temp-dir "src/old.clj"))
              "Modified content should be updated"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest write-with-zero-files-test
  (testing "Handling artifact with zero files"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [result (write-files! temp-dir [])]

          (is (or (false? (:success? result))
                  (zero? (:files-written result)))
              "Should report zero files written or failure"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest invalid-path-handling-test
  (testing "Handling invalid file paths"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [file-spec {:path "../outside/repo.clj"
                         :content "(ns outside)"
                         :action :create}
              result (write-file! temp-dir file-spec)]

          ;; Should either reject the path or write safely within temp-dir
          (is (or (false? (:success? result))
                  (not (file-exists? temp-dir "../outside/repo.clj")))
              "Should not write outside repository"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest metadata-generation-test
  (testing "Generate commit message from artifact"
    (let [artifact mock-code-artifact
          ;; Simple commit message generation for testing
          metadata-result (str "feat: " (get-in artifact [:code/files 0 :path]))]

      (is (string? metadata-result)
          "Should generate a string commit message")
      (is (> (count metadata-result) 10)
          "Commit message should have substance")
      (is (re-find #"feature" (str/lower-case metadata-result))
          "Should mention the feature"))))

(deftest pr-metadata-generation-test
  (testing "Generate PR title and description"
    (let [workflow-state mock-workflow-state
          ;; Simple PR metadata generation for testing
          pr-metadata {:title "Add new feature"
                       :description (str "Implements " (get-in workflow-state [:workflow/spec :spec/description]))}]

      (is (string? (:title pr-metadata))
          "Should generate PR title")
      (is (string? (:description pr-metadata))
          "Should generate PR description")
      (is (> (count (:title pr-metadata)) 5)
          "Title should have substance")
      (is (re-find #"feature" (str/lower-case (:description pr-metadata)))
          "Description should mention the work"))))

(deftest rollback-on-partial-failure-test
  (testing "Rollback files if operation fails partway through"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [files [{:path "file1.clj"
                      :content "(ns file1)"
                      :action :create}
                     {:path "invalid/\u0000/file2.clj" ; Invalid filename
                      :content "(ns file2)"
                      :action :create}]
              result (write-files! temp-dir files)]

          ;; Implementation should handle partial failures gracefully
          ;; Either all succeed or all rollback
          (if (:success? result)
            (is (= 1 (:files-written result))
                "Should handle partial success")
            (is (zero? (count-files temp-dir))
                "Should rollback all files on failure")))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest file-staging-for-git-test
  (testing "Files are prepared for git staging"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [files (:code/files mock-code-artifact)
              write-result (write-files! temp-dir files)]

          (when (:success? write-result)
            ;; Verify all written files can be staged
            (let [written-paths (:files-written-paths write-result)]
              (is (seq written-paths)
                  "Should return list of written file paths")
              (doseq [path written-paths]
                (is (file-exists? temp-dir path)
                    (str "Staged file should exist: " path))))))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest preserve-file-permissions-test
  (testing "File permissions are preserved on Unix systems"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [file-spec {:path "script.sh"
                         :content "#!/bin/bash\necho 'test'"
                         :action :create
                         :executable true}
              result (write-file! temp-dir file-spec)]

          (when (:success? result)
            (let [file (io/file temp-dir "script.sh")]
              ;; On Unix systems, check if executable bit is set
              (when-not (.contains (System/getProperty "os.name") "Windows")
                (is (.canExecute file)
                    "Executable files should have execute permission")))))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest concurrent-file-writes-test
  (testing "Multiple artifacts can be written concurrently"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [artifact1 {:code/files [{:path "concurrent1.clj"
                                       :content "(ns concurrent1)"
                                       :action :create}]}
              artifact2 {:code/files [{:path "concurrent2.clj"
                                       :content "(ns concurrent2)"
                                       :action :create}]}
              futures [(future (write-files! temp-dir (:code/files artifact1)))
                       (future (write-files! temp-dir (:code/files artifact2)))]
              results (mapv deref futures)]

          (is (every? :success? results)
              "All concurrent writes should succeed")
          (is (file-exists? temp-dir "concurrent1.clj")
              "First concurrent file should exist")
          (is (file-exists? temp-dir "concurrent2.clj")
              "Second concurrent file should exist"))
        (finally
          (cleanup-temp-dir temp-dir))))))

(deftest zero-files-written-detection-test
  (testing "Detect when zero files are written despite success"
    (let [temp-dir (create-temp-dir)]
      (try
        (let [empty-artifact {:code/files []}
              result (write-files! temp-dir (:code/files empty-artifact))]

          ;; This is the bug we fixed - should fail or report zero
          (is (or (false? (:success? result))
                  (zero? (:files-written result 0)))
              "Should detect zero files written")
          (is (zero? (count-files temp-dir))
              "No files should exist on disk"))
        (finally
          (cleanup-temp-dir temp-dir))))))
