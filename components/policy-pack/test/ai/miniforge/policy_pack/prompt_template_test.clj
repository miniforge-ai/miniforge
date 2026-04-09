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

(ns ai.miniforge.policy-pack.prompt-template-test
  "Tests for pack-bundled prompt template interpolation and rendering."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.prompt-template :as sut]))

;; ============================================================================
;; Layer 0 — Interpolation engine tests
;; ============================================================================

(deftest interpolate-test
  (testing "replaces keyword-keyed variables"
    (is (= "Hello Chris" (sut/interpolate "Hello {{name}}" {:name "Chris"}))))

  (testing "replaces string-keyed variables"
    (is (= "Hello Chris" (sut/interpolate "Hello {{name}}" {"name" "Chris"}))))

  (testing "replaces multiple variables"
    (is (= "src/core.clj:42"
           (sut/interpolate "{{file}}:{{line}}" {:file "src/core.clj" :line 42}))))

  (testing "unknown variables become empty string"
    (is (= "Hello " (sut/interpolate "Hello {{unknown}}" {}))))

  (testing "no placeholders returns input unchanged"
    (is (= "plain text" (sut/interpolate "plain text" {:foo "bar"})))))

;; ============================================================================
;; Layer 2 — Template resolution tests
;; ============================================================================

(deftest resolve-repair-template-test
  (testing "rule-level template wins"
    (let [rule {:rule/repair-prompt-template "Rule: {{current}}"}
          pack {:pack/prompt-templates {:repair-prompt "Pack: {{current}}"}}]
      (is (= "Rule: {{current}}" (sut/resolve-repair-template rule pack)))))

  (testing "pack-level template used when rule has none"
    (let [rule {}
          pack {:pack/prompt-templates {:repair-prompt "Pack: {{current}}"}}]
      (is (= "Pack: {{current}}" (sut/resolve-repair-template rule pack)))))

  (testing "default used when neither rule nor pack provides"
    (is (= sut/default-repair-prompt (sut/resolve-repair-template {} {})))))

(deftest resolve-behavior-template-test
  (testing "pack-level template wins"
    (let [pack {:pack/prompt-templates {:behavior-section "Custom: {{behaviors}}"}}]
      (is (= "Custom: {{behaviors}}" (sut/resolve-behavior-template pack)))))

  (testing "default used when pack has none"
    (is (= sut/default-behavior-section (sut/resolve-behavior-template {})))))

;; ============================================================================
;; Layer 2 — Rendering tests
;; ============================================================================

(deftest render-repair-prompt-test
  (testing "renders with default template"
    (let [violation {:file "src/core.clj" :line 42
                     :current "(eval x)" :rationale "eval is unsafe"
                     :rule/id :test/no-eval}
          rule      {:rule/title "No eval"}
          result    (sut/render-repair-prompt violation rule {})]
      (is (= :user (:role result)))
      (is (.contains (:content result) "No eval"))
      (is (.contains (:content result) "src/core.clj:42"))
      (is (.contains (:content result) "(eval x)"))))

  (testing "renders with rule-provided template"
    (let [violation {:file "main.tf" :line 10 :current "public" :rationale "bad"
                     :rule/id :test/rule}
          rule      {:rule/title "Test" :rule/repair-prompt-template "Fix {{file}} line {{line}}"}
          result    (sut/render-repair-prompt violation rule {})]
      (is (= "Fix main.tf line 10" (:content result)))))

  (testing "renders with pack-provided template"
    (let [violation {:file "a.clj" :line 1 :current "x" :rationale "y"
                     :rule/id :test/rule}
          rule      {:rule/title "R"}
          pack      {:pack/prompt-templates {:repair-prompt "Pack fix: {{rationale}}"}}
          result    (sut/render-repair-prompt violation rule pack)]
      (is (= "Pack fix: y" (:content result))))))

(deftest render-behavior-section-test
  (testing "formats numbered behaviors"
    (let [result (sut/render-behavior-section ["Do A" "Do B"] {})]
      (is (.contains result "1. Do A"))
      (is (.contains result "2. Do B"))))

  (testing "returns nil for empty behaviors"
    (is (nil? (sut/render-behavior-section [] {})))))

(deftest render-knowledge-section-test
  (testing "formats knowledge entries"
    (let [result (sut/render-knowledge-section
                  [{:rule/title "Rule A" :content "Content A"}] {})]
      (is (.contains result "### Rule A"))
      (is (.contains result "Content A"))))

  (testing "returns nil for empty knowledge"
    (is (nil? (sut/render-knowledge-section [] {})))))
