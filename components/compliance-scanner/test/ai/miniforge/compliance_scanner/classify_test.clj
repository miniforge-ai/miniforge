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
(ns ai.miniforge.compliance-scanner.classify-test
  "Tests for the classify phase — pack-driven classification."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.compliance-scanner.classify :as classify]
            [ai.miniforge.compliance-scanner.scan-pack-config :as spc]))

;------------------------------------------------------------------------------ Layer 0

;; ---------------------------------------------------------------------------
;; Test fixtures — pack-enriched violations
(def ^{:stratum 0} ^:private base-210
  {:rule/id       :std/clojure
   :rule/category "210"
   :rule/title    "Clojure Map Access"
   :line          10
   :current       "(or (:timeout m) 5000)"
   :suggested     "(get m :timeout 5000)"
   ;; Pack enrichment
   :auto-fixable-default true
   :exclude-contexts [{:path-contains "server/"}
                       {:current-contains [":type" ":priority" ":status" ";; JSON"]}]})

(def ^{:stratum 0} ^:private base-730
  {:rule/id       :std/datever
   :rule/category "730"
   :rule/title    "Version Format (SemVer vs DateVer)"
   :line          3
   :current       "1.2.3"
   :suggested     "1.2.3.0"
   ;; Pack enrichment
   :auto-fixable-default false})

(def ^{:stratum 0} ^:private base-810
  {:rule/id       :std/header-copyright
   :rule/category "810"
   :rule/title    "Copyright Header (Markdown)"
   :line          1
   :current       "(missing copyright header)"
   :suggested     nil
   ;; Pack enrichment
   :auto-fixable-default true
   :remediation-type :prepend
   :remediation-template "<!--\n  Copyright header\n-->"})

;; ---------------------------------------------------------------------------
;; Manual-review rules (auto-fixable-default false)
(deftest ^{:stratum 0} classify-manual-review-uses-rule-title
  (testing "rules with auto-fixable-default false use rule title in rationale"
    (let [v   {:rule/id :foundations/no-unsafe-rust
               :rule/category "001"
               :rule/title "No Unsafe Rust"
               :file "src/ffi.rs"
               :line 42
               :current "unsafe {"
               :auto-fixable-default false}
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (str/includes? (:rationale r) "No Unsafe Rust"))
      (is (not (str/includes? (:rationale r) "SemVer"))))))

;; ---------------------------------------------------------------------------
;; enrich -> classify, as the scan phase actually wires it
(deftest ^{:stratum 0} enriched-manual-strategy-reaches-classify
  (testing "a pack rule declaring :manual is not auto-fixable after enrichment"
    (let [remediation {:strategy             :manual
                       :template             "<!-- header -->"
                       :auto-fixable-default true}
          enriched    (spc/enrich-violation
                       {:rule/id :std/header-copyright :rule/category "810"
                        :rule/title "Copyright Header (Markdown)"
                        :file "docs/a.md" :line 1}
                       remediation)
          [r]         (classify/classify-violations [enriched])]
      (is (= :manual (:remediation-strategy enriched))
          "enrich-violation must carry the declared strategy")
      (is (false? (:auto-fixable? r))))))

(deftest ^{:stratum 0} enriched-semantic-strategy-reaches-classify
  (testing "a pack rule declaring :semantic is not auto-fixable after enrichment"
    (let [enriched (spc/enrich-violation
                    {:rule/id :std/some-rule :rule/category "999"
                     :rule/title "Semantic Rule" :file "docs/a.md" :line 1}
                    {:strategy :semantic :auto-fixable-default true})
          [r]      (classify/classify-violations [enriched])]
      (is (= :semantic (:remediation-strategy enriched)))
      (is (false? (:auto-fixable? r))))))

;------------------------------------------------------------------------------ Layer 1

;; ---------------------------------------------------------------------------
;; Non-mechanical remediation strategies override :auto-fixable-default
;;
;; Regression guard: the strategy check was unreachable from the pack path
;; because `enrich-violation` never carried `:remediation-strategy`, so a rule
;; declaring `strategy: semantic` was classified auto-fixable on its
;; `autoFixable` default alone.
(deftest ^{:stratum 1} classify-manual-strategy-is-never-auto-fixable
  (testing ":manual strategy blocks auto-fix even when autoFixable defaults true"
    (let [v   (assoc base-810 :remediation-strategy :manual)
          [r] (classify/classify-violations [v])]
      (is (true? (:auto-fixable-default v))
          "fixture must declare autoFixable true, or the test proves nothing")
      (is (false? (:auto-fixable? r)))
      (is (re-find #"manual remediation strategy" (:rationale r))))))

(deftest ^{:stratum 1} classify-semantic-strategy-is-never-auto-fixable
  (testing ":semantic strategy blocks auto-fix even when autoFixable defaults true"
    (let [v   (assoc base-810 :remediation-strategy :semantic)
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (re-find #"semantic" (:rationale r))))))

(deftest ^{:stratum 1} classify-mechanical-strategy-stays-auto-fixable
  (testing ":mechanical strategy does not block auto-fix"
    (let [v   (assoc base-810 :remediation-strategy :mechanical)
          [r] (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r))))))

(deftest ^{:stratum 1} classify-nested-remediation-strategy-is-honoured
  (testing "strategy read from the nested [:remediation :strategy] form too"
    (let [v   (assoc base-810 :remediation {:strategy :manual})
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Dewey 210 classification
(deftest ^{:stratum 1} classify-210-standard-is-auto-fixable
  (testing "ordinary 210 violation in a component src file is auto-fixable"
    (let [v    (assoc base-210 :file "components/foo/src/ai/miniforge/foo/core.clj")
          [r]  (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r)))
      (is (re-find #"Literal default" (:rationale r))))))

(deftest ^{:stratum 1} classify-210-server-file-is-needs-review
  (testing "210 violation in a server/ file is excluded by context rule"
    (let [v   (assoc base-210 :file "server/handler.clj")
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (re-find #"JSON" (:rationale r))))))

(deftest ^{:stratum 1} classify-210-json-signal-key-is-needs-review
  (testing "210 violation with a JSON-signal key (:status) is excluded"
    (let [v   (-> base-210
                  (assoc :file "components/foo/src/ai/miniforge/foo/core.clj")
                  (assoc :current "(or (:status m) :active)"))
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Dewey 730 classification
(deftest ^{:stratum 1} classify-730-always-needs-review
  (testing "Dewey 730 violations always need review — declared not auto-fixable"
    (let [v   (assoc base-730 :file "build.clj")
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (string? (:rationale r))))))

;; ---------------------------------------------------------------------------
;; Dewey 810 classification
(deftest ^{:stratum 1} classify-810-missing-header-is-auto-fixable
  (testing "810 violation for absent header is auto-fixable"
    (let [v   (assoc base-810 :file "docs/guide.md")
          [r] (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Batch classification
(deftest ^{:stratum 1} classify-batch-returns-all-violations
  (testing "classify-violations returns same count as input"
    (let [viols [base-210 base-730 base-810]
          result (classify/classify-violations
                  (mapv #(assoc % :file "components/foo/src/core.clj") viols))]
      (is (= 3 (count result)))
      (is (every? #(contains? % :auto-fixable?) result))
      (is (every? #(string? (:rationale %)) result)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.classify-test)
  :leave-this-here)
