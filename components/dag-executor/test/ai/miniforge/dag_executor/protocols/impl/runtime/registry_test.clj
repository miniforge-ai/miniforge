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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]))

(deftest registry-known-kinds-test
  (testing "registry exposes :docker, :podman, :nerdctl as known kinds"
    (let [known (registry/known-kinds)]
      (is (contains? known :docker))
      (is (contains? known :podman))
      (is (contains? known :nerdctl)))))

(deftest registry-supported-kinds-phase-1-test
  (testing "Phase 1 supports :docker only"
    (let [supported (registry/supported-kinds)]
      (is (contains? supported :docker))
      (is (not (contains? supported :podman)))
      (is (not (contains? supported :nerdctl))))))

(deftest registry-known?-test
  (testing "known? distinguishes registry membership from supported?"
    (is (registry/known? :docker))
    (is (registry/known? :podman))
    (is (registry/known? :nerdctl))
    (is (not (registry/known? :unknown-runtime)))))

(deftest registry-supported?-test
  (testing "supported? gates Phase 1 :docker only"
    (is (registry/supported? :docker))
    (is (not (registry/supported? :podman)))
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
    (is (= #{} (registry/capabilities :unknown-runtime)))))

(deftest registry-flag-fallback-test
  (testing "flag lookup falls back to the Docker dialect when the kind has no entry"
    (let [docker-template (registry/flag :docker :info-format-template)]
      (is (some? docker-template))
      ;; Phase 1 has empty flags for podman/nerdctl; lookups fall back to Docker
      ;; so existing call sites keep working until Phase 2 declares overrides.
      (is (= docker-template (registry/flag :podman :info-format-template)))
      (is (= docker-template (registry/flag :nerdctl :info-format-template))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.registry-test)
  :leave-this-here)
