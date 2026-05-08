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

(ns ai.miniforge.adapter-claude-code.tool-profiles-test
  "Tests for the Claude CLI tool-profile contributions.

   Uses isolated registries via `make-tool-registry` for assertion
   tests. Load-time registration into the process-wide
   default-tool-registry is also asserted, but indirectly — the act
   of requiring `tool-profiles` triggers the defonce side-effect."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.adapter-claude-code.tool-profiles :as sut]
   [ai.miniforge.progress-detector.interface :as pd]))

;------------------------------------------------------------------------------ Layer 0
;; Expected-shape constants

(def ^:private expected-tool-ids
  #{:tool/Read :tool/Bash :tool/Grep :tool/Glob
    :tool/Edit :tool/Write :tool/WebSearch :tool/WebFetch})

(def ^:private expected-determinisms
  {:tool/Read       :stable-with-resource-version
   :tool/Bash       :environment-dependent
   :tool/Grep       :stable-ish
   :tool/Glob       :stable-ish
   :tool/Edit       :stable-with-resource-version
   :tool/Write      :stable-with-resource-version
   :tool/WebSearch  :unstable
   :tool/WebFetch   :unstable})

;------------------------------------------------------------------------------ Layer 1
;; Profile-data shape

(deftest claude-cli-profiles-cardinality-test
  (testing "8 profiles registered, one per native Claude CLI tool"
    (is (= 8 (count sut/claude-cli-profiles)))
    (is (= expected-tool-ids
           (into #{} (map :tool/id) sut/claude-cli-profiles)))))

(deftest claude-cli-profiles-determinism-test
  (testing "each profile carries the documented determinism level"
    (doseq [profile sut/claude-cli-profiles]
      (let [tool-id (:tool/id profile)]
        (is (= (get expected-determinisms tool-id)
               (:determinism profile))
            (str tool-id " :determinism mismatch"))))))

(deftest claude-cli-profiles-categories-test
  (testing "every profile carries the tool-loop anomaly category"
    (doseq [profile sut/claude-cli-profiles]
      (is (contains? (:anomaly/categories profile)
                     :anomalies.agent/tool-loop)
          (str (:tool/id profile) " missing tool-loop category")))))

;------------------------------------------------------------------------------ Layer 2
;; Registration semantics — isolated registries

(deftest register-profiles-into-fresh-registry-test
  (testing "register-profiles! populates an empty registry"
    (let [registry (pd/make-tool-registry)]
      (sut/register-profiles! registry)
      (is (= expected-tool-ids
             (into #{} (pd/all-tool-ids registry)))))))

(deftest register-profiles-idempotent-test
  (testing "calling register-profiles! twice does not duplicate or corrupt"
    (let [registry (pd/make-tool-registry)]
      (sut/register-profiles! registry)
      (let [after-first @registry]
        (sut/register-profiles! registry)
        (is (= after-first @registry)
            "registry value identical after second register call")))))

(deftest registry-determinism-lookup-test
  (testing "registered profiles answer pd/tool-determinism queries"
    (let [registry (pd/make-tool-registry)]
      (sut/register-profiles! registry)
      (doseq [[tool-id expected] expected-determinisms]
        (is (= expected (pd/tool-determinism tool-id registry))
            (str tool-id " determinism lookup"))))))

(deftest unknown-tool-stays-unstable-test
  (testing "tools outside the Claude CLI set fall through to :unstable default"
    (let [registry (pd/make-tool-registry)]
      (sut/register-profiles! registry)
      (is (= :unstable (pd/tool-determinism :tool/SomeMcpTool registry))
          "unknown tool defaults to :unstable per tool-profile API"))))

;------------------------------------------------------------------------------ Layer 3
;; Load-time contribution to the process-wide registry

(deftest default-tool-registry-populated-at-load-test
  (testing "requiring this ns has registered the eight profiles globally"
    (let [global-ids (into #{} (pd/all-tool-ids))]
      (doseq [tool-id expected-tool-ids]
        (is (contains? global-ids tool-id)
            (str tool-id " missing from default-tool-registry"))))))

(deftest default-registry-determinism-test
  (testing "default-tool-registry returns documented determinism levels"
    (doseq [[tool-id expected] expected-determinisms]
      (is (= expected (pd/tool-determinism tool-id))
          (str tool-id " determinism via default registry")))))
