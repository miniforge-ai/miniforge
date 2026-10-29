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
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [clojure.string]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} cmd-contains?
  "True if cmd (string or arg vector) contains the substring s.
   Vector commands are joined with spaces before the check so that
   multi-word substrings like \"git fetch\" match across elements."
  [cmd s]
  (clojure.string/includes?
   (if (string? cmd) cmd (clojure.string/join " " cmd))
   s))

;; Private fn accessor helper
(defn- ^{:stratum 0} private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli sym)))

;; Default descriptor used by tests that exercise CLI argument shaping
;; rather than runtime selection. `make-descriptor` defaults to :docker.
(defn- ^{:stratum 0} docker-descriptor
  ([] (descriptor/make-descriptor {}))
  ([opts] (descriptor/make-descriptor opts)))

(defn- ^{:stratum 0} podman-descriptor
  "For tests describing Podman-specific behaviour (bare-hex image IDs),
   so the setup matches the runtime named in the test."
  []
  (descriptor/make-descriptor {:runtime-kind :podman}))

;; Phase 2: argument-construction tests run against every supported kind so
;; a Podman regression in flag shaping shows up at unit-test time.
(def ^{:stratum 0} ^:private supported-kinds-under-test
  [:docker :podman])

(defn- ^{:stratum 0} descriptor-for-kind
  [kind]
  (descriptor/make-descriptor {:runtime-kind kind}))

(def ^{:stratum 0} ^:private fake-token "tok-FAKE-0123456789")

(defn- ^{:stratum 0} basic-auth [user token]
  (.encodeToString (java.util.Base64/getEncoder)
                   (.getBytes (str user ":" token) "UTF-8")))

(defn- ^{:stratum 0} parse-fake-runtime-log [lines]
  (->> lines
       (partition-by #(= "END" %))
       (remove #(= ["END"] %))
       (mapv (fn [call]
               {:argv (vec (keep #(when (.startsWith ^String % "ARG ") (subs % 4)) call))
                :env  (into {} (keep #(when (.startsWith ^String % "ENV ")
                                        (clojure.string/split (subs % 4) #"=" 2)))
                            call)}))))

;; ============================================================================
;; descriptor — basic construction
;; ============================================================================
(deftest ^{:stratum 0} descriptor-defaults-to-docker-test
  (testing "make-descriptor defaults to :docker"
    (let [d (descriptor/make-descriptor {})]
      (is (= :docker (descriptor/kind d)))
      (is (= "docker" (descriptor/executable d))))))

(deftest ^{:stratum 0} descriptor-honors-explicit-executable-test
  (testing "make-descriptor uses :executable when provided"
    (let [d (descriptor/make-descriptor {:executable "/opt/homebrew/bin/docker"})]
      (is (= "/opt/homebrew/bin/docker" (descriptor/executable d))))))

(deftest ^{:stratum 0} descriptor-honors-legacy-docker-path-test
  (testing "make-descriptor falls back to :docker-path for :docker kind"
    (let [d (descriptor/make-descriptor {:docker-path "/usr/bin/docker"})]
      (is (= "/usr/bin/docker" (descriptor/executable d))))))

(deftest ^{:stratum 0} descriptor-accepts-podman-test
  (testing "make-descriptor builds a :podman descriptor in Phase 2"
    (let [d (descriptor/make-descriptor {:runtime-kind :podman})]
      (is (= :podman (descriptor/kind d)))
      (is (= "podman" (descriptor/executable d)))
      (is (descriptor/capable? d :rootless))
      (is (descriptor/capable? d :oci-images)))))

(deftest ^{:stratum 0} descriptor-rejects-nerdctl-as-unsupported-test
  (testing "make-descriptor throws :runtime/unsupported for :nerdctl (Future)"
    (try
      (descriptor/make-descriptor {:runtime-kind :nerdctl})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :nerdctl (-> e ex-data :runtime/unsupported)))
        (is (contains? (-> e ex-data :runtime/supported-kinds) :docker))
        (is (contains? (-> e ex-data :runtime/supported-kinds) :podman))))))

(deftest ^{:stratum 0} descriptor-rejects-unknown-kind-test
  (testing "make-descriptor throws :runtime/unknown-kind for unknown kinds"
    (try
      (descriptor/make-descriptor {:runtime-kind :unknown-runtime})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unknown-runtime (-> e ex-data :runtime/unknown-kind)))))))

