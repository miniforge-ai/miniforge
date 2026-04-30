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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test
  "Tests for OCI-CLI executor: token sanitization, URL auth, image
   management, descriptor wiring."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]))

;; Private fn accessor helper
(defn- private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli sym)))

;; Default descriptor used by tests that exercise CLI argument shaping
;; rather than runtime selection. `make-descriptor` defaults to :docker
;; which matches Phase 1 supported-kinds.
(defn- docker-descriptor
  ([] (descriptor/make-descriptor {}))
  ([opts] (descriptor/make-descriptor opts)))

;; ============================================================================
;; descriptor — basic construction
;; ============================================================================

(deftest descriptor-defaults-to-docker-test
  (testing "make-descriptor defaults to :docker"
    (let [d (descriptor/make-descriptor {})]
      (is (= :docker (descriptor/kind d)))
      (is (= "docker" (descriptor/executable d))))))

(deftest descriptor-honors-explicit-executable-test
  (testing "make-descriptor uses :executable when provided"
    (let [d (descriptor/make-descriptor {:executable "/opt/homebrew/bin/docker"})]
      (is (= "/opt/homebrew/bin/docker" (descriptor/executable d))))))

(deftest descriptor-honors-legacy-docker-path-test
  (testing "make-descriptor falls back to :docker-path for :docker kind"
    (let [d (descriptor/make-descriptor {:docker-path "/usr/bin/docker"})]
      (is (= "/usr/bin/docker" (descriptor/executable d))))))

(deftest descriptor-rejects-podman-in-phase-1-test
  (testing "make-descriptor throws :runtime/unsupported for :podman in Phase 1"
    (try
      (descriptor/make-descriptor {:runtime-kind :podman})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :podman (-> e ex-data :runtime/unsupported)))))))

(deftest descriptor-rejects-unknown-kind-test
  (testing "make-descriptor throws :runtime/unknown-kind for unknown kinds"
    (try
      (descriptor/make-descriptor {:runtime-kind :unknown-runtime})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unknown-runtime (-> e ex-data :runtime/unknown-kind)))))))

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
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 1 :out "" :err "No such image"})]
      (is (false? (oci-cli/image-exists? (docker-descriptor)
                                         "nonexistent/image:never"))))))

;; ============================================================================
;; container-image-digest
;; ============================================================================

(deftest container-image-digest-returns-sha-on-success-test
  (testing "returns trimmed digest string when inspect succeeds"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 0 :out "sha256:abc123def456\n" :err ""})]
      (is (= "sha256:abc123def456"
             (oci-cli/container-image-digest (docker-descriptor) "my-container"))))))

(deftest container-image-digest-returns-nil-on-nonzero-exit-test
  (testing "returns nil when inspect exits non-zero"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 1 :out "" :err "No such container"})]
      (is (nil? (oci-cli/container-image-digest (docker-descriptor) "missing"))))))

(deftest container-image-digest-returns-nil-on-empty-output-test
  (testing "returns nil when inspect output is blank"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 0 :out "  \n" :err ""})]
      (is (nil? (oci-cli/container-image-digest (docker-descriptor) "empty-out"))))))

(deftest container-image-digest-returns-nil-on-exception-test
  (testing "returns nil when run-runtime throws"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    (throw (ex-info "Runtime not found" {})))]
      (is (nil? (oci-cli/container-image-digest (docker-descriptor) "any-container"))))))

;; ============================================================================
;; persist-workspace!
;; ============================================================================

(deftest persist-workspace-with-changes-test
  (testing "persist-workspace! commits and pushes when there are dirty files"
    (let [commands (atom [])
          executor (oci-cli/create-docker-executor {:image "test:latest"})]
      (with-redefs [oci-cli/exec-in-container
                    (fn [_descriptor _env-id cmd _opts]
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
    (let [executor (oci-cli/create-docker-executor {:image "test:latest"})]
      (with-redefs [oci-cli/exec-in-container
                    (fn [_descriptor _env-id cmd _opts]
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
          executor (oci-cli/create-docker-executor {:image "test:latest"})]
      (with-redefs [oci-cli/exec-in-container
                    (fn [_descriptor _env-id cmd _opts]
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
;; create-container --stop-timeout (N11 §2.2)
;; ============================================================================

(deftest create-container-stop-timeout-test
  (testing "includes --stop-timeout when execution-plan has :time-limit-ms"
    (let [captured-args (atom nil)]
      (with-redefs [oci-cli/run-runtime (fn [_descriptor & args]
                                          (reset! captured-args (vec args))
                                          {:exit 0 :out "container-id-123\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "test-ctr" "alpine"
                                  "/workspace" nil nil nil
                                  :execution-plan {:time-limit-ms 120000})
        (let [args @captured-args]
          (is (some #(= "--stop-timeout" %) args))
          (is (some #(= "120" %) args))))))

  (testing "omits --stop-timeout when no execution-plan"
    (let [captured-args (atom nil)]
      (with-redefs [oci-cli/run-runtime (fn [_descriptor & args]
                                          (reset! captured-args (vec args))
                                          {:exit 0 :out "container-id-123\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "test-ctr" "alpine"
                                  "/workspace" nil nil nil)
        (let [args @captured-args]
          (is (not (some #(= "--stop-timeout" %) args)))))))

  (testing "enforces minimum 5s stop-timeout"
    (let [captured-args (atom nil)]
      (with-redefs [oci-cli/run-runtime (fn [_descriptor & args]
                                          (reset! captured-args (vec args))
                                          {:exit 0 :out "container-id-123\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "test-ctr" "alpine"
                                  "/workspace" nil nil nil
                                  :execution-plan {:time-limit-ms 2000})
        (let [args @captured-args]
          (is (some #(= "5" %) args)))))))

;; ============================================================================
;; executor-type reflects descriptor kind
;; ============================================================================

(deftest executor-type-from-descriptor-test
  (testing "OciCliExecutor reports its descriptor's runtime kind"
    (let [exec (oci-cli/create-docker-executor {:image "alpine:latest"})]
      (is (= :docker (proto/executor-type exec))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test)
  :leave-this-here)
