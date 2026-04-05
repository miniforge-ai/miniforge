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
  "Tests for the classify phase."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.compliance-scanner.classify :as classify]))

;; ---------------------------------------------------------------------------
;; Test fixtures

(def ^:private base-210
  {:rule/dewey "210"
   :rule/title "Clojure Map Access"
   :line       10
   :current    "(or (:timeout m) 5000)"
   :suggested  "(get m :timeout 5000)"})

(def ^:private base-730
  {:rule/dewey "730"
   :rule/title "Version Format (SemVer vs DateVer)"
   :line       3
   :current    "1.2.3"
   :suggested  "1.2.3.0"})

(def ^:private base-810-absent
  {:rule/dewey "810"
   :rule/title "Copyright Header (Markdown)"
   :line       1
   :current    "(missing copyright header)"
   :suggested  nil})

(def ^:private base-810-wrong
  {:rule/dewey "810"
   :rule/title "Copyright Header (Markdown)"
   :line       1
   :current    "## Copyright 2019 Acme Corp"
   :suggested  nil})

;; ---------------------------------------------------------------------------
;; Dewey 210 classification

(deftest classify-210-standard-is-auto-fixable
  (testing "ordinary 210 violation in a component src file is auto-fixable"
    (let [v    (assoc base-210 :file "components/foo/src/ai/miniforge/foo/core.clj")
          [r]  (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r)))
      (is (= "Literal default, non-JSON field" (:rationale r))))))

(deftest classify-210-server-file-is-needs-review
  (testing "210 violation in a server/ file is flagged as needs-review"
    (let [v   (assoc base-210 :file "server/handler.clj")
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r)))
      (is (re-find #"JSON" (:rationale r))))))

(deftest classify-210-json-signal-key-is-needs-review
  (testing "210 violation with a JSON-signal key (:status) is needs-review"
    (let [v   (-> base-210
                  (assoc :file "components/foo/src/ai/miniforge/foo/core.clj")
                  (assoc :current "(or (:status m) :active)"))
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Dewey 730 classification

(deftest classify-730-always-auto-fixable
  (testing "Dewey 730 violations are always auto-fixable"
    (let [v   (assoc base-730 :file "build.clj")
          [r] (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r)))
      (is (string? (:rationale r))))))

;; ---------------------------------------------------------------------------
;; Dewey 810 classification

(deftest classify-810-missing-header-is-auto-fixable
  (testing "810 violation for absent header is auto-fixable"
    (let [v   (assoc base-810-absent :file "docs/guide.md")
          [r] (classify/classify-violations [v])]
      (is (true? (:auto-fixable? r)))
      (is (re-find #"absent" (:rationale r))))))

(deftest classify-810-wrong-content-is-needs-review
  (testing "810 violation for incorrect header content is needs-review"
    (let [v   (assoc base-810-wrong :file "docs/guide.md")
          [r] (classify/classify-violations [v])]
      (is (false? (:auto-fixable? r))))))

;; ---------------------------------------------------------------------------
;; Batch classification

(deftest classify-batch-returns-all-violations
  (testing "classify-violations returns same count as input"
    (let [viols [base-210 base-730 base-810-absent]
          result (classify/classify-violations
                  (mapv #(assoc % :file "components/foo/src/core.clj") viols))]
      (is (= 3 (count result)))
      (is (every? #(contains? % :auto-fixable?) result))
      (is (every? #(string? (:rationale %)) result)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.classify-test)
  :leave-this-here)
