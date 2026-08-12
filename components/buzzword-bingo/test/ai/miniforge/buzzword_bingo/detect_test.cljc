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
(ns ai.miniforge.buzzword-bingo.detect-test
  (:require
   [ai.miniforge.buzzword-bingo.detect :as sut]
   [ai.miniforge.buzzword-bingo.entry :as entry]
   [ai.miniforge.buzzword-bingo.lexicon :as lexicon]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
;; Detection is exercised against a catalog defined here rather than the
;; shipped one, so editing the lexicon cannot break these assertions. The
;; entries still come from the real compiler, so they carry the shape a scan
;; receives in production.
(defn- ^{:stratum 0} scan-with
  "Scan `text` against a single-category catalog built from `terms`."
  [terms text]
  (let [categories {:probe {:category/weight 2}}
        raw        (mapv (fn [term] {:entry/term term :entry/category :probe}) terms)]
    (sut/scan text {:entries (entry/compile-all categories raw)})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} hit-terms [terms text]
  (mapv :hit/term (:scan/hits (scan-with terms text))))

(defn- ^{:stratum 1} hit-positions [terms text]
  (mapv (juxt :hit/line :hit/column) (:scan/hits (scan-with terms text))))

(deftest ^{:stratum 1} test-word-count-covers-prose-only
  (testing "given a document with code → only prose words are counted"
    (is (= 3 (:scan/word-count (scan-with ["robust"] "one two three\n\n```\nignored code here\n```")))))

  (testing "given the shipped catalog → its version is stamped on the result"
    (is (= lexicon/version (:scan/lexicon-version (sut/scan "text")))))

  (testing "given a caller's own catalog → no shipped version is claimed for it"
    (is (nil? (:scan/lexicon-version (scan-with ["robust"] "text")))))

  (testing "given a caller's own catalog and version → that version is stamped"
    (is (= "probe-1"
           (:scan/lexicon-version
            (sut/scan "text" {:entries [] :lexicon-version "probe-1"}))))))

;------------------------------------------------------------------------------ Layer 2

;; Where a hit is
(deftest ^{:stratum 2} test-hits-are-reported-at-their-source-position
  (testing "given a hit on a later line → line and column are one-based and address the source"
    (is (= [[3 7]] (hit-positions ["robust"] "first\nsecond\nthird robust here"))))

  (testing "given a hit after a masked region → the mask has not shifted its position"
    (is (= [[1 26]] (hit-positions ["robust"] "see `code here` and then robust"))))

  (testing "given a hit → surrounding source is quoted for context"
    (is (= "a robust claim"
           (:hit/context (first (:scan/hits (scan-with ["robust"] "a robust claim"))))))))

(deftest ^{:stratum 2} test-hits-are-ordered-by-position
  (testing "given several terms out of catalog order → hits ascend through the document"
    (is (= ["second" "first"] (hit-terms ["first" "second"] "the second, then the first")))))

;; What counts as a hit
(deftest ^{:stratum 2} test-overlapping-terms-are-counted-once
  (testing "given a term contained in a longer one → only the longer is charged"
    (is (= ["deep dive"] (hit-terms ["deep dive" "dive"] "a deep dive here"))))

  (testing "given the shorter term standing alone → it is charged"
    (is (= ["dive"] (hit-terms ["deep dive" "dive"] "a dive here"))))

  (testing "given two terms that merely overlap → both are charged"
    (is (= ["one two" "two three"] (hit-terms ["one two" "two three"] "one two three")))))

(deftest ^{:stratum 2} test-non-prose-is-not-counted
  (testing "given a term inside a code span → it is not a hit"
    (is (= [] (hit-terms ["robust"] "the `robust` parser"))))

  (testing "given a term inside a URL → it is not a hit"
    (is (= [] (hit-terms ["seamless"] "see https://example.com/seamless now")))))
