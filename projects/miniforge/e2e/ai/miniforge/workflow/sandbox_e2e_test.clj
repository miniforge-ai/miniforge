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

(ns ai.miniforge.workflow.sandbox-e2e-test
  "End-to-end tests for sandbox (Docker) execution.

   These tests spin up real Docker containers using the Clojure task runner image,
   perform git operations inside the container, and verify the full sandbox pipeline.

   Requires Docker to be available. Tests are skipped gracefully if Docker is unavailable."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.executor :as executor]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.release-executor.sandbox :as sandbox]))

;; ============================================================================
;; Test infrastructure
;; ============================================================================

(defn docker-available?
  "Check if Docker is available for testing."
  []
  (let [exec (executor/create-docker-executor {:image "alpine:latest"})
        r (executor/available? exec)]
    (and (result/ok? r)
         (:available? (:data r)))))

(defn task-runner-image-available?
  "Check if the minimal task runner image (with git) is available."
  []
  (executor/image-exists? nil "miniforge/task-runner:latest"))

(defn clojure-image-available?
  "Check if the Clojure task runner image is available."
  []
  (executor/image-exists? nil "miniforge/task-runner-clojure:latest"))

(defmacro when-docker
  "Execute body only if Docker is available."
  [& body]
  `(if (docker-available?)
     (do ~@body)
     (println "  SKIPPED: Docker not available")))

(defmacro when-task-runner
  "Execute body only if Docker + task runner image (with git) available."
  [& body]
  `(if (and (docker-available?) (task-runner-image-available?))
     (do ~@body)
     (println "  SKIPPED: Docker or task-runner image not available")))

(defmacro when-clojure-image
  "Execute body only if Docker + Clojure image available."
  [& body]
  `(if (and (docker-available?) (clojure-image-available?))
     (do ~@body)
     (println "  SKIPPED: Docker or Clojure image not available")))

;; Track containers for cleanup
(def ^:dynamic *containers* (atom []))

(defn cleanup-containers
  "Cleanup any containers created during test."
  [f]
  (reset! *containers* [])
  (try
    (f)
    (finally
      (doseq [{:keys [executor environment-id]} @*containers*]
        (try
          (executor/release-environment! executor environment-id)
          (catch Exception _))))))

(use-fixtures :each cleanup-containers)

;; ============================================================================
;; E2E: Full sandbox lifecycle with Alpine
;; ============================================================================

(deftest ^:e2e sandbox-write-file-e2e-test
  (testing "write-file! creates files inside Docker container via base64"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)
            env-result (executor/acquire-environment! exec task-id {})]
        (is (result/ok? env-result))
        (let [env (:data env-result)
              env-id (:environment-id env)]
          (swap! *containers* conj {:executor exec :environment-id env-id})

          (testing "write a Clojure file into container"
            (let [content "(ns example.core)\n\n(defn greet [name]\n  (str \"Hello, \" name \"!\"))\n"
                  write-result (sandbox/write-file! exec env-id "src/example/core.clj" content)]
              (is (:success? write-result))))

          (testing "verify file exists and has correct content"
            (let [cat-result (executor/execute! exec env-id "cat src/example/core.clj" {})]
              (is (result/ok? cat-result))
              (is (= 0 (:exit-code (:data cat-result))))
              (is (str/includes? (:stdout (:data cat-result)) "(ns example.core)"))))

          (testing "write a file with special characters"
            (let [content "line 1\nline 2\n\"quoted\"\ntab\there\n"
                  write-result (sandbox/write-file! exec env-id "test/special.txt" content)]
              (is (:success? write-result))
              (let [cat-result (executor/execute! exec env-id "cat test/special.txt" {})]
                (is (= content (:stdout (:data cat-result))))))))))))

(deftest ^:e2e sandbox-delete-file-e2e-test
  (testing "delete-file! removes files inside Docker container"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)
            env-result (executor/acquire-environment! exec task-id {})]
        (is (result/ok? env-result))
        (let [env (:data env-result)
              env-id (:environment-id env)]
          (swap! *containers* conj {:executor exec :environment-id env-id})

          ;; Create a file
          (executor/execute! exec env-id "echo 'hello' > /workspace/to-delete.txt" {})

          ;; Delete it
          (let [delete-result (sandbox/delete-file! exec env-id "/workspace/to-delete.txt")]
            (is (:success? delete-result)))

          ;; Verify gone
          (let [ls-result (executor/execute! exec env-id "test -f /workspace/to-delete.txt" {})]
            (is (not= 0 (:exit-code (:data ls-result))))))))))

;; ============================================================================
;; E2E: Git operations in container
;; ============================================================================

(deftest ^:e2e sandbox-git-operations-e2e-test
  (testing "Full git workflow inside Docker container"
    (when-task-runner
      (let [exec (executor/create-docker-executor {:image "miniforge/task-runner:latest"})
            task-id (random-uuid)
            env-result (executor/acquire-environment! exec task-id {})]
        (is (result/ok? env-result))
        (let [env (:data env-result)
              env-id (:environment-id env)]
          (swap! *containers* conj {:executor exec :environment-id env-id})

          ;; Initialize a git repo in container (simulating post-clone state)
          (executor/execute! exec env-id "git init" {})
          (executor/execute! exec env-id "git config user.email 'test@test.com'" {})
          (executor/execute! exec env-id "git config user.name 'Test'" {})
          ;; Create initial commit so we have a HEAD
          (executor/execute! exec env-id "echo init > README.md && git add . && git commit -m 'init'" {})

          (testing "write files via sandbox"
            (let [r (sandbox/write-file! exec env-id "src/core.clj" "(ns core)")]
              (is (:success? r))))

          (testing "stage files via sandbox"
            (let [r (sandbox/stage-files! exec env-id :all)]
              (is (:success? r))))

          (testing "commit via sandbox"
            (let [r (sandbox/commit-changes! exec env-id "feat: add core namespace")]
              (is (:success? r))
              (is (some? (:commit-sha r)))
              (is (= 40 (count (:commit-sha r))))))

          (testing "verify commit in git log"
            (let [log-result (executor/execute! exec env-id "git log --oneline -1" {})]
              (is (str/includes? (:stdout (:data log-result)) "feat: add core namespace")))))))))

;; ============================================================================
;; E2E: write-and-stage-files! batch operation
;; ============================================================================

(deftest ^:e2e sandbox-write-and-stage-files-e2e-test
  (testing "write-and-stage-files! batch operation in Docker container"
    (when-task-runner
      (let [exec (executor/create-docker-executor {:image "miniforge/task-runner:latest"})
            task-id (random-uuid)
            env-result (executor/acquire-environment! exec task-id {})]
        (is (result/ok? env-result))
        (let [env (:data env-result)
              env-id (:environment-id env)]
          (swap! *containers* conj {:executor exec :environment-id env-id})

          ;; Initialize git repo
          (executor/execute! exec env-id "git init" {})
          (executor/execute! exec env-id "git config user.email 'test@test.com'" {})
          (executor/execute! exec env-id "git config user.name 'Test'" {})
          ;; Create initial commit and a file for modify/delete
          (executor/execute! exec env-id
                             (str "echo old > src/existing.clj && "
                                  "echo deleteme > src/old.clj && "
                                  "git add . && git commit -m 'init'")
                             {})

          (testing "batch write, modify, delete, and stage"
            (let [code-artifacts [{:code/files [{:action :create
                                                 :path "src/new.clj"
                                                 :content "(ns new)"}
                                                {:action :modify
                                                 :path "src/existing.clj"
                                                 :content "(ns existing.updated)"}
                                                {:action :delete
                                                 :path "src/old.clj"}]}]
                  result (sandbox/write-and-stage-files! exec env-id code-artifacts)]
              (is (:success? result))
              (is (= 1 (get-in result [:metrics :files-written])))
              (is (= 1 (get-in result [:metrics :files-modified])))
              (is (= 1 (get-in result [:metrics :files-deleted])))
              (is (= 3 (get-in result [:metrics :total-operations])))))

          (testing "verify files in container"
            ;; New file exists
            (let [r (executor/execute! exec env-id "cat src/new.clj" {})]
              (is (= "(ns new)" (str/trim (:stdout (:data r))))))
            ;; Modified file has new content
            (let [r (executor/execute! exec env-id "cat src/existing.clj" {})]
              (is (= "(ns existing.updated)" (str/trim (:stdout (:data r))))))
            ;; Deleted file is gone
            (let [r (executor/execute! exec env-id "test -f src/old.clj" {})]
              (is (not= 0 (:exit-code (:data r))))))

          (testing "git status shows staged changes"
            (let [r (executor/execute! exec env-id "git status --porcelain" {})]
              (is (str/includes? (:stdout (:data r)) "src/new.clj"))
              (is (str/includes? (:stdout (:data r)) "src/existing.clj")))))))))

;; ============================================================================
;; E2E: Full sandbox pipeline (with Clojure image)
;; ============================================================================

(deftest ^:e2e sandbox-full-pipeline-clojure-image-test
  (testing "Full sandbox pipeline with Clojure task runner image"
    (when-clojure-image
      (let [exec (executor/create-docker-executor
                  {:image "miniforge/task-runner-clojure:latest"})
            task-id (random-uuid)
            env-result (executor/acquire-environment! exec task-id {})]
        (is (result/ok? env-result))
        (let [env (:data env-result)
              env-id (:environment-id env)]
          (swap! *containers* conj {:executor exec :environment-id env-id})

          ;; Verify Clojure tooling is available
          (testing "Clojure tools available in container"
            (let [clj-r (executor/execute! exec env-id "clojure --version" {})]
              (is (= 0 (:exit-code (:data clj-r)))))
            (let [bb-r (executor/execute! exec env-id "bb --version" {})]
              (is (= 0 (:exit-code (:data bb-r)))))
            ;; gh CLI is available after image rebuild with Dockerfile changes
            (let [gh-r (executor/execute! exec env-id "which gh" {})]
              (when (= 0 (:exit-code (:data gh-r)))
                (let [gh-ver (executor/execute! exec env-id "gh --version" {})]
                  (is (= 0 (:exit-code (:data gh-ver))))))))

          ;; Initialize repo
          (executor/execute! exec env-id
                             "git init && git config user.email 'test@test.com' && git config user.name 'Test' && echo init > README.md && git add . && git commit -m 'init'"
                             {})

          (testing "write Clojure files"
            (let [r (sandbox/write-file! exec env-id "src/example/core.clj"
                                         "(ns example.core)\n\n(defn hello []\n  \"Hello from sandbox!\")\n")]
              (is (:success? r))))

          (testing "write test file"
            (let [r (sandbox/write-file! exec env-id "test/example/core_test.clj"
                                         "(ns example.core-test\n  (:require [clojure.test :refer [deftest is]]\n            [example.core :as core]))\n\n(deftest hello-test\n  (is (= \"Hello from sandbox!\" (core/hello))))\n")]
              (is (:success? r))))

          (testing "stage and commit"
            (sandbox/stage-files! exec env-id :all)
            (let [r (sandbox/commit-changes! exec env-id "feat: add example namespace")]
              (is (:success? r))
              (is (= 40 (count (:commit-sha r))))))

          (testing "verify git log"
            (let [r (executor/execute! exec env-id "git log --oneline" {})]
              (is (str/includes? (:stdout (:data r)) "feat: add example namespace")))))))))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Run E2E tests manually:
  ;; clojure -M:dev:test -e "(require 'ai.miniforge.workflow.sandbox-e2e-test) (clojure.test/run-tests 'ai.miniforge.workflow.sandbox-e2e-test)"

  ;; Check prerequisites:
  (docker-available?)
  (clojure-image-available?)

  ;; Build Clojure image if needed:
  ;; (executor/ensure-image! nil :clojure)

  :leave-this-here)
