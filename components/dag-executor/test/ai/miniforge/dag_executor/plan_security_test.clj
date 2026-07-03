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

(ns ai.miniforge.dag-executor.plan-security-test
  (:require
   [ai.miniforge.dag-executor.plan-security :as sut]
   [ai.miniforge.response.interface :as response]
   [clojure.test :refer [deftest is testing]]))

(def ^:private valid-digest
  (str "sha256:" (apply str (repeat 64 \a))))

(def ^:private base-plan
  "A clean plan: pinned digest, one allowlisted mount, no socket."
  {:image-digest    valid-digest
   :command         ["bb" "run"]
   :mounts          [{:host-path "/workspace" :container-path "/work" :read-only? false}]
   :env             {}
   :secrets-refs    []
   :network-profile :standard
   :time-limit-ms   60000
   :memory-limit-mb 512
   :trust-level     :trusted})

(def ^:private rootless-descriptor {:runtime/capabilities #{:rootless}})
(def ^:private rooted-descriptor {:runtime/capabilities #{}})
(def ^:private config {:host-path-allowlist #{"/workspace"} :rootless-action :warn})

(defn- rules-fired [findings] (set (map :security/rule findings)))

(deftest clean-plan-passes-test
  (testing "a pinned, allowlisted, rootless plan passes with no findings"
    (let [result (sut/check-plan-security base-plan rootless-descriptor config)]
      (is (response/success? result))
      (is (= :pass (get-in result [:output :security/decision])))
      (is (empty? (get-in result [:output :security/review])))
      (is (empty? (get-in result [:output :security/warnings]))))))

(deftest host-socket-mount-hard-stops-test
  (testing "a runtime-socket mount is a forbidden hard-stop, whatever the mount target"
    (let [plan   (assoc base-plan :mounts
                        [{:host-path "/var/run/docker.sock" :container-path "/x" :read-only? false}])
          result (sut/check-plan-security plan rootless-descriptor config)]
      (is (response/anomaly-map? result))
      (is (= :anomalies/forbidden (:anomaly/category result)))
      (is (= #{:std/runtime-no-host-docker-socket}
             (rules-fired (:security/findings result))))))

  (testing "podman/containerd sockets and suffix matches are caught too"
    (doseq [p ["/run/podman/podman.sock" "/some/weird/path/containerd.sock" "renamed-docker.sock"]]
      (let [plan   (assoc base-plan :mounts [{:host-path p :container-path "/x" :read-only? false}])
            result (sut/check-plan-security plan rootless-descriptor config)]
        (is (response/anomaly-map? result) (str "should hard-stop on " p))))))

(deftest unpinned-image-digest-hard-stops-test
  (testing "a tag or non-sha256 :image-digest is a forbidden hard-stop"
    (doseq [d ["task-runner:latest" "sha256:short" "" "abc123"]]
      (let [result (sut/check-plan-security (assoc base-plan :image-digest d)
                                            rootless-descriptor config)]
        (is (response/anomaly-map? result) (str "should hard-stop on digest " (pr-str d)))
        (is (contains? (rules-fired (:security/findings result))
                       :std/runtime-require-image-digest-pin)))))

  (testing "a well-formed sha256 digest passes"
    (let [result (sut/check-plan-security base-plan rootless-descriptor config)]
      (is (response/success? result)))))

(deftest non-allowlisted-host-mount-reviews-test
  (testing "a host mount outside the allowlist is a review, not a hard-stop"
    (let [plan   (assoc base-plan :mounts
                        [{:host-path "/Users/alice" :container-path "/home" :read-only? false}])
          result (sut/check-plan-security plan rootless-descriptor config)]
      (is (response/success? result) "review does not auto-block")
      (is (= :review (get-in result [:output :security/decision])))
      (is (= #{:std/runtime-restrict-host-mounts}
             (rules-fired (get-in result [:output :security/review])))))))

(deftest missing-rootless-warns-by-default-hard-stops-in-fleet-test
  (testing "OSS default: a non-rootless runtime warns, does not block"
    (let [result (sut/check-plan-security base-plan rooted-descriptor config)]
      (is (response/success? result))
      (is (= :pass (get-in result [:output :security/decision]))
          "a warning alone does not force review")
      (is (= #{:std/runtime-require-rootless}
             (rules-fired (get-in result [:output :security/warnings]))))))

  (testing "Fleet override: rootless-action :hard-stop makes it a forbidden block"
    (let [result (sut/check-plan-security base-plan rooted-descriptor
                                          (assoc config :rootless-action :hard-stop))]
      (is (response/anomaly-map? result))
      (is (contains? (rules-fired (:security/findings result))
                     :std/runtime-require-rootless)))))

(deftest unknown-action-fails-closed-test
  (testing "a finding with an unexpected action (misconfigured :rootless-action) is escalated to a hard-stop, never dropped"
    (let [result (sut/check-plan-security base-plan rooted-descriptor
                                          (assoc config :rootless-action :bogus-typo))]
      (is (response/anomaly-map? result) "unknown action must not silently pass")
      (is (= :anomalies/forbidden (:anomaly/category result)))
      (is (contains? (rules-fired (:security/findings result))
                     :std/runtime-require-rootless)))))

(deftest hard-stop-precedes-review-test
  (testing "a socket mount hard-stops and is not also double-reported as a host-mount review"
    (let [plan   (assoc base-plan :mounts
                        [{:host-path "/var/run/docker.sock" :container-path "/x" :read-only? false}])
          result (sut/check-plan-security plan rootless-descriptor config)]
      (is (response/anomaly-map? result))
      (is (= #{:std/runtime-no-host-docker-socket} (rules-fired (:security/findings result)))
          "socket mount reported once as hard-stop, not also as a review"))))

(deftest multiple-hard-stops-all-reported-test
  (testing "a socket mount AND an unpinned digest both surface in the forbidden anomaly"
    (let [plan   (assoc base-plan
                        :image-digest "latest"
                        :mounts [{:host-path "/run/docker.sock" :container-path "/x" :read-only? false}])
          result (sut/check-plan-security plan rootless-descriptor config)]
      (is (response/anomaly-map? result))
      (is (= #{:std/runtime-no-host-docker-socket :std/runtime-require-image-digest-pin}
             (rules-fired (:security/findings result)))))))
