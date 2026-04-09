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
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.compliance-scanner.classify :as classify]))

;; ---------------------------------------------------------------------------
;; Test fixtures — pack-enriched violations

(def ^:private base-210
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

(def ^:private base-730
  {:rule/id       :std/datever
   :rule/category "730"
   :rule/title    "Version Format (SemVer vs DateVer)"
   :line          3
   :current       "1.2.3"
   :suggested     "1.2.3.0"
   ;; Pack enrichment
   :auto-fixable-default false})

(def ^:private base-810
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
;; Dewey 210 classification

(deftest classify-210-standard-is-auto-fixable
  (testing "ordinary 210 violation in a component src file is auto-fixable"
    (let [v    (assoc base-210 :file "components/foo/src/ai/miniforge/foo/core.clj")
          [r]  (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r)))
      (is (re-find #"Literal default" (:rationale r))))))

(deftest classify-210-server-file-is-needs-review
  (testing "210 violation in a server/ file is excluded by context rule"
    (let [v   (assoc base-210 :file "server/handler.clj")
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (re-find #"JSON" (:rationale r))))))

(deftest classify-210-json-signal-key-is-needs-review
  (testing "210 violation with a JSON-signal key (:status) is excluded"
    (let [v   (-> base-210
                  (assoc :file "components/foo/src/ai/miniforge/foo/core.clj")
                  (assoc :current "(or (:status m) :active)"))
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Dewey 730 classification

(deftest classify-730-always-needs-review
  (testing "Dewey 730 violations always need review — declared not auto-fixable"
    (let [v   (assoc base-730 :file "build.clj")
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (string? (:rationale r))))))

;; ---------------------------------------------------------------------------
;; Dewey 810 classification

(deftest classify-810-missing-header-is-auto-fixable
  (testing "810 violation for absent header is auto-fixable"
    (let [v   (assoc base-810 :file "docs/guide.md")
          [r] (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Manual-review rules (auto-fixable-default false)

(deftest classify-manual-review-uses-rule-title
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
      (is (clojure.string/includes? (:rationale r) "No Unsafe Rust"))
      (is (not (clojure.string/includes? (:rationale r) "SemVer"))))))

;; ---------------------------------------------------------------------------
;; Batch classification

(deftest classify-batch-returns-all-violations
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
