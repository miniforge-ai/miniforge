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

(ns ai.miniforge.dag-executor.executor-test
  "Integration tests for TaskExecutor implementations.

   These tests validate the full lifecycle of Docker and Kubernetes executors.
   Tests are skipped gracefully if the respective backend is unavailable."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.executor :as executor]
   [ai.miniforge.dag-executor.result :as result]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn docker-available?
  "Check if Docker is available for testing."
  []
  (let [exec (executor/create-docker-executor {:image "alpine:latest"})
        result (executor/available? exec)]
    (and (result/ok? result)
         (:available? (:data result))
         (let [env-result (executor/acquire-environment! exec (random-uuid) {})]
           (when (result/ok? env-result)
             (executor/release-environment! exec (:environment-id (:data env-result))))
           (result/ok? env-result)))))

(defn k8s-available?
  "Check if Kubernetes is available for testing."
  []
  (let [exec (executor/create-kubernetes-executor {})
        result (executor/available? exec)]
    (and (result/ok? result)
         (:available? (:data result)))))

(defmacro when-docker
  "Execute body only if Docker is available, otherwise skip with message."
  [& body]
  `(if (docker-available?)
     (do ~@body)
     (println "  SKIPPED: Docker not available")))

(defmacro when-k8s
  "Execute body only if Kubernetes is available, otherwise skip with message."
  [& body]
  `(if (k8s-available?)
     (do ~@body)
     (println "  SKIPPED: Kubernetes not available")))

;; ============================================================================
;; Docker Executor Tests
;; ============================================================================

(deftest docker-executor-availability-test
  (testing "Docker executor reports availability correctly"
    (let [exec (executor/create-docker-executor {:image "alpine:latest"})
          result (executor/available? exec)]
      (is (result/ok? result))
      (is (contains? (:data result) :available?))
      (when (:available? (:data result))
        (is (contains? (:data result) :docker-version))))))

(deftest docker-executor-type-test
  (testing "Docker executor returns correct type"
    (let [exec (executor/create-docker-executor {})]
      (is (= :docker (executor/executor-type exec))))))

(deftest docker-executor-lifecycle-test
  (testing "Docker executor full lifecycle"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)]

        (testing "acquire environment creates container"
          (let [env-result (executor/acquire-environment! exec task-id
                                                          {:env {:TEST_VAR "hello"}})]
            (is (result/ok? env-result))
            (let [env (:data env-result)
                  env-id (:environment-id env)]
              (is (some? env-id))
              (is (= :docker (:executor-type env)))

              (try
                (testing "execute runs commands in container"
                  (let [exec-result (executor/execute! exec env-id "echo $TEST_VAR" {})]
                    (is (result/ok? exec-result))
                    (is (= 0 (:exit-code (:data exec-result))))
                    (is (= "hello\n" (:stdout (:data exec-result))))))

                (testing "execute with multiple commands"
                  (let [exec-result (executor/execute! exec env-id
                                                       "pwd && id -u"
                                                       {})]
                    (is (result/ok? exec-result))
                    (is (= 0 (:exit-code (:data exec-result))))
                    (is (= "/workspace\n1000\n" (:stdout (:data exec-result))))))

                (testing "environment-status shows running"
                  (let [status-result (executor/environment-status exec env-id)]
                    (is (result/ok? status-result))
                    (is (= :running (:status (:data status-result))))))

                (finally
                  (testing "release cleans up container"
                    (let [release-result (executor/release-environment! exec env-id)]
                      (is (result/ok? release-result))
                      (is (:released? (:data release-result))))))))))))))

(deftest docker-executor-file-operations-test
  (testing "Docker executor file copy operations"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)
            temp-file (java.io.File/createTempFile "test" ".txt")
            temp-out (java.io.File/createTempFile "test-out" ".txt")]
        (try
          ;; Write test content to temp file
          (spit temp-file "test content from host")

          (let [env-result (executor/acquire-environment! exec task-id {})]
            (is (result/ok? env-result))
            (let [env (:data env-result)
                  env-id (:environment-id env)]
              (try
                (testing "copy-to! copies files into container"
                  (let [copy-result (executor/copy-to! exec env-id
                                                       (.getAbsolutePath temp-file)
                                                       "/tmp/test-file.txt")]
                    (is (result/ok? copy-result))))

                (testing "file exists in container"
                  (let [exec-result (executor/execute! exec env-id
                                                       "cat /tmp/test-file.txt" {})]
                    (is (result/ok? exec-result))
                    (is (= 0 (:exit-code (:data exec-result))))
                    (is (= "test content from host" (:stdout (:data exec-result))))))

                (testing "create file in container for copy-from"
                  (let [exec-result (executor/execute! exec env-id
                                                       "echo 'from container' > /tmp/out.txt"
                                                       {})]
                    (is (result/ok? exec-result))
                    (is (= 0 (:exit-code (:data exec-result))))))

                (testing "copy-from! copies files from container"
                  (let [copy-result (executor/copy-from! exec env-id
                                                         "/tmp/out.txt"
                                                         (.getAbsolutePath temp-out))]
                    (is (result/ok? copy-result))
                    (is (= "from container\n" (slurp temp-out)))))

                (finally
                  (executor/release-environment! exec env-id)))))
          (finally
            (.delete temp-file)
            (.delete temp-out)))))))