(deftest ^{:stratum 0} descriptor-validation-result-returns-anomaly-test
  (testing "constructed descriptor schema drift returns a canonical anomaly"
    (let [result (@#'descriptor/validate-descriptor-result
                  {:runtime/kind :docker})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :runtime/descriptor
             (get-in result [:anomaly/data :anomaly/schema]))))))

(deftest ^{:stratum 0} persist-workspace-no-changes-test
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
;; create-container --stop-timeout (N11 §2.2)
;; ============================================================================
(defn- ^{:stratum 0} capture-create-container-args
  "Run create-container with `oci-cli/run-runtime-process` stubbed and return
   the captured argv. Keeps the parameterized stop-timeout tests focused on
   the assertions rather than mock plumbing."
  [descriptor & {:as create-opts}]
  (let [captured-args (atom nil)
        stub          (fn [_descriptor args & _]
                        (reset! captured-args (vec args))
                        {:exit 0 :out "container-id-123\n" :err ""})]
    (with-redefs [oci-cli/run-runtime-process stub]
      (apply oci-cli/create-container
             descriptor "test-ctr" "alpine" "/workspace" nil nil nil
             (mapcat identity create-opts))
      @captured-args)))

;; ============================================================================
;; acquisition timeout — production-side guard against stuck Docker
;; ============================================================================
;;
;; The 2026-05-16 dogfood hung 33+ minutes when the Docker daemon stalled
;; mid acquire; PR #895 mocked the test side, this guard fails fast in
;; production. Tests target the with-acquisition-timeout helper directly so
;; they stay deterministic without a real Docker daemon.
(deftest ^{:stratum 0} with-acquisition-timeout-returns-body-result-on-success-test
  (testing "body that returns in time passes its result through unchanged"
    (let [ok-result {:ok? true :data {:environment-id "env-1"}}]
      (is (= ok-result
             (oci-cli/with-acquisition-timeout 5000 (fn [] ok-result)))))))

(deftest ^{:stratum 0} with-acquisition-timeout-fires-on-deadline-test
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

(deftest ^{:stratum 0} with-acquisition-timeout-nil-or-nonpositive-disables-guard-test
  (testing "nil / 0 / negative timeout bypasses the deadline check"
    (doseq [t [nil 0 -1]]
      (is (= :body-ran
             (oci-cli/with-acquisition-timeout t (fn [] :body-ran)))
          (str "timeout " (pr-str t) " must run the body without the guard")))))

(deftest ^{:stratum 0} with-acquisition-timeout-disabled-body-exception-returns-error-result-test
  (testing "disabled deadline still returns body exceptions as failure data"
    (let [result (oci-cli/with-acquisition-timeout
                   nil
                   (fn [] (throw (ex-info "boom" {:tag :disabled-timeout}))))]
      (is (result/err? result))
      (is (= :acquire-failed (:code (:error result))))
      (is (= :disabled-timeout
             (get-in result [:error :data :exception-data :tag]))))))

(deftest ^{:stratum 0} with-acquisition-timeout-cancels-the-future-on-timeout-test
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

(deftest ^{:stratum 0} with-acquisition-timeout-body-exception-returns-error-result-test
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

(deftest ^{:stratum 0} run-runtime-interrupt-destroys-child-process-test
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

(deftest ^{:stratum 0} run-runtime-returns-interrupted-result-test
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

(defn- ^{:stratum 0} recorded-remote-executor
  "An executor that bootstrapped `c1` from https://github.com/o/r.git."
  []
  (doto (oci-cli/create-docker-executor {:image "test:latest"})
    (-> :git-remotes (reset! {"c1" {:repo-url  "https://github.com/o/r.git"
                                    :https-url "https://github.com/o/r.git"
                                    :host-kind :github}}))))

(defn- ^{:stratum 0} credentialed-calls
  "The [cmd opts] in `calls` that carried an env."
  [calls]
  (filter (fn [[_ opts]] (:env opts)) calls))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} token-in? [s]
  (or (clojure.string/includes? s fake-token)
      (clojure.string/includes? s (basic-auth "x-access-token" fake-token))))

(defn- ^{:stratum 1} with-fake-runtime
  "Run (f descriptor calls) against a stand-in runtime CLI for `kind`: a
   script that logs each invocation's argv and the values of `env-names` in
   its own environment, then prints a fake id. `calls` returns the
   invocations as [{:argv [...] :env {name value}}] — what `ps` and the
   CLI's environment would show."
  [kind env-names f]
  (let [dir    (.toFile (java.nio.file.Files/createTempDirectory
                         "fake-runtime" (make-array java.nio.file.attribute.FileAttribute 0)))
        log    (java.io.File. dir "calls.log")
        script (java.io.File. dir "runtime")]
    (try
      (spit script
            (str "#!/bin/sh\n"
                 "log='" log "'\n"
                 "for a in \"$@\"; do printf 'ARG %s\\n' \"$a\" >> \"$log\"; done\n"
                 (apply str (for [n env-names]
                              (str "[ -n \"${" n "+x}\" ] && printf 'ENV " n "=%s\\n' \"$" n "\" >> \"$log\"\n")))
                 "echo END >> \"$log\"\n"
                 "echo abc123\n"))
      (.setExecutable script true)
      (f (descriptor/make-descriptor {:runtime-kind kind :executable (str script)})
         #(if (.exists log) (parse-fake-runtime-log (clojure.string/split-lines (slurp log))) []))
      (finally
        (run! #(.delete ^java.io.File %) (reverse (file-seq dir)))))))

;; ============================================================================
;; sanitize-token
;; ============================================================================
(deftest ^{:stratum 1} sanitize-token-github-test
  (testing "sanitizes GitHub x-access-token"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "https://x-access-token:***@github.com/o/r.git"
             (sanitize "https://x-access-token:ghp_abc123@github.com/o/r.git"))))))

(deftest ^{:stratum 1} sanitize-token-gitlab-test
  (testing "sanitizes GitLab oauth2 token"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "https://oauth2:***@gitlab.com/g/p.git"
             (sanitize "https://oauth2:glpat-xyz@gitlab.com/g/p.git"))))))

(deftest ^{:stratum 1} sanitize-token-no-token-test
  (testing "passes through strings without tokens"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "git clone https://github.com/o/r.git"
             (sanitize "git clone https://github.com/o/r.git"))))))

;; ============================================================================
;; git-credential-env — per-command git auth (N11 §2.2)
;; ============================================================================
(deftest ^{:stratum 1} git-credential-env-test
  (let [cred-env (private-fn 'git-credential-env)
        auth     {:user "x-access-token" :token "tok"}]
    (testing "an Authorization header scoped to the remote's host"
      (is (= {"GIT_CONFIG_COUNT"    "1"
              "GIT_CONFIG_KEY_0"    "http.https://github.com/.extraheader"
              "GIT_CONFIG_VALUE_0"  (str "Authorization: Basic "
                                         (basic-auth "x-access-token" "tok"))
              "GIT_TERMINAL_PROMPT" "0"}
             (cred-env "https://github.com/o/r.git" auth))))
    (testing "an http remote gets its header too"
      (is (some #{(str "Authorization: Basic " (basic-auth "x-access-token" "tok"))}
                (vals (cred-env "http://git.example.com/r.git" auth)))))
    (testing "no token, or a URL that is not http(s), yields no credential"
      (is (= {} (cred-env "https://github.com/o/r.git" nil)))
      (is (= {} (cred-env "git@github.com:o/r.git" auth))))))

(deftest ^{:stratum 1} git-remote-test
  (let [git-remote (private-fn 'git-remote)]
    (testing "an explicit nil :host-kind is inferred, like an absent one"
      (is (= :github (:host-kind (git-remote {:repo-url "https://github.com/o/r.git"
                                              :host-kind nil})))))
    (testing "userinfo is stripped from both URLs and kept as the credential"
      (is (= {:repo-url  "https://github.com/o/r.git"
              :https-url "https://github.com/o/r.git"
              :host-kind :github
              :url-auth  {:user "bob" :token "s3cr%t"}}
             (git-remote {:repo-url "https://bob:s3cr%25t@github.com/o/r.git"}))))
    (testing "a username alone is stripped and carries no credential"
      (is (= "https://github.com/o/r.git"
             (:https-url (git-remote {:repo-url "https://bob@github.com/o/r.git"}))))
      (is (nil? (:url-auth (git-remote {:repo-url "https://bob@github.com/o/r.git"})))))))

(deftest ^{:stratum 1} git-auth-test
  (let [git-auth (private-fn 'git-auth)]
    (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] "tok")]
      (testing "GitLab pairs a resolved token with oauth2, others with x-access-token"
        (is (= {:user "oauth2" :token "tok"} (git-auth {:host-kind :gitlab})))
        (is (= {:user "x-access-token" :token "tok"} (git-auth {:host-kind :github}))))
      (testing "a credential from the URL wins over the resolved token"
        (is (= {:user "bob" :token "pw"}
               (git-auth {:host-kind :github :url-auth {:user "bob" :token "pw"}})))))
    (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] "tok")]
      (testing "a resolved token is not sent over plain http"
        (is (nil? (git-auth {:host-kind :generic :https-url "http://git.example.com/r.git"})))))
    (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] nil)]
      (is (nil? (git-auth {:host-kind :github}))))))

