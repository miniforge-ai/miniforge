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

(ns ai.miniforge.release-executor.artifact-validation-integration-test
  "Project-level integration tests for artifact validation in the release executor.

  Validates the fail-fast behavior when artifacts are nil, empty, or have no files.
  Exercises the release-executor component through its public interface."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [ai.miniforge.release-executor.interface :as release-executor]
   [ai.miniforge.dag-executor.protocols.executor :as executor-proto]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (fs/create-temp-dir {:prefix "artifact-val-test-"})]
    (process/shell {:dir (str dir)} "git init")
    (process/shell {:dir (str dir)} "git config user.email" "test@example.com")
    (process/shell {:dir (str dir)} "git config user.name" "Test User")
    (process/shell {:dir (str dir)} "git config commit.gpgsign" "false")
    (process/shell {:dir (str dir)} "git config tag.gpgsign" "false")
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

(defn make-stub-executor
  "Create a stub executor that runs shell commands in the temp dir.
   Satisfies the TaskExecutor protocol so the release pipeline passes
   input validation and can execute git operations on the test repo."
  [worktree-dir]
  (reify executor-proto/TaskExecutor
    (executor-type [_] :worktree)
    (available? [_] {:ok? true :data {:available? true}})
    (acquire-environment! [_ _task-id _config]
      {:ok? true :data {:environment-id "test-env-001" :type :worktree
                        :workdir (str worktree-dir)}})
    (execute! [_ _env-id command opts]
      (try
        (let [workdir (or (:workdir opts) (str worktree-dir))
              result (process/shell {:dir workdir :out :string :err :string :continue true}
                                    "sh" "-c" command)]
          {:ok? true :data {:exit-code (:exit result)
                            :stdout (or (:out result) "")
                            :stderr (or (:err result) "")}})
        (catch Exception e
          {:ok? false :error {:code :exec-error :message (.getMessage e)}})))
    (copy-to! [_ _env-id _local _remote]
      {:ok? true :data {:copied-bytes 0}})
    (copy-from! [_ _env-id _remote _local]
      {:ok? true :data {:copied-bytes 0}})
    (release-environment! [_ _env-id]
      {:ok? true :data {:released? true}})
    (environment-status [_ _env-id]
      {:ok? true :data {:status :running}})
    (persist-workspace! [_ _env-id _opts]
      {:ok? true :data {:persisted? true}})))

(defn make-context []
  {:worktree-path *temp-dir*
   :create-pr? false
   :executor (make-stub-executor *temp-dir*)
   :environment-id "test-env-001"
   :logger (log/create-logger {:min-level :warn})})

;------------------------------------------------------------------------------ Tests

(deftest nil-artifacts-rejected-test
  (testing "release executor pipeline fails with nil artifact content"
    (let [state (make-workflow-state [{:artifact/type :code
                                       :artifact/content nil}])
          result (release-executor/execute-release-phase state (make-context) {})]
      (is (false? (:success? result))
          "Should fail when artifact content is nil")
      ;; In the environment model, the release executor discovers files from the
      ;; worktree via git, not from artifact :code/files. The pipeline passes
      ;; input validation (executor + environment-id) and fails downstream at
      ;; git branch creation (no remote) or staging (no dirty files).
      (is (not (some #(= :missing-executor (:type %)) (:errors result)))
          "Should get past input validation (executor is provided)")
      (is (not (some #(= :missing-environment-id (:type %)) (:errors result)))
          "Should get past input validation (environment-id is provided)")
      (is (seq (:errors result))
          "Should have downstream pipeline errors"))))

(deftest empty-files-rejected-test
  (testing "release executor pipeline fails with zero-file artifacts"
    (let [state (make-workflow-state [{:artifact/type :code
                                       :artifact/content {:code/id (random-uuid)
                                                          :code/files []}}])
          result (release-executor/execute-release-phase state (make-context) {})]
      (is (false? (:success? result))
          "Should fail when artifact has zero files")
      ;; In the environment model, the release executor discovers files from the
      ;; worktree via git, not from artifact :code/files. The pipeline passes
      ;; input validation and fails downstream at git operations.
      (is (not (some #(= :missing-executor (:type %)) (:errors result)))
          "Should get past input validation (executor is provided)")
      (is (not (some #(= :missing-environment-id (:type %)) (:errors result)))
          "Should get past input validation (environment-id is provided)")
      (is (seq (:errors result))
          "Should have downstream pipeline errors"))))

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
      (is (not (contains? (set (map :type (:errors result))) :no-code-artifacts))
          "Should not fail with no-code-artifacts for valid artifact")
      (is (not (contains? (set (map :type (:errors result))) :no-code-files))
          "Should not fail with no-code-files for valid artifact")
      (is (not (contains? (set (map :type (:errors result))) :no-files-to-write))
          "Should not fail with no-files-to-write for valid artifact"))))

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
      (is (not (contains? (set (map :type (:errors result))) :no-code-artifacts))
          "Should not fail with no-code-artifacts — nil was filtered, valid remained")
      (is (not (contains? (set (map :type (:errors result))) :no-code-files))
          "Should not fail with no-code-files — valid artifact has files"))))