(deftest docker-executor-with-environment-test
  (testing "with-environment helper manages lifecycle"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)
            result (executor/with-environment exec task-id {}
                     (fn [env]
                       (executor/execute! exec (:environment-id env)
                                          "echo 'inside environment'" {})))]
        (is (result/ok? result))
        (is (= 0 (:exit-code (:data result))))
        (is (= "inside environment\n" (:stdout (:data result))))))))

(deftest docker-executor-env-vars-test
  (testing "Docker executor passes environment variables"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)]
        (executor/with-environment exec task-id
          {:env {:MY_VAR "my_value"
                 :ANOTHER "another_value"}}
          (fn [env]
            (let [result (executor/execute! exec (:environment-id env)
                                            "echo $MY_VAR:$ANOTHER" {})]
              (is (result/ok? result))
              (is (= "my_value:another_value\n" (:stdout (:data result)))))))))))

(deftest docker-executor-workdir-test
  (testing "Docker executor respects working directory"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)]
        (executor/with-environment exec task-id
          {:workdir "/tmp"}
          (fn [env]
            (let [result (executor/execute! exec (:environment-id env) "pwd" {})]
              (is (result/ok? result))
              (is (= "/tmp\n" (:stdout (:data result)))))))))))

(deftest docker-executor-resource-limits-test
  (testing "Docker executor applies resource limits"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)]
        ;; Just verify container starts with limits - actual enforcement is Docker's job
        (executor/with-environment exec task-id
          {:resources {:memory "64m" :cpu 0.5}}
          (fn [env]
            (let [result (executor/execute! exec (:environment-id env)
                                            "echo 'started with limits'" {})]
              (is (result/ok? result))
              (is (= 0 (:exit-code (:data result)))))))))))

;; ============================================================================
;; Kubernetes Executor Tests
;; ============================================================================

(deftest k8s-executor-availability-test
  (testing "Kubernetes executor reports availability correctly"
    (let [exec (executor/create-kubernetes-executor {})
          result (executor/available? exec)]
      (is (result/ok? result))
      (is (contains? (:data result) :available?)))))

(deftest k8s-executor-type-test
  (testing "Kubernetes executor returns correct type"
    (let [exec (executor/create-kubernetes-executor {})]
      (is (= :kubernetes (executor/executor-type exec))))))

(deftest k8s-executor-lifecycle-test
  (testing "Kubernetes executor full lifecycle"
    (when-k8s
      (let [exec (executor/create-kubernetes-executor
                  {:namespace "default"
                   :image "alpine:latest"})
            task-id (random-uuid)]

        (testing "acquire environment creates job"
          (let [env-result (executor/acquire-environment! exec task-id {})]
            (is (result/ok? env-result))
            (let [env (:data env-result)
                  env-id (:environment-id env)]
              (is (some? env-id))
              (is (= :kubernetes (:executor-type env)))

              (try
                (testing "execute runs commands in pod"
                  ;; Note: K8s jobs need time to start
                  (Thread/sleep 5000)
                  (let [exec-result (executor/execute! exec env-id "echo hello" {})]
                    (is (result/ok? exec-result))))

                (finally
                  (testing "release cleans up job"
                    (let [release-result (executor/release-environment! exec env-id)]
                      (is (result/ok? release-result)))))))))))))

;; ============================================================================
;; Executor Registry Tests
;; ============================================================================

(deftest executor-registry-test
  (testing "create-executor-registry creates all configured executors"
    (let [registry (executor/create-executor-registry
                    {:docker {:image "alpine:latest"}
                     :kubernetes {:namespace "default"}
                     :worktree {:base-path "/tmp/test-worktrees"}})]
      (is (contains? registry :docker))
      (is (contains? registry :kubernetes))
      (is (contains? registry :worktree))

      (is (= :docker (executor/executor-type (:docker registry))))
      (is (= :kubernetes (executor/executor-type (:kubernetes registry))))
      (is (= :worktree (executor/executor-type (:worktree registry)))))))