;; ============================================================================
;; image-exists? (public)
;; ============================================================================
(deftest ^{:stratum 1} image-exists-nonexistent-test
  (testing "image-exists? returns false for nonexistent image"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 1 :out "" :err "No such image"})]
      (is (false? (oci-cli/image-exists? (docker-descriptor)
                                         "nonexistent/image:never"))))))

;; ============================================================================
;; create-container — execution-plan interactions
;; ============================================================================
(deftest ^{:stratum 1} create-container-plan-without-profile-does-not-use-legacy-network-test
  (testing "an execution plan is authoritative even when it omits
            :network-profile"
    (let [captured (atom nil)]
      (with-redefs [oci-cli/run-runtime-process
                    (fn [_d args & _]
                      (reset! captured (vec args))
                      {:exit 0 :out "cid\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "c" "img" "/w" {} nil "bridge"
                                  :execution-plan {:image-digest "sha256:x" :mounts []})
        (let [args @captured]
          (is (not (some #{"--network"} args)))
          (is (not (some #{"bridge"} args))))))))

(deftest ^{:stratum 1} create-container-plan-network-profile-overrides-legacy-test
  (testing "an execution-plan WITH :network-profile drops the legacy network
            argument (profile drives networking via build-security-args)"
    (let [captured (atom nil)]
      (with-redefs [oci-cli/run-runtime-process
                    (fn [_d args & _]
                      (reset! captured (vec args))
                      {:exit 0 :out "cid\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "c" "img" "/w" {} nil "bridge"
                                  :execution-plan {:image-digest "sha256:x" :mounts []
                                                   :network-profile :none})
        (let [args @captured]
          (is (not (some #{"--network"} args)))
          (is (some #{"--network=none"} args)))))))

(deftest ^{:stratum 1} create-container-plan-memory-replaces-registry-default-test
  (testing "a plan memory limit emits one authoritative --memory argument"
    (let [captured (atom nil)]
      (with-redefs [oci-cli/run-runtime-process
                    (fn [_d args & _]
                      (reset! captured (vec args))
                      {:exit 0 :out "cid\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "c" "img" "/w" {} nil nil
                                  :execution-plan {:image-digest "sha256:x"
                                                   :mounts []
                                                   :memory-limit-mb 768})
        (is (= 1 (count (filter #{"--memory"} @captured))))
        (is (some #{"768m"} @captured))))))

(deftest ^{:stratum 1} create-container-plan-env-replaces-legacy-env-test
  (testing "plan env is authoritative when present; partial plans retain legacy env"
    (let [captured (atom nil)
          run! (fn [plan]
                 (with-redefs [oci-cli/run-runtime-process
                               (fn [_d args & {:keys [env]}]
                                 (reset! captured {:args (vec args) :env env})
                                 {:exit 0 :out "cid\n" :err ""})]
                   (oci-cli/create-container
                    (docker-descriptor) "c" "img" "/w" {"SOURCE" "legacy"} nil nil
                    :execution-plan plan)
                   @captured))]
      (let [{:keys [args env]} (run! {:env {"SOURCE" "plan"}})]
        (is (some #{"SOURCE"} args))
        (is (= {"SOURCE" "plan"} env)))
      (is (= {"SOURCE" "legacy"} (:env (run! {})))))))

(deftest ^{:stratum 1} create-container-runs-plan-command-test
  (testing "the container command comes from the plan's :command, defaulting
            to the keep-alive when absent"
    (let [captured (atom nil)]
      (with-redefs [oci-cli/run-runtime-process
                    (fn [_d args & _]
                      (reset! captured (vec args))
                      {:exit 0 :out "cid\n" :err ""})]
        (oci-cli/create-container (docker-descriptor) "c" "img" "/w" {} nil nil
                                  :execution-plan {:image-digest "sha256:x" :mounts []
                                                   :command ["bb" "run" "task"]})
        (is (= ["bb" "run" "task"] (subvec @captured (- (count @captured) 3))))
        (oci-cli/create-container (docker-descriptor) "c" "img" "/w" {} nil nil)
        (is (= ["sleep" "infinity"] (subvec @captured (- (count @captured) 2))))))))

;; ============================================================================
;; container-image-digest
;; ============================================================================
(deftest ^{:stratum 1} container-image-digest-returns-sha-on-success-test
  (testing "returns trimmed digest string when inspect succeeds"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 0 :out "sha256:abc123def456\n" :err ""})]
      (is (= "sha256:abc123def456"
             (oci-cli/container-image-digest (docker-descriptor) "my-container"))))))

(deftest ^{:stratum 1} container-image-digest-normalizes-podman-bare-hex-test
  (testing "Podman prints the image ID as bare 64-hex; the digest is
            normalized to the sha256:-prefixed form the gate expects"
    (let [hex (apply str (repeat 64 \a))]
      (with-redefs [oci-cli/run-runtime
                    (fn [_d & _args]
                      {:exit 0 :out (str hex "\n") :err ""})]
        (is (= (str "sha256:" hex)
               (oci-cli/container-image-digest (podman-descriptor) "my-container")))))))

(deftest ^{:stratum 1} image-config-digest-normalizes-podman-bare-hex-test
  (testing "image-config-digest applies the same normalization on image IDs"
    (let [hex (apply str (repeat 64 \a))]
      (with-redefs [oci-cli/run-runtime
                    (fn [_d & _args]
                      {:exit 0 :out (str hex "\n") :err ""})]
        (is (= (str "sha256:" hex)
               (oci-cli/image-config-digest (podman-descriptor) "img:tag")))))))

(deftest ^{:stratum 1} image-config-digest-passes-docker-prefixed-form-through-test
  (testing "Docker's already-prefixed sha256:<hex> ID is returned unchanged"
    (let [digest (str "sha256:" (apply str (repeat 64 \b)))]
      (with-redefs [oci-cli/run-runtime
                    (fn [_d & _args]
                      {:exit 0 :out (str digest "\n") :err ""})]
        (is (= digest
               (oci-cli/image-config-digest (docker-descriptor) "img:tag")))))))

(deftest ^{:stratum 1} container-image-digest-returns-nil-on-nonzero-exit-test
  (testing "returns nil when inspect exits non-zero"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 1 :out "" :err "No such container"})]
      (is (nil? (oci-cli/container-image-digest (docker-descriptor) "missing"))))))

(deftest ^{:stratum 1} container-image-digest-returns-nil-on-empty-output-test
  (testing "returns nil when inspect output is blank"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    {:exit 0 :out "  \n" :err ""})]
      (is (nil? (oci-cli/container-image-digest (docker-descriptor) "empty-out"))))))

(deftest ^{:stratum 1} container-image-digest-returns-nil-on-exception-test
  (testing "returns nil when run-runtime throws"
    (with-redefs [oci-cli/run-runtime
                  (fn [_d & _args]
                    (throw (ex-info "Runtime not found" {})))]
      (is (nil? (oci-cli/container-image-digest (docker-descriptor) "any-container"))))))

;; ============================================================================
;; persist-workspace!
;; ============================================================================
(deftest ^{:stratum 1} persist-workspace-with-changes-test
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
          (is (some #(cmd-contains? % "-c commit.gpgsign=false commit") @commands)
              "the persist commit runs unsigned")
          (is (some #(cmd-contains? % "git push") @commands)))))))

;; ============================================================================
;; restore-workspace!
;; ============================================================================
(deftest ^{:stratum 1} restore-workspace-test
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
          (is (some #(cmd-contains? % "git fetch") @commands))
          (is (some #(cmd-contains? % "git checkout") @commands)))))))

(deftest ^{:stratum 1} create-container-stop-timeout-test
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
(deftest ^{:stratum 1} executor-type-from-descriptor-test
  (testing "OciCliExecutor reports its descriptor's runtime kind"
    (doseq [kind supported-kinds-under-test]
      (testing (str "kind " kind)
        (let [exec (oci-cli/create-oci-cli-executor
                    {:runtime-kind kind :image "test:latest"})]
          (is (= kind (proto/executor-type exec))))))))

;; ============================================================================
;; run-runtime-timed / run-runtime-process-timed — per-subprocess deadline
;; ============================================================================
;;
;; Covers the 2026-07-18 fix: `execute!`, `release-environment!`, `copy-to!`,
;; and `copy-from!` now route through bounded waitFor rather than parking
;; the JVM thread indefinitely if Docker stalls (2026-05-16 dogfood follow-up).
(deftest ^{:stratum 1} run-runtime-timed-fires-on-deadline-test
  (testing "returns exit 124 when the subprocess exceeds the per-op timeout"
    (let [run-timed  (private-fn 'run-runtime-timed)
          descriptor {:runtime/executable "/bin/sleep"}
          result     (run-timed 100 descriptor "10")]
      (is (= 124 (:exit result))
          "exit 124 mirrors the GNU timeout(1) convention for a killed-by-timeout command")
      (is (clojure.string/includes? (:err result) "100")
          "error message includes the timeout-ms so operators can distinguish from a real failure"))))

(deftest ^{:stratum 1} run-runtime-process-timed-fires-on-deadline-test
  (testing "returns exit 124 with empty byte arrays when the subprocess exceeds the timeout"
    (let [run-timed  (private-fn 'run-runtime-process-timed)
          descriptor {:runtime/executable "/bin/sleep"}
          result     (run-timed 100 descriptor ["10"])]
      (is (= 124 (:exit result)))
      (is (= "" (:out result)))
      (is (clojure.string/includes? (:err result) "100"))
      (is (zero? (alength ^bytes (:out-bytes result))))
      (is (zero? (alength ^bytes (:err-bytes result)))))))

(deftest ^{:stratum 1} run-runtime-timed-nil-timeout-is-unbounded-test
  (testing "nil timeout-ms disables the deadline (backward-compatible with run-runtime)"
    (let [run-timed  (private-fn 'run-runtime-timed)
          descriptor {:runtime/executable "/bin/echo"}
          result     (run-timed nil descriptor "hello")]
      (is (= 0 (:exit result)))
      (is (clojure.string/includes? (:out result) "hello")))))

(deftest ^{:stratum 1} bootstrap-workspace-command-failure-returns-error-result-test
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

(deftest ^{:stratum 1} bootstrap-workspace-authenticates-http-userinfo-test
  (testing "an http URL's user:password@ is stripped from the clone URL and
            still authenticates the clone"
    (let [envs (atom [])]
      (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] nil)
                    oci-cli/exec-in-container (fn [_d _c cmd opts]
                                                (swap! envs conj [cmd (:env opts)])
                                                {:data {:exit-code 0 :stdout ""}})]
        ((private-fn 'bootstrap-workspace!) (docker-descriptor) "c1" "/workspace"
                                            {:repo-url "http://bob:pw@git.example.com/r.git"})
        (let [[cmd env] (first (filter #(some #{"clone"} (first %)) @envs))]
          (is (some #{"http://git.example.com/r.git"} cmd))
          (is (some #{(str "Authorization: Basic " (basic-auth "bob" "pw"))} (vals env))))))))

(deftest ^{:stratum 1} persist-workspace-authenticates-push-only-test
  (testing "with a recorded remote, only the push gets the credential, with
            hooks off, and it pushes to the recorded URL rather than the
            capsule's origin; add/commit (which run workspace hooks) never
            see it"
    (let [executor (recorded-remote-executor)
          calls    (atom [])]
      (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] fake-token)
                    oci-cli/stop-container    (fn [& _] {:exit 0})
                    oci-cli/remove-container  (fn [& _] (result/ok {:released? true}))
                    oci-cli/exec-in-container
                    (fn [_d _id cmd opts]
                      (swap! calls conj [cmd opts])
                      {:data {:exit-code 0
                              :stdout (if (= cmd "git status --porcelain") " M f" "sha")}})]
        (is (true? (get-in (proto/persist-workspace! executor "c1" {:branch "task/x"})
                           [:data :persisted?])))
        (let [[[cmd opts] & more] (credentialed-calls @calls)]
          (is (empty? more))
          (is (= ["git" "-c" "core.hooksPath=/dev/null" "push" "https://github.com/o/r.git"
                  "HEAD:task/x" "--force"]
                 cmd))
          (is (= (str "Authorization: Basic " (basic-auth "x-access-token" fake-token))
                 (get-in opts [:env "GIT_CONFIG_VALUE_0"]))))
        (proto/release-environment! executor "c1")
        (is (= {} @(:git-remotes executor)))))))

(deftest ^{:stratum 1} restore-workspace-fetches-recorded-url-test
  (testing "with a recorded remote, restore fetches the recorded URL into
            origin's tracking ref, with the credential on the fetch alone"
    (let [calls (atom [])]
      (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] fake-token)
                    oci-cli/exec-in-container
                    (fn [_d _id cmd opts]
                      (swap! calls conj [cmd opts])
                      {:data {:exit-code 0 :stdout "sha"}})]
        (is (true? (get-in (proto/restore-workspace! (recorded-remote-executor) "c1"
                                                     {:branch "task/x"})
                           [:data :restored?])))
        (is (= [["git" "-c" "core.hooksPath=/dev/null" "fetch" "https://github.com/o/r.git"
                 "+refs/heads/task/x:refs/remotes/origin/task/x"]]
               (map first (credentialed-calls @calls))))))))

(deftest ^{:stratum 1} execute-git-credentials-test
  (testing ":git-credentials? adds the recorded remote's credential to the
            caller's env; without it the command gets only the caller's env"
    (let [seen (atom [])]
      (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] fake-token)
                    oci-cli/exec-in-container (fn [_d _id _cmd opts] (swap! seen conj opts)
                                                (result/ok {:exit-code 0}))]
        (let [executor (recorded-remote-executor)]
          (proto/execute! executor "c1" ["git" "push"] {:git-credentials? true
                                                        :env {"GH_TOKEN" "t"}})
          (proto/execute! executor "c1" ["git" "push"] {:env {"GH_TOKEN" "t"}}))
        (let [[with-creds without] @seen]
          (is (= "t" (get-in with-creds [:env "GH_TOKEN"])))
          (is (contains? (:env with-creds) "GIT_CONFIG_VALUE_0"))
          (is (= {"GH_TOKEN" "t"} (:env without))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} git-credential-env-is-read-by-git-test
  (testing "git takes the header from the env alone; the repo config file
            never holds it"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "cred-env" (make-array java.nio.file.attribute.FileAttribute 0)))
          env ((private-fn 'git-credential-env) "https://github.com/o/r.git"
                                                {:user "x-access-token" :token fake-token})
          git (fn [& args]
                (apply shell/sh "git" "-C" (str dir)
                       (concat args [:env (merge (into {} (System/getenv)) env)])))]
      (try
        (git "init" "-q")
        (is (= (get env "GIT_CONFIG_VALUE_0")
               (clojure.string/trim
                (:out (git "config" "--get" "http.https://github.com/.extraheader")))))
        (is (not (token-in? (slurp (java.io.File. dir ".git/config")))))
        (finally
          (run! #(.delete ^java.io.File %) (reverse (file-seq dir))))))))

;; ============================================================================
;; Credentials stay off the runtime argv and out of capsule git config
;; (N11 §2.2). These drive a stand-in runtime CLI, so they observe what the
;; real `docker` / `podman` process would receive.
;; ============================================================================
(deftest ^{:stratum 2} create-container-passes-env-values-by-name-test
  (doseq [kind supported-kinds-under-test]
    (testing (str "runtime " kind ": container env is `-e NAME` on argv, the
                   value only in the runtime CLI's environment")
      (with-fake-runtime kind ["GH_TOKEN"]
        (fn [descriptor calls]
          (oci-cli/create-container descriptor "c" "img" "/w" nil nil nil
                                    :execution-plan {:env {"GH_TOKEN" fake-token}})
          (let [{:keys [argv env]} (first (calls))]
            (is (some #{"GH_TOKEN"} argv))
            (is (not-any? token-in? argv))
            (is (= fake-token (get env "GH_TOKEN")))))))))

(deftest ^{:stratum 2} exec-in-container-passes-env-values-by-name-test
  (testing "per-command env (release's GH_TOKEN) stays off argv; a name the
            runtime CLI reads itself goes inline so it cannot reconfigure
            the CLI"
    (with-fake-runtime :podman ["GH_TOKEN" "HOME"]
      (fn [descriptor calls]
        (oci-cli/exec-in-container descriptor "c" ["gh" "pr" "create"]
                                   {:env {"GH_TOKEN" fake-token "HOME" "/capsule-home"}})
        (let [{:keys [argv env]} (first (calls))]
          (is (some #{"GH_TOKEN"} argv))
          (is (not-any? token-in? argv))
          (is (= fake-token (get env "GH_TOKEN")))
          (is (some #{"HOME=/capsule-home"} argv))
          (is (not= "/capsule-home" (get env "HOME"))))))))

(deftest ^{:stratum 2} bootstrap-workspace-keeps-token-out-of-git-config-test
  (testing "the clone gets the token through its environment: no runtime
            argv carries it, origin is the plain URL, no push URL is set,
            and no later command receives the credential"
    (with-fake-runtime :docker ["GIT_CONFIG_KEY_0" "GIT_CONFIG_VALUE_0"]
      (fn [descriptor calls]
        (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] fake-token)]
          (let [result ((private-fn 'bootstrap-workspace!)
                        descriptor "c1" "/workspace"
                        {:repo-url "git@github.com:o/r.git" :branch "main"})
                all    (calls)
                [clone & later] all]
            (is (result/ok? result))
            (is (some #{"clone"} (:argv clone)))
            (is (not-any? token-in? (mapcat :argv all)))
            (is (some #{"https://github.com/o/r.git"} (:argv clone)))
            (is (not-any? #(some #{"set-url"} (:argv %)) all))
            (is (= "http.https://github.com/.extraheader"
                   (get-in clone [:env "GIT_CONFIG_KEY_0"])))
            (is (= (str "Authorization: Basic " (basic-auth "x-access-token" fake-token))
                   (get-in clone [:env "GIT_CONFIG_VALUE_0"])))
            (is (seq later))
            (is (not-any? #(get-in % [:env "GIT_CONFIG_VALUE_0"]) later))))))))

(deftest ^{:stratum 2} bootstrap-workspace-strips-url-credentials-test
  (testing "a repo URL carrying user:password@ is cloned without it; the
            password authenticates the clone through its environment"
    (with-fake-runtime :docker ["GIT_CONFIG_VALUE_0"]
      (fn [descriptor calls]
        (with-redefs [oci-cli/resolve-git-token (fn [_url _opts] nil)]
          ((private-fn 'bootstrap-workspace!)
           descriptor "c1" "/workspace"
           {:repo-url (str "https://bob:" fake-token "@github.com/o/r.git")})
          (let [[clone :as all] (calls)]
            (is (not-any? token-in? (mapcat :argv all)))
            (is (some #{"https://github.com/o/r.git"} (:argv clone)))
            (is (= (str "Authorization: Basic " (basic-auth "bob" fake-token))
                   (get-in clone [:env "GIT_CONFIG_VALUE_0"])))))))))

(deftest ^{:stratum 2} container-env-leaves-runtime-cli-variables-alone-test
  (testing "a container's DOCKER_DEFAULT_PLATFORM reaches the container but
            not the docker CLI's own environment, where it would change the
            platform `docker run` pulls and starts"
    (with-fake-runtime :docker ["DOCKER_DEFAULT_PLATFORM"]
      (fn [descriptor calls]
        (oci-cli/create-container descriptor "c" "img" "/w" nil nil nil
                                  :execution-plan {:env {"DOCKER_DEFAULT_PLATFORM" "linux/s390x"}})
        (let [{:keys [argv env env-file]} (first (calls))]
          (is (not= "linux/s390x" (get env "DOCKER_DEFAULT_PLATFORM")))
          (is (or (some #{"DOCKER_DEFAULT_PLATFORM=linux/s390x"} argv)
                  (= "linux/s390x" (get env-file "DOCKER_DEFAULT_PLATFORM")))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test)
  :leave-this-here)
