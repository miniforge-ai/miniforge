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
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]
   [clojure.string]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]))

;; Private fn accessor helper
(defn- private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli sym)))

;; Default descriptor used by tests that exercise CLI argument shaping
;; rather than runtime selection. `make-descriptor` defaults to :docker.
(defn- docker-descriptor
  ([] (descriptor/make-descriptor {}))
  ([opts] (descriptor/make-descriptor opts)))

(defn- podman-descriptor
  "For tests describing Podman-specific behaviour (bare-hex image IDs),
   so the setup matches the runtime named in the test."
  []
  (descriptor/make-descriptor {:runtime-kind :podman}))

;; Phase 2: argument-construction tests run against every supported kind so
;; a Podman regression in flag shaping shows up at unit-test time.
(def ^:private supported-kinds-under-test
  [:docker :podman])

(defn- descriptor-for-kind
  [kind]
  (descriptor/make-descriptor {:runtime-kind kind}))

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

(deftest descriptor-accepts-podman-test
  (testing "make-descriptor builds a :podman descriptor in Phase 2"
    (let [d (descriptor/make-descriptor {:runtime-kind :podman})]
      (is (= :podman (descriptor/kind d)))
      (is (= "podman" (descriptor/executable d)))
      (is (descriptor/capable? d :rootless))
      (is (descriptor/capable? d :oci-images)))))

(deftest descriptor-rejects-nerdctl-as-unsupported-test
  (testing "make-descriptor throws :runtime/unsupported for :nerdctl (Future)"
    (try
      (descriptor/make-descriptor {:runtime-kind :nerdctl})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :nerdctl (-> e ex-data :runtime/unsupported)))
        (is (contains? (-> e ex-data :runtime/supported-kinds) :docker))
        (is (contains? (-> e ex-data :runtime/supported-kinds) :podman))))))

(deftest descriptor-rejects-unknown-kind-test
  (testing "make-descriptor throws :runtime/unknown-kind for unknown kinds"
    (try
      (descriptor/make-descriptor {:runtime-kind :unknown-runtime})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unknown-runtime (-> e ex-data :runtime/unknown-kind)))))))

(deftest descriptor-validation-result-returns-anomaly-test
  (testing "constructed descriptor schema drift returns a canonical anomaly"
    (let [result (@#'descriptor/validate-descriptor-result
                  {:runtime/kind :docker})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :runtime/descriptor
             (get-in result [:anomaly/data :anomaly/schema]))))))

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

(deftest container-image-digest-normalizes-podman-bare-hex-test
  (testing "Podman prints the image ID as bare 64-hex; the digest is
            normalized to the sha256:-prefixed form the gate expects"
    (let [hex (apply str (repeat 64 \a))]
      (with-redefs [oci-cli/run-runtime
                    (fn [_d & _args]
                      {:exit 0 :out (str hex "\n") :err ""})]
        (is (= (str "sha256:" hex)
               (oci-cli/container-image-digest (podman-descriptor) "my-container")))))))

(deftest image-config-digest-normalizes-podman-bare-hex-test
  (testing "image-config-digest applies the same normalization on image IDs"
    (let [hex (apply str (repeat 64 \a))]
      (with-redefs [oci-cli/run-runtime
                    (fn [_d & _args]
                      {:exit 0 :out (str hex "\n") :err ""})]
        (is (= (str "sha256:" hex)
               (oci-cli/image-config-digest (podman-descriptor) "img:tag")))))))

(deftest image-config-digest-passes-docker-prefixed-form-through-test
  (testing "Docker's already-prefixed sha256:<hex> ID is returned unchanged"
    (let [digest (str "sha256:" (apply str (repeat 64 \b)))]
      (with-redefs [oci-cli/run-runtime
                    (fn [_d & _args]
                      {:exit 0 :out (str digest "\n") :err ""})]
        (is (= digest
               (oci-cli/image-config-digest (docker-descriptor) "img:tag")))))))

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

