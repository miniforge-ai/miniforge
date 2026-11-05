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
(ns ai.miniforge.knowledge.yaml-test
  "Frontmatter parsing: YAML block scalars, nested maps and block lists
   keep their values; EDN keyword vectors and non-YAML `.mdc` frontmatter
   keep the line parser's results."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.knowledge.yaml :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} documented-spec-frontmatter
  "Frontmatter shaped like the Markdown spec example in
   docs/user-guide/writing-specs.md."
  (str/join "\n" ["title: Add password reset flow"
                  "description: |"
                  "  Implement a password reset flow with email verification."
                  "  The user requests a reset."
                  "intent:"
                  "  type: feature"
                  "  scope: [\"src/auth/\"]"
                  "constraints:"
                  "  - No breaking changes to the public API"
                  "  - All existing tests must pass"]))

(deftest ^{:stratum 0} edn-keyword-vector-test
  (testing "`tags: [:a :b]` (EDN, not valid YAML) keeps its keywords"
    (is (= {:title "T" :tags [:compliance :scanner]}
           (sut/parse-yaml-frontmatter "title: T\ntags: [:compliance :scanner]"))))
  (testing "an EDN keyword vector does not cost a block-scalar description"
    (is (= {:description "Line one.\nLine two.\n" :tags [:auth :security]}
           (sut/parse-yaml-frontmatter
            "description: |\n  Line one.\n  Line two.\ntags: [:auth :security]"))))
  (testing "a YAML flow list of bare words is a vector of strings"
    (is (= {:tags ["bugfix" "auth"]}
           (sut/parse-yaml-frontmatter "tags: [bugfix, auth]")))))

(deftest ^{:stratum 0} non-yaml-fallback-test
  (testing "frontmatter YAML rejects (unquoted glob) falls back to the line parser"
    (is (= {:description "Header rule" :globs "**/*.{md,mdx,html}" :alwaysApply true}
           (sut/parse-yaml-frontmatter
            "description: Header rule\nglobs: **/*.{md,mdx,html}\nalwaysApply: true"))))
  (testing "empty frontmatter is an empty map"
    (is (= {} (sut/parse-yaml-frontmatter "")))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} block-scalar-and-nested-map-test
  (let [fm (sut/parse-yaml-frontmatter documented-spec-frontmatter)]
    (testing "a `description: |` block scalar keeps its text"
      (is (= (str "Implement a password reset flow with email verification.\n"
                  "The user requests a reset.\n")
             (:description fm))))
    (testing "a nested `intent:` map keeps type and scope"
      (is (= {:type "feature" :scope ["src/auth/"]} (:intent fm))))
    (testing "a block list becomes a vector"
      (is (= ["No breaking changes to the public API"
              "All existing tests must pass"]
             (:constraints fm))))))

(deftest ^{:stratum 1} plain-data-output-test
  (testing "parsed frontmatter is plain maps and vectors, not clj-yaml's ordered maps and lazy seqs"
    (let [fm (sut/parse-yaml-frontmatter documented-spec-frontmatter)]
      (is (not (str/includes? (pr-str fm) "#ordered")))
      (is (vector? (:constraints fm)))
      (is (vector? (get-in fm [:intent :scope]))))))
