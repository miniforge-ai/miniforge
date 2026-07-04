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
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.test :refer [deftest is testing]]))

(def ^:private valid-digest (str "sha256:" (apply str (repeat 64 \a))))
(def ^:private good-digest-fn (fn [_descriptor _image] valid-digest))
(def ^:private rootless-descriptor {:runtime/capabilities #{:rootless}})
(def ^:private rooted-descriptor {:runtime/capabilities #{}})
(def ^:private base-env {:workdir "/workspace" :mounts []})

(defn- err-rules [r] (set (map :security/rule (get-in r [:error :data :security/findings]))))

(deftest clean-launch-passes-test
  (testing "pinned digest, rootless runtime, no mounts -> ok, decision :pass"
    (let [r (oci-cli/security-gate-check rootless-descriptor "img" base-env good-digest-fn)]
      (is (result/ok? r))
      (is (= :pass (get-in r [:data :security/decision])))
      (is (empty? (get-in r [:data :security/warnings]))))))

(deftest nil-digest-fails-closed-test
  (testing "a nil resolved digest (resolution failed) is a hard-stop, not a silent pass"
    (let [r (oci-cli/security-gate-check rootless-descriptor "img" base-env (fn [_ _] nil))]
      (is (result/err? r))
      (is (= :security-policy-violation (get-in r [:error :code])))
      (is (contains? (err-rules r) :std/runtime-require-image-digest-pin)))))

(deftest unpinned-digest-hard-stops-test
  (testing "a tag / non-sha256 digest is a hard-stop"
    (let [r (oci-cli/security-gate-check rootless-descriptor "img" base-env (fn [_ _] "latest"))]
      (is (result/err? r))
      (is (contains? (err-rules r) :std/runtime-require-image-digest-pin)))))

(deftest socket-mount-hard-stops-test
  (testing "a host runtime-socket mount aborts the launch"
    (let [env (assoc base-env :mounts
                     [{:host-path "/var/run/docker.sock" :container-path "/x" :read-only? false}])
          r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
      (is (result/err? r))
      (is (contains? (err-rules r) :std/runtime-no-host-docker-socket)))))

(deftest non-rootless-warns-does-not-block-test
  (testing "OSS default: a non-rootless runtime warns but the launch proceeds"
    (let [r (oci-cli/security-gate-check rooted-descriptor "img" base-env good-digest-fn)]
      (is (result/ok? r) "rootless is a warning in OSS, not a block")
      (is (= #{:std/runtime-require-rootless}
             (set (map :security/rule (get-in r [:data :security/warnings]))))))))

(deftest non-allowlisted-mount-reviews-not-blocks-test
  (testing "a host mount outside the workdir allowlist is a review, launch proceeds"
    (let [env (assoc base-env :mounts
                     [{:host-path "/Users/alice" :container-path "/home" :read-only? false}])
          r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
      (is (result/ok? r))
      (is (= :review (get-in r [:data :security/decision])))
      (is (= #{:std/runtime-restrict-host-mounts}
             (set (map :security/rule (get-in r [:data :security/review]))))))))

(deftest workdir-mount-is-allowlisted-test
  (testing "a mount of the sandbox workdir itself is on the allowlist -> no review"
    (let [env (assoc base-env :mounts
                     [{:host-path "/workspace" :container-path "/work" :read-only? false}])
          r   (oci-cli/security-gate-check rootless-descriptor "img" env good-digest-fn)]
      (is (result/ok? r))
      (is (= :pass (get-in r [:data :security/decision]))))))
