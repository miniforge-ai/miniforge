(ns ai.miniforge.release-executor.artifact-validation-integration-test
  "Project-level integration tests for artifact validation in the release executor.

  Validates the fail-fast behavior when artifacts are nil, empty, or have no files.
  Exercises the release-executor component through its public interface."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [ai.miniforge.release-executor.interface :as release-executor]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (fs/create-temp-dir {:prefix "artifact-val-test-"})]
    (process/shell {:dir (str dir)} "git init")
    (process/shell {:dir (str dir)} "git config user.email" "test@example.com")
    (process/shell {:dir (str dir)} "git config user.name" "Test User")
    ;; Create an initial commit so branch operations work
    (spit (str (fs/path dir "README.md")) "# Test")
    (process/shell {:dir (str dir)} "git add README.md")
    (process/shell {:dir (str dir)} "git commit -m" "initial")
    (binding [*temp-dir* dir]
      (try (f)
           (finally (fs/delete-tree dir))))))

(use-fixtures :each temp-dir-fixture)

;------------------------------------------------------------------------------ Helpers

(defn make-workflow-state [artifacts]
  {:workflow/id (random-uuid)
   :workflow/phase :release
   :workflow/spec {:spec/description "test"}
   :workflow/artifacts artifacts})

(defn make-context []
  {:worktree-path *temp-dir*
   :create-pr? false
   :logger (log/create-logger {:min-level :warn})})

;------------------------------------------------------------------------------ Tests

(deftest nil-artifacts-rejected-test
  (testing "release executor rejects workflow with nil artifact content"
    (let [state (make-workflow-state [{:artifact/type :code
                                       :artifact/content nil}])
          result (release-executor/execute-release-phase state (make-context) {})]
      (is (false? (:success? result))
          "Should fail when artifact content is nil")
      (is (some #(= :no-code-artifacts (:type %)) (:errors result))
          "Error should indicate no code artifacts"))))

(deftest empty-files-rejected-test
  (testing "release executor rejects artifacts with zero files"
    (let [state (make-workflow-state [{:artifact/type :code
                                       :artifact/content {:code/id (random-uuid)
                                                          :code/files []}}])
          result (release-executor/execute-release-phase state (make-context) {})]
      (is (false? (:success? result))
          "Should fail when artifact has zero files")
      (is (some #(= :no-code-files (:type %)) (:errors result))
          "Error should indicate no code files"))))

(deftest valid-artifacts-pass-validation-test
  (testing "release executor passes validation for valid artifacts with files"
    (let [state (make-workflow-state
                 [{:artifact/type :code
                   :artifact/content {:code/id (random-uuid)
                                      :code/files [{:path "src/hello.clj"
                                                    :content "(ns hello)\n(defn greet [] :hi)"
                                                    :action :create}]}}])
          result (release-executor/execute-release-phase state (make-context) {})]
      ;; Pipeline may fail later at git branch (no remote) but should NOT fail at validation
      (let [error-types (set (map :type (:errors result)))]
        (is (not (contains? error-types :no-code-artifacts))
            "Should not fail with no-code-artifacts for valid artifact")
        (is (not (contains? error-types :no-code-files))
            "Should not fail with no-code-files for valid artifact")
        (is (not (contains? error-types :no-files-to-write))
            "Should not fail with no-files-to-write for valid artifact")))))

(deftest mixed-nil-and-valid-artifacts-test
  (testing "release executor filters out nil artifacts and processes valid ones"
    (let [state (make-workflow-state
                 [{:artifact/type :code
                   :artifact/content nil}
                  {:artifact/type :code
                   :artifact/content {:code/id (random-uuid)
                                      :code/files [{:path "src/valid.clj"
                                                    :content "(ns valid)"
                                                    :action :create}]}}])
          result (release-executor/execute-release-phase state (make-context) {})]
      ;; Nil artifact should be filtered out, valid one should pass validation
      (let [error-types (set (map :type (:errors result)))]
        (is (not (contains? error-types :no-code-artifacts))
            "Should not fail with no-code-artifacts — nil was filtered, valid remained")
        (is (not (contains? error-types :no-code-files))
            "Should not fail with no-code-files — valid artifact has files")))))
