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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-security-gate-test
  "Capsule security gate: unit tests for `security-gate-check`, the gate step
   `acquire-environment!` runs on the resolved plan before launch. The digest
   resolver is injected, so these exercise the gate decision (block / warn /
   review / pass) without a container runtime."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.dag-executor.execution-plan :as execution-plan]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-digest (str "sha256:" (apply str (repeat 64 \a))))

(def ^{:stratum 0} ^:private rootless-descriptor {:runtime/capabilities #{:rootless}})

(def ^{:stratum 0} ^:private rooted-descriptor {:runtime/capabilities #{}})

(def ^{:stratum 0} ^:private base-env {:workdir "/workspace" :mounts []})

(defn- ^{:stratum 0} err-rules [r] (set (map :security/rule (get-in r [:error :data :security/findings]))))

(deftest ^{:stratum 0} release-keeps-git-remote-until-removed-test
  (testing "the recorded remote survives a release whose container removal
            failed, so the capsule's work can still be persisted, and goes
            once removal succeeds"
    (let [remote   {:repo-url "https://github.com/o/r.git"
                    :https-url "https://github.com/o/r.git" :host-kind :github}
          executor (oci-cli/map->OciCliExecutor
                    {:descriptor  {:runtime/kind :docker}
                     :git-remotes (atom {"c1" remote})})
          removed? (atom false)]
      (with-redefs [oci-cli/stop-container   (fn [& _] {:exit 0})
                    oci-cli/remove-container (fn [& _] (result/ok {:released? @removed?}))]
        (proto/release-environment! executor "c1")
        (is (= {"c1" remote} @(:git-remotes executor)))
        (reset! removed? true)
        (proto/release-environment! executor "c1")
        (is (= {} @(:git-remotes executor)))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private good-digest-fn (fn [_descriptor _image] valid-digest))

(deftest ^{:stratum 1} nil-digest-fails-closed-test
  (testing "a nil resolved digest (resolution failed) is a hard-stop, not a silent pass"
    (let [r (oci-cli/security-gate-check rootless-descriptor "img" base-env (fn [_ _] nil))]
      (is (result/err? r))
      (is (= :security-policy-violation (get-in r [:error :code])))
      (is (contains? (err-rules r) :std/runtime-require-image-digest-pin)))))

(deftest ^{:stratum 1} unpinned-digest-hard-stops-test
  (testing "a tag / non-sha256 digest is a hard-stop"
    (let [r (oci-cli/security-gate-check rootless-descriptor "img" base-env (fn [_ _] "latest"))]
      (is (result/err? r))
      (is (contains? (err-rules r) :std/runtime-require-image-digest-pin)))))

(deftest ^{:stratum 1} build-launch-plan-defaults-test
  (testing "a minimal env-config yields the untrusted keep-alive plan with no
            invented optional fields"
    (let [plan (oci-cli/build-launch-plan valid-digest {:workdir "/workspace"})]
      (is (= {:image-digest valid-digest
              :command      ["sleep" "infinity"]
              :mounts       []
              :env          {}
              :trust-level  :untrusted}
             plan))
      (is (= {:valid? true} (execution-plan/validate-plan plan))))))

(deftest ^{:stratum 1} build-launch-plan-passes-optional-fields-through-test
  (testing "optional plan fields thread from env-config when present"
    (let [plan (oci-cli/build-launch-plan
                valid-digest
                {:mounts          [{:host-path "/workspace" :container-path "/w" :read-only? false}]
                 :env             {:A "1"}
                 :trust-level     :trusted
                 :time-limit-ms   60000
                 :memory-limit-mb 1024
                 :network-profile :none
                 :secrets-refs    ["ref-1"]})]
      (is (= :trusted (:trust-level plan)))
      (is (= 60000 (:time-limit-ms plan)))
      (is (= 1024 (:memory-limit-mb plan)))
      (is (= :none (:network-profile plan)))
      (is (= ["ref-1"] (:secrets-refs plan)))
      (is (= {"A" "1"} (:env plan))
          "env keys normalize to the schema's string->string shape")
      (is (= {"PORT" "8080" "DEBUG" "true"}
             (:env (oci-cli/build-launch-plan valid-digest
                                              {:env {:PORT 8080 :DEBUG true}})))
          "non-string env values stringify to their wire form")
      (is (= [{:host-path "/workspace" :container-path "/w" :read-only? false}]
             (:mounts plan)))
      (is (= {:valid? true} (execution-plan/validate-plan plan))
          "a fully-threaded launch plan conforms to ExecutionPlanSchema"))))

(deftest ^{:stratum 1} build-launch-plan-drops-secrets-for-untrusted-test
  (testing "an :untrusted plan (the default) launches without secret-named
            env such as GH_TOKEN, so no process in the capsule inherits it,
            but keeps the agent's ANTHROPIC_API_KEY (N11 §8.1); :trusted
            keeps everything"
    (let [env {:GH_TOKEN "t" :ANTHROPIC_API_KEY "k" :LANG "C.UTF-8"}]
      (is (= {"ANTHROPIC_API_KEY" "k" "LANG" "C.UTF-8"}
             (:env (oci-cli/build-launch-plan valid-digest {:env env}))))
      (is (= {"GH_TOKEN" "t" "ANTHROPIC_API_KEY" "k" "LANG" "C.UTF-8"}
             (:env (oci-cli/build-launch-plan valid-digest
                                              {:env env :trust-level :trusted})))))))

(deftest ^{:stratum 1} build-launch-plan-keeps-agent-backend-credentials-test
  (testing "an :untrusted plan keeps the credential the agent backend reads,
            named by :agent-backend or else the configured [:llm :backend]"
    (let [env {:OPENAI_API_KEY "o" :CURSOR_API_KEY "c" :GH_TOKEN "t"}]
      (is (= {"OPENAI_API_KEY" "o"}
             (:env (oci-cli/build-launch-plan valid-digest {:env env :agent-backend :codex}))))
      (with-redefs [config/load-merged-config (constantly {:llm {:backend :cursor}})]
        (is (= {"CURSOR_API_KEY" "c"}
               (:env (oci-cli/build-launch-plan valid-digest {:env env}))))))))

(deftest ^{:stratum 1} build-launch-plan-treats-unknown-trust-as-untrusted-test
  (testing "a nil or unknown :trust-level launches as :untrusted: secrets
            are dropped and the plan records :untrusted"
    (doseq [trust-level [nil :unknown "trusted"]]
      (let [plan (oci-cli/build-launch-plan valid-digest {:env {:GH_TOKEN "t"}
                                                          :trust-level trust-level})]
        (is (= {} (:env plan)) (str "trust level " (pr-str trust-level)))
        (is (= :untrusted (:trust-level plan)))
        (is (= {:valid? true} (execution-plan/validate-plan plan)))))))

(deftest ^{:stratum 1} acquire-records-git-remote-for-persist-test
  (testing "a bootstrapped acquire records the caller's remote (not one read
            back from the capsule) under the environment id"
    (let [executor (oci-cli/map->OciCliExecutor
                    {:descriptor  {:runtime/kind :docker :runtime/capabilities #{:rootless}}
                     :image       "task-runner:latest"
                     :git-remotes (atom {})})]
      (with-redefs [oci-cli/image-exists?          (fn [_ _] true)
                    oci-cli/resolve-image-digest!  (fn [_ _] valid-digest)
                    oci-cli/create-container       (fn [_d container-name & _]
                                                     (result/ok {:container-name container-name}))
                    oci-cli/bootstrap-workspace!   (fn [& _] (result/ok {:base-branch "main"}))
                    oci-cli/container-image-digest (fn [_ _] valid-digest)]
        (let [r (proto/acquire-environment! executor "abcd1234efgh"
                                            {:workdir "/workspace"
                                             :repo-url "git@github.com:o/r.git"})]
          (is (result/ok? r))
          (is (= {"miniforge-task-abcd1234"
                  {:repo-url  "git@github.com:o/r.git"
                   :https-url "https://github.com/o/r.git"
                   :host-kind :github}}
                 @(:git-remotes executor)))))
      (testing "the environment record returned to the caller carries the
                repo URL without the user:password@ it was given"
        (with-redefs [oci-cli/image-exists?          (fn [_ _] true)
                      oci-cli/resolve-image-digest!  (fn [_ _] valid-digest)
                      oci-cli/create-container       (fn [_d container-name & _]
                                                       (result/ok {:container-name container-name}))
                      oci-cli/bootstrap-workspace!   (fn [& _] (result/ok {:base-branch "main"}))
                      oci-cli/container-image-digest (fn [_ _] valid-digest)]
          (let [r (proto/acquire-environment! executor "abcd1234efgh"
                                              {:workdir "/workspace"
                                               :repo-url "https://bob:s3cret@github.com/o/r.git"})]
            (is (= "https://github.com/o/r.git" (get-in r [:data :repo-url])))
            (is (not (str/includes? (pr-str r) "s3cret")))))))))

(deftest ^{:stratum 1} build-launch-plan-drops-git-config-tuples-test
  (testing "an :untrusted plan drops a whole GIT_CONFIG_* tuple, whose
            VALUE_n can hold an Authorization header, not just its KEY_n"
    (is (= {"LANG" "C.UTF-8"}
           (:env (oci-cli/build-launch-plan
                  valid-digest
                  {:env {"GIT_CONFIG_COUNT"   "1"
                         "GIT_CONFIG_KEY_0"   "http.https://github.com/.extraheader"
                         "GIT_CONFIG_VALUE_0" "Authorization: Basic eDp0b2s="
                         "LANG"               "C.UTF-8"}}))))))

(deftest ^{:stratum 1} build-launch-plan-strips-proxy-and-git-c-credentials-test
  (testing "an :untrusted plan keeps proxy addresses without user:password@
            and drops GIT_CONFIG_PARAMETERS, which can carry an extraheader"
    (is (= {"HTTPS_PROXY" "http://proxy:3128" "http_proxy" "proxy:8080"
            "ALL_PROXY" "socks5://proxy:1080" "NO_PROXY" "localhost"}
           (:env (oci-cli/build-launch-plan
                  valid-digest
                  {:env {"HTTPS_PROXY" "http://u:pw@proxy:3128"
                         "http_proxy" "u:pw@proxy:8080"
                         "ALL_PROXY" "socks5://proxy:1080"
                         "NO_PROXY" "localhost"
                         "GIT_CONFIG_PARAMETERS" "'http.extraheader=Authorization: Basic eDp0b2s='"}}))))))

(deftest ^{:stratum 1} acquire-launches-from-checked-plan-test
  (testing "acquire-environment! creates the container from the gate-checked
            plan: its digest as the image reference, the plan as the
            :execution-plan"
    (let [launched (atom nil)
          executor (oci-cli/map->OciCliExecutor
                    {:config     {}
                     :descriptor {:runtime/kind :docker :runtime/capabilities #{:rootless}}
                     :image      "task-runner:latest"
                     :network    nil})]
      (with-redefs [oci-cli/image-exists?          (fn [_ _] true)
                    oci-cli/resolve-image-digest!  (fn [_ _] valid-digest)
                    oci-cli/create-container       (fn [_d container-name image _workdir
                                                        _env _resources _network
                                                        & {:keys [execution-plan]}]
                                                     (reset! launched {:image image
                                                                       :plan  execution-plan})
                                                     (result/ok {:container-id "c1"
                                                                 :container-name container-name}))
                    oci-cli/container-image-digest (fn [_ _] valid-digest)]
        (let [r (proto/acquire-environment! executor "abcd1234efgh" {:workdir "/workspace"})]
          (is (result/ok? r))
          (is (= valid-digest (:image @launched)))
          (is (= valid-digest (get-in @launched [:plan :image-digest])))
          (is (= :untrusted (get-in @launched [:plan :trust-level]))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} clean-launch-passes-test
  (testing "pinned digest, rootless runtime, no mounts -> ok, decision :pass"
    (let [r (oci-cli/security-gate-check rootless-descriptor "img" base-env good-digest-fn)]
      (is (result/ok? r))
      (is (= :pass (get-in r [:data :security/decision])))
      (is (empty? (get-in r [:data :security/warnings]))))))

(deftest ^{:stratum 2} socket-mount-hard-stops-test
  (testing "a host runtime-socket mount aborts the launch"
    (let [env (assoc base-env :mounts
                     [{:host-path "/var/run/docker.sock" :container-path "/x" :read-only? false}])
          r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
      (is (result/err? r))
      (is (contains? (err-rules r) :std/runtime-no-host-docker-socket)))))

(deftest ^{:stratum 2} non-rootless-warns-does-not-block-test
  (testing "OSS default: a non-rootless runtime warns but the launch proceeds"
    (let [r (oci-cli/security-gate-check rooted-descriptor "img" base-env good-digest-fn)]
      (is (result/ok? r) "rootless is a warning in OSS, not a block")
      (is (= #{:std/runtime-require-rootless}
             (set (map :security/rule (get-in r [:data :security/warnings]))))))))

(deftest ^{:stratum 2} non-allowlisted-mount-reviews-not-blocks-test
  (testing "a host mount outside the workdir allowlist is a review, launch proceeds"
    (let [env (assoc base-env :mounts
                     [{:host-path "/Users/alice" :container-path "/home" :read-only? false}])
          r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
      (is (result/ok? r))
      (is (= :review (get-in r [:data :security/decision])))
      (is (= #{:std/runtime-restrict-host-mounts}
             (set (map :security/rule (get-in r [:data :security/review]))))))))

(deftest ^{:stratum 2} workdir-mount-is-allowlisted-test
  (testing "a mount of the sandbox workdir itself is on the allowlist -> no review"
    (let [env (assoc base-env :mounts
                     [{:host-path "/workspace" :container-path "/work" :read-only? false}])
          r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
      (is (result/ok? r))
      (is (= :pass (get-in r [:data :security/decision]))))))

(deftest ^{:stratum 2} gate-ok-carries-launch-plan-test
  (testing "a passing gate returns the checked launch plan for the launch to use"
    (let [r    (oci-cli/security-gate-check rootless-descriptor "img" base-env good-digest-fn)
          plan (get-in r [:data :launch-plan])]
      (is (result/ok? r))
      (is (= valid-digest (:image-digest plan)))
      (is (= :untrusted (:trust-level plan)))
      (is (= ["sleep" "infinity"] (:command plan))))))

(deftest ^{:stratum 2} non-identifier-env-name-hard-stops-test
  (testing "an env name such as * or GH_*, which Podman's `-e NAME` reads as
            a pattern over its own environment, stops the launch, as does
            DOCKER_CUSTOM_HEADERS, which would go inline on argv"
    (doseq [n ["*" "GH_*" "A B" "DOCKER_CUSTOM_HEADERS"]]
      (let [env (assoc base-env :env {n "x"} :trust-level :trusted)
            r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
        (is (= :security-policy-violation (get-in r [:error :code])) (pr-str n))
        (is (str/includes? (get-in r [:error :message]) (pr-str n)))))
    (is (result/ok? (oci-cli/security-gate-check
                     rootless-descriptor "img"
                     (assoc base-env :env {"_A1" "x"} :trust-level :trusted) good-digest-fn)))))