(deftest executor-registry-fallback-test
  (testing "Worktree executor is always included as fallback"
    (let [registry (executor/create-executor-registry {})]
      (is (contains? registry :worktree))
      (is (= :worktree (executor/executor-type (:worktree registry)))))))

(deftest select-executor-test
  (testing "select-executor chooses best available"
    (let [registry (executor/create-executor-registry
                    {:docker {:image "alpine:latest"}
                     :worktree {:base-path "/tmp/test-worktrees"}})
          ;; Should select docker if available, otherwise worktree
          selected (executor/select-executor registry)]
      (is (some? selected))
      (is (#{:docker :worktree} (executor/executor-type selected))))))

(deftest select-executor-preferred-test
  (testing "select-executor respects preferred option"
    (let [registry (executor/create-executor-registry
                    {:docker {:image "alpine:latest"}
                     :worktree {:base-path "/tmp/test-worktrees"}})
          ;; Explicitly prefer worktree
          selected (executor/select-executor registry :preferred :worktree)]
      (is (some? selected))
      (is (= :worktree (executor/executor-type selected))))))

;; ============================================================================
;; Worktree Executor Tests (always available)
;; ============================================================================

(deftest worktree-executor-availability-test
  (testing "Worktree executor is always available (git installed)"
    (let [exec (executor/create-worktree-executor {})
          result (executor/available? exec)]
      (is (result/ok? result))
      (is (:available? (:data result)))
      (is (contains? (:data result) :git-version)))))

(deftest worktree-executor-type-test
  (testing "Worktree executor returns correct type"
    (let [exec (executor/create-worktree-executor {})]
      (is (= :worktree (executor/executor-type exec))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest docker-executor-invalid-command-test
  (testing "Docker executor handles command failures"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)]
        (executor/with-environment exec task-id {}
          (fn [env]
            (let [result (executor/execute! exec (:environment-id env)
                                            "nonexistent-command" {})]
              (is (result/ok? result))
              (is (not= 0 (:exit-code (:data result)))))))))))

(deftest docker-executor-copy-nonexistent-test
  (testing "Docker executor handles copy of nonexistent file"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-id (random-uuid)]
        (executor/with-environment exec task-id {}
          (fn [env]
            (let [result (executor/copy-from! exec (:environment-id env)
                                              "/nonexistent/file.txt"
                                              "/tmp/out.txt")]
              (is (result/err? result)))))))))

;; ============================================================================
;; Concurrent Execution Tests
;; ============================================================================

(deftest docker-executor-concurrent-test
  (testing "Docker executor handles concurrent environments"
    (when-docker
      (let [exec (executor/create-docker-executor {:image "alpine:latest"})
            task-ids [(random-uuid) (random-uuid) (random-uuid)]
            results (atom [])
            ;; Start 3 containers concurrently
            futures (mapv (fn [tid]
                            (future
                              (executor/with-environment exec tid {}
                                (fn [env]
                                  (let [r (executor/execute! exec
                                                             (:environment-id env)
                                                             (str "echo " tid)
                                                             {})]
                                    (swap! results conj
                                           {:task-id tid
                                            :success? (and (result/ok? r)
                                                           (= 0 (:exit-code
                                                                 (:data r))))}))))))
                          task-ids)]

        ;; Wait for all to complete
        (doseq [f futures]
          (deref f 60000 :timeout))

        ;; All should succeed
        (is (= 3 (count @results)))
        (is (every? :success? @results))))))

;; ============================================================================
;; Rich Comment for REPL Testing
;; ============================================================================

(comment
  ;; Quick REPL test for Docker
  (def exec (executor/create-docker-executor {:image "alpine:latest"}))
  (executor/available? exec)

  ;; Test lifecycle
  (def env-result (executor/acquire-environment! exec (random-uuid) {}))
  (def env-id (:environment-id (:data env-result)))

  (executor/execute! exec env-id "ls -la" {})
  (executor/environment-status exec env-id)
  (executor/release-environment! exec env-id)

  ;; Test with-environment
  (executor/with-environment exec (random-uuid) {}
    (fn [env]
      (executor/execute! exec (:environment-id env) "cat /etc/os-release" {})))

  :leave-this-here)
