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
(ns ai.miniforge.buzzword-bingo.report-test
  (:require
   [ai.miniforge.buzzword-bingo.interface :as bingo]
   [ai.miniforge.buzzword-bingo.report :as sut]
   [clojure.string :as str]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private slop-text
  (str "Our comprehensive platform delivers a seamless, robust experience. "
       "Let us delve into the tapestry of synergy and low-hanging fruit."))

(def ^{:stratum 0} ^:private clean-text
  (str "The loop condition used the wrong comparison, so the last index was "
       "skipped. The fix changes it and the two failing tests pass."))

(defn- ^{:stratum 0} report-of [text]
  (sut/scan-report (bingo/scan text)))

(defn- ^{:stratum 0} first-line [text]
  (first (str/split-lines text)))

(deftest ^{:stratum 0} test-a-caller-supplied-catalog-is-not-attributed-to-the-shipped-one
  (testing "given a caller's own catalog → the report does not claim a shipped version"
    (let [report (sut/scan-report (bingo/scan "robust" {:entries []}))]
      (is (not (str/includes? report (bingo/lexicon-version)))))))

;------------------------------------------------------------------------------ Layer 1

;; What a report says
(deftest ^{:stratum 1} test-the-verdict-comes-first
  (testing "given slop → the first line names the grade, before any evidence"
    (is (str/includes? (first-line (report-of slop-text)) "slop")))

  (testing "given clean prose → the first line says so"
    (is (str/includes? (first-line (report-of clean-text)) "clean"))))

(deftest ^{:stratum 1} test-a-report-shows-its-working
  (testing "given hits → the score, the thresholds it was judged against, and the catalog"
    (let [report (report-of slop-text)]
      (is (str/includes? report "per 1000 words"))
      (is (str/includes? report "suspect from"))
      (is (str/includes? report (bingo/lexicon-version)))))

  (testing "given hits → every flagged term is named, so the verdict can be argued with"
    (let [report (report-of slop-text)]
      (is (str/includes? report "comprehensive"))
      (is (str/includes? report "delve"))
      (is (str/includes? report "low-hanging fruit"))))

  (testing "given hits → categories are broken out alongside terms"
    (let [report (report-of slop-text)]
      (is (str/includes? report "marketing"))
      (is (str/includes? report "ai-tell")))))

;; Edges
(deftest ^{:stratum 1} test-a-clean-report-stays-short
  (testing "given nothing flagged → the report says so rather than printing empty sections"
    (let [report (report-of clean-text)]
      (is (str/includes? report "nothing flagged"))
      (is (not (str/includes? report "By term"))))))
