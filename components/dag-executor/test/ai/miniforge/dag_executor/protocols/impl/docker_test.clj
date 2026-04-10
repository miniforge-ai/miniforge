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

(ns ai.miniforge.dag-executor.protocols.impl.docker-test
  "Tests for Docker executor: token sanitization, URL auth, image management."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.docker :as docker]))

;; Private fn accessor helper
(defn- private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.dag-executor.protocols.impl.docker sym)))

;; ============================================================================
;; sanitize-token
;; ============================================================================

(deftest sanitize-token-github-test
  (testing "sanitizes GitHub x-access-token"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "https://x-access-token:***@github.com/o/r.git"
             (sanitize "https://x-access-token:ghp_abc123@github.com/o/r.git"))))))

(deftest sanitize-token-gitlab-test
  (testing "sanitizes GitLab oauth2 token"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "https://oauth2:***@gitlab.com/g/p.git"
             (sanitize "https://oauth2:glpat-xyz@gitlab.com/g/p.git"))))))

(deftest sanitize-token-no-token-test
  (testing "passes through strings without tokens"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "git clone https://github.com/o/r.git"
             (sanitize "git clone https://github.com/o/r.git"))))))

;; ============================================================================
;; authenticated-https-url
;; ============================================================================

(deftest authenticated-url-github-test
  (testing "injects GitHub x-access-token"
    (let [auth-url (private-fn 'authenticated-https-url)]
      (is (= "https://x-access-token:tok123@github.com/o/r.git"
             (auth-url "https://github.com/o/r.git" "tok123" :github))))))

(deftest authenticated-url-gitlab-test
  (testing "injects GitLab oauth2 token"
    (let [auth-url (private-fn 'authenticated-https-url)]
      (is (= "https://oauth2:tok456@gitlab.com/g/p.git"
             (auth-url "https://gitlab.com/g/p.git" "tok456" :gitlab))))))

;; ============================================================================
;; image-exists? (public)
;; ============================================================================

(deftest image-exists-nonexistent-test
  (testing "image-exists? returns false for nonexistent image"
    (is (false? (docker/image-exists? nil "nonexistent/image:never")))))

;; ============================================================================
;; persist-workspace!
;; ============================================================================

(deftest persist-workspace-with-changes-test
  (testing "persist-workspace! commits and pushes when there are dirty files"
    (let [commands (atom [])
          executor (docker/create-docker-executor {:image "test:latest"})]
      (with-redefs [docker/exec-in-container
                    (fn [_docker-path _env-id cmd _opts]
                      (swap! commands conj cmd)
                      (cond
                        (= cmd "git status --porcelain")
                        {:data {:exit-code 0 :stdout "M src/core.clj\n"}}

                        (= cmd "git rev-parse HEAD")
                        {:data {:exit-code 0 :stdout "abc123\n"}}

                        :else
                        {:data {:exit-code 0 :stdout "" :stderr ""}}))]
        (let [result (proto/persist-workspace! executor "container-1"
                                               {:branch "task/test-123"
                                                :message "implement completed"
                                                :workdir "/workspace"})]
          (is (true? (get-in result [:data :persisted?])))
          (is (= "abc123" (get-in result [:data :commit-sha])))
          (is (some #(= "git add -A" %) @commands))
          (is (some #(clojure.string/includes? % "git commit") @commands))
          (is (some #(clojure.string/includes? % "git push") @commands)))))))

(deftest persist-workspace-no-changes-test
  (testing "persist-workspace! returns {:persisted? false} when no dirty files"
    (let [executor (docker/create-docker-executor {:image "test:latest"})]
      (with-redefs [docker/exec-in-container
                    (fn [_docker-path _env-id cmd _opts]
                      (if (= cmd "git status --porcelain")
                        {:data {:exit-code 0 :stdout ""}}
                        {:data {:exit-code 0 :stdout "" :stderr ""}}))]
        (let [result (proto/persist-workspace! executor "container-1"
                                               {:branch "task/test-123"
                                                :workdir "/workspace"})]
          (is (false? (get-in result [:data :persisted?])))
          (is (true? (get-in result [:data :no-changes?]))))))))

;; ============================================================================
;; restore-workspace!
;; ============================================================================

(deftest restore-workspace-test
  (testing "restore-workspace! fetches and checks out task branch"
    (let [commands (atom [])
          executor (docker/create-docker-executor {:image "test:latest"})]
      (with-redefs [docker/exec-in-container
                    (fn [_docker-path _env-id cmd _opts]
                      (swap! commands conj cmd)
                      (if (= cmd "git rev-parse HEAD")
                        {:data {:exit-code 0 :stdout "def456\n"}}
                        {:data {:exit-code 0 :stdout "" :stderr ""}}))]
        (let [result (proto/restore-workspace! executor "container-1"
                                               {:branch "task/test-123"
                                                :workdir "/workspace"})]
          (is (true? (get-in result [:data :restored?])))
          (is (= "def456" (get-in result [:data :commit-sha])))
          (is (some #(clojure.string/includes? % "git fetch") @commands))
          (is (some #(clojure.string/includes? % "git checkout") @commands)))))))

;; ============================================================================
;; container-image-digest
;; ============================================================================

(deftest container-image-digest-success-test
  (testing "returns trimmed digest when docker inspect succeeds"
    (with-redefs [docker/run-docker (fn [_docker-path & _args]
                                      {:exit 0 :out "sha256:abc123\n"})]
      (is (= "sha256:abc123"
             (docker/container-image-digest nil "myimage:latest"))))))

(deftest container-image-digest-failure-test
  (testing "returns nil when docker inspect fails (non-zero exit)"
    (with-redefs [docker/run-docker (fn [_docker-path & _args]
                                      {:exit 1 :out "" :err "not found"})]
      (is (nil? (docker/container-image-digest nil "nonexistent:latest"))))))

(deftest container-image-digest-exception-test
  (testing "returns nil when docker/run-docker throws an exception"
    (with-redefs [docker/run-docker (fn [_docker-path & _args]
                                      (throw (ex-info "docker not installed" {})))]
      (is (nil? (docker/container-image-digest nil "myimage:latest"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.docker-test)
  :leave-this-here)
