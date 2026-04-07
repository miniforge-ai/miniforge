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

(ns ai.miniforge.compliance-scanner.scanner-registry-test
  "Tests for file predicates and suggest functions in scanner-registry."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.compliance-scanner.scanner-registry :as registry]))

;; ---------------------------------------------------------------------------
;; clojure-source-file?

(deftest clojure-source-file-accepts-component-src
  (testing "component src .clj files are selected"
    (is (registry/clojure-source-file? "components/foo/src/ai/miniforge/foo/core.clj")))
  (testing "component src .cljc files are selected"
    (is (registry/clojure-source-file? "components/foo/src/ai/miniforge/foo/core.cljc")))
  (testing "bases src .clj files are selected"
    (is (registry/clojure-source-file? "bases/api/src/ai/miniforge/api/main.clj"))))

(deftest clojure-source-file-rejects-non-src
  (testing "test files are not selected"
    (is (not (registry/clojure-source-file? "components/foo/test/ai/miniforge/foo/core_test.clj"))))
  (testing "EDN files are not selected"
    (is (not (registry/clojure-source-file? "components/foo/deps.edn"))))
  (testing "markdown files are not selected"
    (is (not (registry/clojure-source-file? "docs/guide.md")))))

;; ---------------------------------------------------------------------------
;; version-file?

(deftest version-file-accepts-known-files
  (testing "build.clj is a version file"
    (is (registry/version-file? "build.clj")))
  (testing "version.edn is a version file"
    (is (registry/version-file? "version.edn")))
  (testing "GitHub workflow yaml is a version file"
    (is (registry/version-file? ".github/workflows/release.yml")))
  (testing "GitHub workflow yaml (alternate extension) is a version file"
    (is (registry/version-file? ".github/workflows/ci.yaml"))))

(deftest version-file-rejects-non-version-files
  (testing "source .clj is not a version file"
    (is (not (registry/version-file? "components/foo/src/core.clj"))))
  (testing "arbitrary YAML outside .github/workflows is not a version file"
    (is (not (registry/version-file? "config/app.yaml")))))

;; ---------------------------------------------------------------------------
;; markdown-doc-file?

(deftest markdown-doc-file-accepts-tracked-docs
  (testing "docs/ markdown is selected"
    (is (registry/markdown-doc-file? "docs/guide.md")))
  (testing "specs/ markdown is selected"
    (is (registry/markdown-doc-file? "specs/design.md")))
  (testing "root-level markdown is selected"
    (is (registry/markdown-doc-file? "README.md"))))

(deftest markdown-doc-file-rejects-excluded-paths
  (testing ".git files are excluded"
    (is (not (registry/markdown-doc-file? ".git/COMMIT_EDITMSG"))))
  (testing "node_modules files are excluded"
    (is (not (registry/markdown-doc-file? "node_modules/foo/README.md"))))
  (testing "src subdirectory markdown is not selected"
    (is (not (registry/markdown-doc-file? "components/foo/src/internal.md")))))

;; ---------------------------------------------------------------------------
;; suggest-clojure-map-access

(deftest suggest-clojure-map-access-rewrites-or-form
  (testing "integer default"
    (is (= "(get m :timeout 5000)"
           (registry/suggest-clojure-map-access "(or (:timeout m) 5000)"))))
  (testing "keyword default"
    (is (= "(get m :status :pending)"
           (registry/suggest-clojure-map-access "(or (:status m) :pending)"))))
  (testing "nil default"
    (is (= "(get m :k nil)"
           (registry/suggest-clojure-map-access "(or (:k m) nil)")))))

(deftest suggest-clojure-map-access-returns-nil-on-no-match
  (is (nil? (registry/suggest-clojure-map-access "(get m :k 0)"))))

;; ---------------------------------------------------------------------------
;; suggest-datever

(deftest suggest-datever-appends-build-count
  (is (= "1.2.3.0" (registry/suggest-datever "1.2.3")))
  (is (= "10.0.0.0" (registry/suggest-datever "10.0.0"))))

;; ---------------------------------------------------------------------------
;; suggest-copyright-header

(deftest suggest-copyright-header-returns-nil
  (is (nil? (registry/suggest-copyright-header nil)))
  (is (nil? (registry/suggest-copyright-header "anything"))))

;; ---------------------------------------------------------------------------
;; enabled-rule-configs

(deftest enabled-rule-configs-returns-all-by-default
  (let [cfgs (registry/enabled-rule-configs {:rules :all})]
    (is (= 3 (count cfgs)))
    (is (every? :rule/id cfgs))))

(deftest enabled-rule-configs-filters-by-requested-set
  (let [cfgs (registry/enabled-rule-configs {:rules #{:std/clojure}})]
    (is (= 1 (count cfgs)))
    (is (= :std/clojure (:rule/id (first cfgs))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.scanner-registry-test)
  :leave-this-here)
