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
(ns ai.miniforge.policy-pack.applicability-paths-test
  "A rule's file globs against an artifact that spans several files."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.applicability :as applicability]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.policy-pack.registry.support :as registry-support]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private clj-rule
  {:rule/id :test/clj :rule/applies-to {:file-globs ["**/*.clj"]}})

(def ^{:stratum 0} ^:private md-rule
  {:rule/id :test/md :rule/applies-to {:file-globs ["**/*.md"]}})

(defn- ^{:stratum 0} paths-context
  "A rule-filter context whose artifact spans `paths`."
  [& paths]
  {:artifact {:artifact/paths (vec paths)}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} rule-applies-when-any-path-matches-test
  (testing "an artifact with several :artifact/paths matches a rule when any
            path matches its globs"
    (is (applicability/rule-applies-to-artifact?
         clj-rule {:artifact/paths ["README.md" "src/core.clj"]}))
    (is (not (applicability/rule-applies-to-artifact?
              clj-rule {:artifact/paths ["README.md" "docs/a.md"]}))))
  (testing "a single :artifact/path behaves as before, and no path matches no
            glob"
    (is (applicability/rule-applies-to-artifact? clj-rule {:artifact/path "src/core.clj"}))
    (is (not (applicability/rule-applies-to-artifact? clj-rule {})))))

(deftest ^{:stratum 1} interface-filter-honours-artifact-paths-test
  (testing "the public filter-applicable-rules keeps a glob-scoped rule when
            any of the context's :artifact/paths matches"
    (is (= [:test/clj]
           (mapv :rule/id (policy-pack/filter-applicable-rules
                           [clj-rule md-rule]
                           (paths-context "src/core.clj" "deps.edn")))))))

(deftest ^{:stratum 1} registry-rule-applies-honours-artifact-paths-test
  (testing "the registry's rule-applies? reads :artifact/paths as the
            applicability filter does"
    (is (registry-support/rule-applies? clj-rule (paths-context "README.md" "src/core.clj")))
    (is (not (registry-support/rule-applies? clj-rule (paths-context "README.md"))))
    (is (registry-support/rule-applies?
         clj-rule {:artifact {:artifact/path "src/core.clj"}}))))