(defn- capture-create-container-args
  "Run create-container with `oci-cli/run-runtime` stubbed and return the
   captured argv. Keeps the parameterized stop-timeout tests focused on
   the assertions rather than mock plumbing."
  [descriptor & {:as create-opts}]
  (let [captured-args (atom nil)
        stub          (fn [_descriptor & args]
                        (reset! captured-args (vec args))
                        {:exit 0 :out "container-id-123\n" :err ""})]
    (with-redefs [oci-cli/run-runtime stub]
      (apply oci-cli/create-container
             descriptor "test-ctr" "alpine" "/workspace" nil nil nil
             (mapcat identity create-opts))
      @captured-args)))

(deftest create-container-stop-timeout-test
  (doseq [kind supported-kinds-under-test]
    (testing (str "runtime " kind)
      (testing "  includes --stop-timeout when execution-plan has :time-limit-ms"
        (let [args (capture-create-container-args
                    (descriptor-for-kind kind)
                    :execution-plan {:time-limit-ms 120000})]
          (is (some #(= "--stop-timeout" %) args))
          (is (some #(= "120" %) args))))

      (testing "  omits --stop-timeout when no execution-plan"
        (let [args (capture-create-container-args (descriptor-for-kind kind))]
          (is (not (some #(= "--stop-timeout" %) args)))))

      (testing "  enforces minimum 5s stop-timeout"
        (let [args (capture-create-container-args
                    (descriptor-for-kind kind)
                    :execution-plan {:time-limit-ms 2000})]
          (is (some #(= "5" %) args)))))))

;; ============================================================================
;; executor-type reflects descriptor kind
;; ============================================================================

(deftest executor-type-from-descriptor-test
  (testing "OciCliExecutor reports its descriptor's runtime kind"
    (doseq [kind supported-kinds-under-test]
      (testing (str "kind " kind)
        (let [exec (oci-cli/create-oci-cli-executor
                    {:runtime-kind kind :image "test:latest"})]
          (is (= kind (proto/executor-type exec))))))))

;; ============================================================================
;; acquisition timeout — production-side guard against stuck Docker
;; ============================================================================
;;
;; The 2026-05-16 dogfood hung 33+ minutes when the Docker daemon stalled
;; mid acquire; PR #895 mocked the test side, this guard fails fast in
;; production. Tests target the with-acquisition-timeout helper directly so
;; they stay deterministic without a real Docker daemon.

(deftest with-acquisition-timeout-returns-body-result-on-success-test
  (testing "body that returns in time passes its result through unchanged"
    (let [ok-result {:ok? true :data {:environment-id "env-1"}}]
      (is (= ok-result
             (oci-cli/with-acquisition-timeout 5000 (fn [] ok-result)))))))

(deftest with-acquisition-timeout-fires-on-deadline-test
  (testing "body that exceeds the deadline returns :timeout err"
    (let [result (oci-cli/with-acquisition-timeout
                   50
                   (fn [] (Thread/sleep 5000) :should-not-see))]
      (is (= false (:ok? result)))
      (is (= :timeout (:code (:error result)))
          "Uses the standard dag-executor :timeout code, not a bespoke one")
      (is (= 50 (get-in result [:error :data :timeout-ms])))
      (is (= :acquire-environment! (get-in result [:error :data :surface]))
          ":surface tags which protocol method tripped the deadline")
      (is (clojure.string/includes? (:message (:error result)) "stuck daemon")))))

(deftest with-acquisition-timeout-nil-or-nonpositive-disables-guard-test
  (testing "nil / 0 / negative timeout bypasses the deadline check"
    (doseq [t [nil 0 -1]]
      (is (= :body-ran
             (oci-cli/with-acquisition-timeout t (fn [] :body-ran)))
          (str "timeout " (pr-str t) " must run the body without the guard")))))

(deftest with-acquisition-timeout-disabled-body-exception-returns-error-result-test
  (testing "disabled deadline still returns body exceptions as failure data"
    (let [result (oci-cli/with-acquisition-timeout
                   nil
                   (fn [] (throw (ex-info "boom" {:tag :disabled-timeout}))))]
      (is (result/err? result))
      (is (= :acquire-failed (:code (:error result))))
      (is (= :disabled-timeout
             (get-in result [:error :data :exception-data :tag]))))))

(deftest with-acquisition-timeout-cancels-the-future-on-timeout-test
  (testing "the inner future is cancelled when the deadline fires"
    ;; The body runs an inner sleep that records `cancelled?` via
    ;; InterruptedException. After the outer deadline fires,
    ;; future-cancel interrupts the worker thread and the catch
    ;; branch flips the flag. Assert the flag — without this the
    ;; test passed even if cancellation regressed.
    (let [cancelled? (promise)
          deadline-ms 50
          result (oci-cli/with-acquisition-timeout
                   deadline-ms
                   (fn []
                     (try
                       (Thread/sleep 5000)
                       :never
                       (catch InterruptedException _
                         (deliver cancelled? true)
                         :interrupted))))]
      (is (= :timeout (:code (:error result))))
      (is (= true (deref cancelled? 2000 :no-interrupt))
          "future-cancel must propagate InterruptedException into the body"))))

(deftest with-acquisition-timeout-body-exception-returns-error-result-test
  (testing "exceptions thrown by the body return failure data instead of throwing"
    (let [result (oci-cli/with-acquisition-timeout
                   5000
                   (fn [] (throw (ex-info "boom" {:tag :original}))))]
      (is (false? (:ok? result)))
      (is (= :acquire-failed (:code (:error result))))
      (is (= "boom" (:message (:error result))))
      (is (= :acquire-environment! (get-in result [:error :data :surface])))
      (is (= "clojure.lang.ExceptionInfo"
             (get-in result [:error :data :exception-class])))
      (is (= {:tag :original}
             (get-in result [:error :data :exception-data]))))))

(deftest run-runtime-interrupt-destroys-child-process-test
  (testing "interrupting a runtime call does not wait for the child command to finish"
    (let [descriptor {:runtime/executable "/bin/sleep"}
          started? (promise)
          worker (Thread.
                   (fn []
                     (deliver started? true)
                     (oci-cli/run-runtime descriptor "5")))]
      (.start worker)
      (is (= true (deref started? 1000 false)))
      (.interrupt worker)
      ;; Join with 4s — comfortably under the 5s child sleep, so a dead worker
      ;; still proves the interrupt short-circuited the sleep, but with enough
      ;; headroom that thread-teardown scheduling under a saturated full-suite
      ;; run doesn't flake (1500ms raced under `poly test :all` load).
      (.join worker 4000)
      (is (false? (.isAlive worker))
          "interrupt must destroy the child and end the worker well before the 5s sleep"))))

(deftest run-runtime-returns-interrupted-result-test
  (testing "run-runtime reports interruption as process result data"
    (let [descriptor {:runtime/executable "/bin/sleep"}
          started? (promise)
          runtime-result (promise)
          worker (Thread.
                   (fn []
                     (deliver started? true)
                     (deliver runtime-result
                              (oci-cli/run-runtime descriptor "5"))))]
      (.start worker)
      (is (= true (deref started? 1000 false)))
      (.interrupt worker)
      (let [result (deref runtime-result 4000 :timeout)]
        (is (not= :timeout result))
        (is (= 130 (:exit result)))
        (is (clojure.string/includes? (:err result) "interrupted"))))))

(deftest bootstrap-workspace-command-failure-returns-error-result-test
  (testing "bootstrap command failures are returned as result data"
    (let [bootstrap-workspace! (private-fn 'bootstrap-workspace!)
          exec-results (atom [{:data {:exit-code 1
                                      :stderr "fatal: could not read password\n"}}])]
      (with-redefs [oci-cli/exec-in-container
                    (fn [_descriptor _container-id _cmd _opts]
                      (let [r (first @exec-results)]
                        (swap! exec-results rest)
                        r))]
        (let [result (bootstrap-workspace! (docker-descriptor)
                                           "container-1"
                                           "/workspace"
                                           {:repo-url "https://github.com/o/r.git"
                                            :branch "main"})]
          (is (result/err? result))
          (is (= :container-command-failed (:code (:error result))))
          (is (clojure.string/includes? (:message (:error result))
                                        "Workspace bootstrap failed"))
          (is (clojure.string/includes? (get-in result [:error :data :stderr])
                                        "could not read password")))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test)
  :leave-this-here)
