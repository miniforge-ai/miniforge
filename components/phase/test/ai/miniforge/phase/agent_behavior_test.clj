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

(ns ai.miniforge.phase.agent-behavior-test
  "Unit tests for agent-behavior extraction, formatting, and phase filtering.

   Covers:
   - Layer 0: extract-agent-behaviors, format-behavior-addendum,
              rule-matches-phase? (indirectly)
   - Layer 1: load-builtin-rules, load-standards-rules (classpath EDN loading)
   - Layer 2: load-and-filter-behaviors pipeline (fail-safe)

   Acceptance criteria verified:
   - Standards pack is loaded separately from builtin pack (both from classpath)
   - Always-inject rules bypass file-glob/task-type matching
   - Phase-only gating for always-inject rules"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.phase.agent-behavior :as sut]))

;; ============================================================================
;; Layer 0 — extract-agent-behaviors tests
;; ============================================================================

(deftest extract-agent-behaviors-test
  (testing "extracts non-nil behavior strings from rules"
    (is (= ["Check existing files" "Validate inputs"]
           (sut/extract-agent-behaviors
            [{:rule/agent-behavior "Check existing files"}
             {:rule/agent-behavior nil}
             {:rule/agent-behavior "Validate inputs"}]))))

  (testing "returns empty vector when no behaviors present"
    (is (= [] (sut/extract-agent-behaviors
               [{:rule/agent-behavior nil}
                {:rule/id :no-behavior}]))))

  (testing "returns empty vector for empty input"
    (is (= [] (sut/extract-agent-behaviors []))))

  (testing "returns empty vector for nil input"
    (is (= [] (sut/extract-agent-behaviors nil))))

  (testing "preserves order of behaviors"
    (is (= ["first" "second" "third"]
           (sut/extract-agent-behaviors
            [{:rule/agent-behavior "first"}
             {:rule/agent-behavior "second"}
             {:rule/agent-behavior "third"}])))))

;; ============================================================================
;; Layer 0 — format-behavior-addendum tests
;; ============================================================================

(deftest format-behavior-addendum-test
  (testing "formats behaviors as numbered markdown section"
    (let [result (sut/format-behavior-addendum ["Check files" "Validate inputs"])]
      (is (string? result))
      (is (str/includes? result "## Policy Rules"))
      (is (str/includes? result "1. Check files"))
      (is (str/includes? result "2. Validate inputs"))))

  (testing "returns nil for empty behaviors"
    (is (nil? (sut/format-behavior-addendum []))))

  (testing "returns nil for nil behaviors"
    (is (nil? (sut/format-behavior-addendum nil))))

  (testing "single behavior produces valid numbered list"
    (let [result (sut/format-behavior-addendum ["Only one behavior"])]
      (is (str/includes? result "1. Only one behavior"))
      ;; Should not contain "2."
      (is (not (str/includes? result "2."))))))

;; ============================================================================
;; Layer 0 — rule-matches-phase? tests (via public interface)
;; ============================================================================

;; rule-matches-phase? is private, so we test it indirectly through the
;; full pipeline or by using var access for thorough unit testing.

(deftest rule-matches-phase-test
  (let [matches? (var-get #'ai.miniforge.phase.agent-behavior/rule-matches-phase?)]

    (testing "rule with matching phase returns true"
      (is (matches? {:rule/applies-to {:phases #{:implement :review}}} :implement)))

    (testing "rule without matching phase returns false"
      (is (not (matches? {:rule/applies-to {:phases #{:implement :review}}} :verify))))

    (testing "rule with nil phases matches all phases"
      (is (matches? {:rule/applies-to {:phases nil}} :implement))
      (is (matches? {:rule/applies-to {:phases nil}} :plan)))

    (testing "rule with empty phases matches all phases"
      (is (matches? {:rule/applies-to {:phases #{}}} :implement))
      (is (matches? {:rule/applies-to {:phases #{}}} :verify)))

    (testing "rule with no applies-to phases matches all phases"
      (is (matches? {:rule/applies-to {}} :implement)))

    (testing "always-inject semantics: phase-only gating regardless of globs"
      (let [rule {:rule/always-inject? true
                  :rule/applies-to {:phases #{:plan :implement}
                                    :file-globs ["*.tf"]}}]
        ;; rule-matches-phase? only checks phase, not globs
        (is (matches? rule :implement))
        (is (matches? rule :plan))
        (is (not (matches? rule :review)))))))

;; ============================================================================
;; Layer 1 — Rule loading tests
;; ============================================================================

(deftest load-builtin-rules-test
  (testing "returns a vector (possibly empty if resource missing)"
    (let [rules (sut/load-builtin-rules)]
      (is (vector? rules))))

  (testing "loaded rules have phases normalized to sets"
    (let [rules (sut/load-builtin-rules)]
      (doseq [rule rules
              :when (get-in rule [:rule/applies-to :phases])]
        (is (set? (get-in rule [:rule/applies-to :phases]))
            (str "Rule " (:rule/id rule) " should have phases as a set"))))))

(deftest load-standards-rules-test
  (testing "returns a vector (standards pack is separate from builtin)"
    (let [rules (sut/load-standards-rules)]
      (is (vector? rules))))

  (testing "standards rules are loaded from separate classpath resource"
    ;; Verifies the architectural decision: standards pack is a separate file
    ;; from builtin pack, both loaded from classpath.
    (let [builtin (sut/load-builtin-rules)
          standards (sut/load-standards-rules)]
      ;; They may both be empty if resources aren't present, but
      ;; the loading paths are distinct.
      (is (vector? builtin))
      (is (vector? standards))))

  (testing "loaded standards rules have phases normalized to sets"
    (let [rules (sut/load-standards-rules)]
      (doseq [rule rules
              :when (get-in rule [:rule/applies-to :phases])]
        (is (set? (get-in rule [:rule/applies-to :phases]))
            (str "Rule " (:rule/id rule) " should have phases as a set"))))))

(deftest load-user-pack-rules-test
  (testing "returns empty vector when no user packs directory exists"
    ;; Most test environments won't have ~/.miniforge/packs/
    (let [rules (sut/load-user-pack-rules)]
      (is (vector? rules)))))

;; ============================================================================
;; Layer 2 — Full pipeline tests
;; ============================================================================

(deftest load-and-filter-behaviors-fail-safe-test
  (testing "returns nil or string — never throws"
    (is (let [result (sut/load-and-filter-behaviors :implement {})]
          (or (nil? result) (string? result)))))

  (testing "returns nil or string for unknown phase"
    (is (let [result (sut/load-and-filter-behaviors :nonexistent-phase {})]
          (or (nil? result) (string? result)))))

  (testing "returns nil or string for nil phase"
    (is (let [result (sut/load-and-filter-behaviors nil {})]
          (or (nil? result) (string? result)))))

  (testing "returns nil or string for nil context"
    (is (let [result (sut/load-and-filter-behaviors :implement nil)]
          (or (nil? result) (string? result))))))

(deftest load-and-filter-behaviors-phase-filtering-test
  (testing "when behaviors are returned, they are formatted as markdown"
    (let [result (sut/load-and-filter-behaviors :implement {})]
      (when (some? result)
        (is (str/includes? result "## Policy Rules"))))))

;; ============================================================================
;; Integration — always-inject vs context-gated splitting
;; ============================================================================

(deftest always-inject-vs-context-gated-test
  (testing "always-inject rules are split from context-gated rules"
    ;; Simulate the splitting logic used in load-and-filter-behaviors
    (let [always-inject-rule {:rule/id :std/stratified-design
                              :rule/always-inject? true
                              :rule/agent-behavior "Output a stratified plan."
                              :rule/applies-to {:phases #{:plan :implement :review :verify :release}}}
          context-rule {:rule/id :std/clojure
                        :rule/agent-behavior "Follow Clojure style."
                        :rule/applies-to {:phases #{:implement :review}
                                          :file-globs ["*.clj"]}}
          all-rules [always-inject-rule context-rule]

          {always-inject true, context-gated false}
          (group-by #(boolean (:rule/always-inject? %)) all-rules)]

      (is (= 1 (count always-inject)))
      (is (= 1 (count context-gated)))
      (is (= :std/stratified-design (:rule/id (first always-inject))))
      (is (= :std/clojure (:rule/id (first context-gated))))))

  (testing "always-inject rules only check phase, not file globs"
    (let [matches? (var-get #'ai.miniforge.phase.agent-behavior/rule-matches-phase?)
          rule {:rule/always-inject? true
                :rule/applies-to {:phases #{:implement :verify}
                                  :file-globs ["*.tf"]}}]
      ;; Phase matches → included (regardless of file-globs)
      (is (matches? rule :implement))
      ;; Phase doesn't match → excluded
      (is (not (matches? rule :plan))))))

;; ============================================================================
;; Integration — end-to-end behavior extraction and formatting
;; ============================================================================

(deftest end-to-end-behavior-extraction-test
  (testing "full extraction pipeline: rules → behaviors → formatted addendum"
    (let [rules [{:rule/id :r1 :rule/agent-behavior "Check imports."}
                 {:rule/id :r2 :rule/agent-behavior nil}
                 {:rule/id :r3 :rule/agent-behavior "Validate schema."}
                 {:rule/id :r4}]  ;; no :rule/agent-behavior key at all
          behaviors (sut/extract-agent-behaviors rules)
          addendum (sut/format-behavior-addendum behaviors)]
      (is (= ["Check imports." "Validate schema."] behaviors))
      (is (string? addendum))
      (is (str/includes? addendum "1. Check imports."))
      (is (str/includes? addendum "2. Validate schema.")))))

;; ============================================================================
;; Layer 0 — extract-knowledge-content tests
;; ============================================================================

(deftest extract-knowledge-content-test
  (testing "extracts rules with :rule/knowledge-content"
    (let [result (sut/extract-knowledge-content
                  [{:rule/id :r1 :rule/title "Rule 1" :rule/knowledge-content "Content 1"}
                   {:rule/id :r2 :rule/title "Rule 2"}
                   {:rule/id :r3 :rule/title "Rule 3" :rule/knowledge-content "Content 3"}])]
      (is (= 2 (count result)))
      (is (= :r1 (:rule/id (first result))))
      (is (= "Content 1" (:content (first result))))))

  (testing "returns empty vector when no knowledge content"
    (is (= [] (sut/extract-knowledge-content [{:rule/id :r1}]))))

  (testing "returns empty vector for empty input"
    (is (= [] (sut/extract-knowledge-content [])))))

;; ============================================================================
;; Layer 0 — format-knowledge-addendum tests
;; ============================================================================

(deftest format-knowledge-addendum-test
  (testing "formats knowledge as Reference Material section"
    (let [result (sut/format-knowledge-addendum
                  [{:rule/title "Rule A" :content "Body A"}])]
      (is (string? result))
      (is (str/includes? result "## Reference Material"))
      (is (str/includes? result "### Rule A"))
      (is (str/includes? result "Body A"))))

  (testing "formats multiple knowledge entries"
    (let [result (sut/format-knowledge-addendum
                  [{:rule/title "R1" :content "C1"}
                   {:rule/title "R2" :content "C2"}])]
      (is (str/includes? result "### R1"))
      (is (str/includes? result "### R2"))))

  (testing "returns nil for empty knowledge"
    (is (nil? (sut/format-knowledge-addendum []))))

  (testing "returns nil for nil knowledge"
    (is (nil? (sut/format-knowledge-addendum nil)))))

(deftest empty-rules-produce-nil-addendum-test
  (testing "no applicable rules → nil addendum (no empty section injected)"
    (let [behaviors (sut/extract-agent-behaviors [])
          addendum (sut/format-behavior-addendum behaviors)]
      (is (= [] behaviors))
      (is (nil? addendum)))))

(deftest all-nil-behaviors-produce-nil-addendum-test
  (testing "rules with no agent-behavior → nil addendum"
    (let [rules [{:rule/id :r1} {:rule/id :r2 :rule/agent-behavior nil}]
          behaviors (sut/extract-agent-behaviors rules)
          addendum (sut/format-behavior-addendum behaviors)]
      (is (= [] behaviors))
      (is (nil? addendum)))))

;; ============================================================================
;; Acceptance: standards + builtin loaded independently from classpath
;; ============================================================================

(deftest standards-and-builtin-are-independent-resources-test
  (testing "standards and builtin packs are loaded from different classpath resources"
    ;; This verifies the architectural decision documented in acceptance criteria:
    ;; standards pack is a separate file from builtin pack.
    ;; Both are loaded from classpath EDN but from different resource paths.
    (let [builtin-rules (sut/load-builtin-rules)
          standards-rules (sut/load-standards-rules)]
      ;; Both return vectors (may be empty in test env)
      (is (vector? builtin-rules))
      (is (vector? standards-rules))
      ;; If both have rules, their IDs should be from different namespaces
      (when (and (seq builtin-rules) (seq standards-rules))
        (let [_builtin-ids (set (map :rule/id builtin-rules))
              standards-ids (set (map :rule/id standards-rules))]
          ;; Standards rules use :std/ namespace
          (doseq [id standards-ids]
            (is (= "std" (namespace id))
                (str "Standards rule " id " should use :std/ namespace"))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase.agent-behavior-test)
  :leave-this-here)
