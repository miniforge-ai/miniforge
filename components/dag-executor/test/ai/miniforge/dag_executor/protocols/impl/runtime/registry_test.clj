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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.registry-test
  "Tests for the runtime registry — EDN load, kind lookups, flag fallback."
  (:require
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]))

(deftest registry-known-kinds-test
  (testing "registry exposes :docker, :podman, :nerdctl as known kinds"
    (let [known (registry/known-kinds)]
      (is (contains? known :docker))
      (is (contains? known :podman))
      (is (contains? known :nerdctl)))))

(deftest registry-supported-kinds-phase-2-test
  (testing "Phase 2 supports :docker and :podman; :nerdctl is future"
    (let [supported (registry/supported-kinds)]
      (is (contains? supported :docker))
      (is (contains? supported :podman))
      (is (not (contains? supported :nerdctl))))))

(deftest registry-known?-test
  (testing "known? distinguishes registry membership from supported?"
    (is (registry/known? :docker))
    (is (registry/known? :podman))
    (is (registry/known? :nerdctl))
    (is (not (registry/known? :unknown-runtime)))))

(deftest registry-supported?-test
  (testing "Phase 2: :docker and :podman supported; :nerdctl and unknowns not"
    (is (registry/supported? :docker))
    (is (registry/supported? :podman))
    (is (not (registry/supported? :nerdctl)))
    (is (not (registry/supported? :unknown-runtime)))))

(deftest registry-executable-test
  (testing "executable lookup returns the registered binary name"
    (is (= "docker" (registry/executable :docker)))
    (is (= "podman" (registry/executable :podman)))
    (is (= "nerdctl" (registry/executable :nerdctl)))
    (is (nil? (registry/executable :unknown)))))

(deftest registry-capabilities-test
  (testing "Docker advertises the OCI capability set; unknown kinds get empty"
    (is (contains? (registry/capabilities :docker) :oci-images))
    (is (contains? (registry/capabilities :docker) :graceful-stop))
    (is (= #{} (registry/capabilities :unknown-runtime))))

  (testing "Podman matches Docker's OCI surface and adds :rootless"
    (is (contains? (registry/capabilities :podman) :oci-images))
    (is (contains? (registry/capabilities :podman) :tmpfs-uid-gid-options))
    (is (contains? (registry/capabilities :podman) :rootless))
    (is (not (contains? (registry/capabilities :docker) :rootless)))))

(deftest registry-flag-podman-info-template-test
  (testing "Podman declares its own :info-format-template (Podman info shape)"
    (is (= "{{.Version.Version}}" (registry/flag :podman :info-format-template)))
    (is (= "{{.ServerVersion}}" (registry/flag :docker :info-format-template)))))

(deftest registry-flag-fallback-test
  (testing "flag lookup falls back to the Docker dialect when the kind has no override"
    (let [docker-template (registry/flag :docker :info-format-template)]
      (is (some? docker-template))
      ;; :nerdctl has empty :flags so it inherits from Docker. :podman now
      ;; declares its own override, so it does NOT fall back.
      (is (= docker-template (registry/flag :nerdctl :info-format-template))))))

(deftest registry-default-test
  (testing "default lookup returns per-kind container defaults"
    (is (= 1000 (registry/default :docker :uid)))
    (is (= 1000 (registry/default :docker :gid)))
    (is (= "512m" (registry/default :docker :memory)))
    (is (= 0.5 (registry/default :docker :cpu)))
    (is (= "512m" (registry/default :docker :tmpfs-size))))

  (testing "Podman declares its own defaults (matching Docker's values today)"
    (is (= 1000 (registry/default :podman :uid)))
    (is (= 1000 (registry/default :podman :gid)))
    (is (= "512m" (registry/default :podman :memory)))
    (is (= 0.5 (registry/default :podman :cpu)))
    (is (= "512m" (registry/default :podman :tmpfs-size))))

  (testing "default lookup falls back to Docker for kinds with no override"
    (is (= "512m" (registry/default :nerdctl :memory)))))

(deftest registry-user-spec-test
  (testing "user-spec stitches uid:gid from the registry defaults"
    (is (= "1000:1000" (registry/user-spec :docker)))
    (is (= "1000:1000" (registry/user-spec :podman)))))

(deftest registry-tmpfs-mount-options-test
  (testing "tmpfs-mount-options builds the comma-separated string from defaults"
    (doseq [kind [:docker :podman]]
      (testing (str "kind " kind)
        (let [opts (registry/tmpfs-mount-options kind)]
          (is (clojure.string/includes? opts "size=512m"))
          (is (clojure.string/includes? opts "uid=1000"))
          (is (clojure.string/includes? opts "gid=1000"))
          (is (clojure.string/includes? opts "rw,nosuid,nodev,exec")))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.registry-test)
  :leave-this-here)
