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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-fake-runtime-test
  "OCI-CLI executor tests that run against the stand-in runtime CLI
   (`with-fake-runtime`), which logs each invocation's argv, env file and
   environment: what `ps`, the CLI's environment and the container see."
  (:require
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test-support
    :refer [basic-auth env-file-path fake-token git-config-of inspect-env-result private-fn
            recorded-remote-executor supported-kinds-under-test token-in? with-fake-runtime]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.container :as oci-ctr]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials :as oci-git]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process :as oci-run]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Credentials stay off the runtime argv and out of capsule git config
;; (N11 §2.2). These drive a stand-in runtime CLI, so they observe what the
;; real `docker` / `podman` process would receive.
;; ============================================================================
(deftest ^{:stratum 0} create-container-passes-env-through-an-env-file-test
  (doseq [kind supported-kinds-under-test]
    (testing (str "runtime " kind ": container env reaches the runtime in an
                   env file, not on argv or in the runtime CLI's environment,
                   and the file is gone afterwards")
      (with-fake-runtime kind ["GH_TOKEN"]
        (fn [descriptor calls]
          (oci-ctr/create-container descriptor "c" "img" "/w" nil nil nil
                                    :execution-plan {:env {"GH_TOKEN" fake-token}})
          (let [{:keys [argv env env-file]} (first (calls))]
            (is (not-any? token-in? argv))
            (is (= {"GH_TOKEN" fake-token} env-file))
            (is (not= fake-token (get env "GH_TOKEN")))
            (is (some-> (env-file-path argv) java.io.File. .exists not))))))))

(deftest ^{:stratum 0} exec-in-container-passes-env-through-an-env-file-test
  (testing "per-command env (release's GH_TOKEN, a HOME for the container)
            leaves the runtime CLI's own environment alone"
    (with-fake-runtime :podman ["GH_TOKEN" "HOME"]
      (fn [descriptor calls]
        (oci-ctr/exec-in-container descriptor "c" ["gh" "pr" "create"]
                                   {:env {"GH_TOKEN" fake-token
                                          "HOME"     "/capsule-home"}})
        (let [{:keys [argv env env-file]} (first (calls))]
          (is (not-any? token-in? argv))
          (is (= {"GH_TOKEN" fake-token "HOME" "/capsule-home"} env-file))
          (is (not= fake-token (get env "GH_TOKEN")))
          (is (not= "/capsule-home" (get env "HOME"))))))))

(deftest ^{:stratum 0} env-with-line-break-is-refused-test
  (testing "a value with a line break, which an env file cannot hold, is
            refused: the runtime CLI never runs, so the value reaches neither
            its argv nor its environment (where LD_PRELOAD would load it)"
    (with-fake-runtime :podman ["LD_PRELOAD"]
      (fn [descriptor calls]
        (let [r (oci-ctr/exec-in-container descriptor "c" ["true"]
                                           {:env {"LD_PRELOAD" "\n/tmp/evil.so"}})]
          (is (= 1 (get-in r [:data :exit-code])))
          (is (clojure.string/includes? (get-in r [:data :stderr]) "LD_PRELOAD"))
          (is (empty? (calls))))
        (is (result/err? (oci-ctr/create-container descriptor "c" "img" "/w" nil nil nil
                                                   :execution-plan {:env {"PEM" "a\nb"}})))
        (is (empty? (calls)))))))

(deftest ^{:stratum 0} env-name-that-is-not-an-identifier-is-refused-test
  (testing "a name with a line break, which would write a second assignment
            (here an unsanitized HTTPS_PROXY) or a bare line copying the
            runtime CLI's own variable, is refused before the env file"
    (with-fake-runtime :docker []
      (fn [descriptor calls]
        (is (result/err? (oci-ctr/create-container
                          descriptor "c" "img" "/w" nil nil nil
                          :execution-plan {:env {"SAFE\nHTTPS_PROXY" "http://u:pw@proxy:3128"}})))
        (let [r (oci-ctr/exec-in-container descriptor "c" ["true"] {:env {"GH_TOKEN\n" ""}})]
          (is (= 1 (get-in r [:data :exit-code])))
          (is (clojure.string/includes? (get-in r [:data :stderr]) "GH_TOKEN\\n")))
        (is (empty? (calls)))))))

(deftest ^{:stratum 0} bootstrap-workspace-keeps-token-out-of-git-config-test
  (testing "the clone gets the token through its env file: no runtime argv
            carries it, origin is the plain URL, no push URL is set, and no
            later command receives the credential"
    (with-fake-runtime :docker []
      (fn [descriptor calls]
        (with-redefs [oci-git/resolve-git-token (fn [_url _opts] fake-token)]
          (let [result ((private-fn 'bootstrap-workspace!)
                        descriptor "c1" "/workspace"
                        {:repo-url "git@github.com:o/r.git" :branch "main"})
                all    (calls)
                [version inspect clone & later] all]
            (is (result/ok? result))
            (is (some #{"--version"} (:argv version)))
            (is (some #{"inspect"} (:argv inspect)) "the container's own git config is read first")
            (is (empty? (:env-file version)))
            (is (some #{"clone"} (:argv clone)))
            (is (not-any? #{"--single-branch"} (:argv clone))
                "every branch is cloned, so release can compare against its base")
            (is (not-any? token-in? (mapcat :argv all)))
            (is (some #{"https://github.com/o/r.git"} (:argv clone)))
            (is (not-any? #(some #{"set-url"} (:argv %)) all))
            (is (= (str "Authorization: Basic " (basic-auth "x-access-token" fake-token))
                   (get (git-config-of (:env-file clone)) "http.https://github.com/.extraheader")))
            (is (= "1" (get (:env-file clone) "GIT_TRACE_REDACT"))
                "the clone gets the same trace redaction as every credentialed command")
            (is (seq later))
            (is (not-any? (comp seq :env-file) later))))))))

(deftest ^{:stratum 0} bootstrap-workspace-strips-url-credentials-test
  (testing "a repo URL carrying user:password@ is cloned without it; the
            password authenticates the clone through the env file"
    (with-fake-runtime :docker []
      (fn [descriptor calls]
        (with-redefs [oci-git/resolve-git-token (fn [_url _opts] nil)]
          ((private-fn 'bootstrap-workspace!)
           descriptor "c1" "/workspace"
           {:repo-url (str "https://bob:" fake-token "@github.com/o/r.git")})
          (let [[_version _inspect clone :as all] (calls)]
            (is (not-any? token-in? (mapcat :argv all)))
            (is (some #{"https://github.com/o/r.git"} (:argv clone)))
            (is (= (str "Authorization: Basic " (basic-auth "bob" fake-token))
                   (get (git-config-of (:env-file clone))
                        "http.https://github.com/.extraheader")))))))))

(deftest ^{:stratum 0} container-env-leaves-runtime-cli-variables-alone-test
  (testing "a container's DOCKER_DEFAULT_PLATFORM reaches the container but
            not the docker CLI's own environment, where it would change the
            platform `docker run` pulls and starts"
    (with-fake-runtime :docker ["DOCKER_DEFAULT_PLATFORM"]
      (fn [descriptor calls]
        (oci-ctr/create-container descriptor "c" "img" "/w" nil nil nil
                                  :execution-plan {:env {"DOCKER_DEFAULT_PLATFORM" "linux/s390x"}})
        (let [{:keys [argv env env-file]} (first (calls))]
          (is (not= "linux/s390x" (get env "DOCKER_DEFAULT_PLATFORM")))
          (is (or (some #{"DOCKER_DEFAULT_PLATFORM=linux/s390x"} argv)
                  (= "linux/s390x" (get env-file "DOCKER_DEFAULT_PLATFORM")))))))))

(deftest ^{:stratum 0} create-container-sets-untrusted-proxies-with-e-test
  (testing "an :untrusted plan's proxy variables go on argv as -e, which
            Docker's configured proxies do not replace, and not in the env
            file; a :trusted plan's, which may carry a password, stay there"
    (with-fake-runtime :docker []
      (fn [descriptor calls]
        (oci-ctr/create-container descriptor "c" "img" "/w" nil nil nil
                                  :execution-plan {:trust-level :untrusted
                                                   :env {"HTTPS_PROXY" "http://proxy:3128"
                                                         "http_proxy" "" "LANG" "C"}})
        (oci-ctr/create-container descriptor "c" "img" "/w" nil nil nil
                                  :execution-plan {:trust-level :trusted
                                                   :env {"HTTPS_PROXY" "http://u:pw@proxy:3128"}})
        (let [[untrusted trusted] (calls)]
          (is (= ["-e" "HTTPS_PROXY=http://proxy:3128" "-e" "http_proxy="]
                 (take 4 (drop-while #(not= "-e" %) (:argv untrusted)))))
          (is (= {"LANG" "C"} (:env-file untrusted)))
          (is (= {"HTTPS_PROXY" "http://u:pw@proxy:3128"} (:env-file trusted)))
          (is (not-any? #(clojure.string/includes? % "pw@") (:argv trusted))))))))

(deftest ^{:stratum 0} credentialed-exec-keeps-a-multi-line-container-git-config-test
  (testing "a trusted image's multi-line GIT_CONFIG_VALUE_0 stays in the
            container env: the credentialed exec is not refused for its line
            break, and the env file holds only the credential's entries,
            numbered after the container's"
    (with-fake-runtime :docker []
      (fn [descriptor calls]
        (with-redefs [oci-git/resolve-git-token (fn [_url _opts] fake-token)
                      oci-run/run-runtime-timed
                      (fn [_ms _d & _]
                        (inspect-env-result "GIT_CONFIG_COUNT=1"
                                            "GIT_CONFIG_KEY_0=http.sslCAInfo"
                                            "GIT_CONFIG_VALUE_0=-----BEGIN CERT\nabc"))]
          (let [r        (proto/execute! (assoc (recorded-remote-executor) :descriptor descriptor)
                                         "c1" ["git" "push"] {:git-credentials? true})
                env-file (:env-file (first (calls)))]
            (is (zero? (get-in r [:data :exit-code])))
            (is (not (contains? env-file "GIT_CONFIG_VALUE_0")))
            (is (= "core.hooksPath" (get env-file "GIT_CONFIG_KEY_1")))))))))
