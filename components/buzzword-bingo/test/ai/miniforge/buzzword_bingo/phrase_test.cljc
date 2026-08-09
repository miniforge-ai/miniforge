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
(ns ai.miniforge.buzzword-bingo.phrase-test
  (:require
   [ai.miniforge.buzzword-bingo.phrase :as sut]
   [ai.miniforge.buzzword-bingo.regex :as regex]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(defn- ^{:stratum 0} matches-of
  "Matched text of every occurrence of `term` in `text`."
  ([term text] (matches-of term nil text))
  ([term opts text]
   (mapv :match/text
         (regex/find-all (sut/term-pattern term opts) (regex/ascii-lower text)))))

(deftest ^{:stratum 0} test-term-pattern-rejects-a-blank-term
  (testing "given a blank term → the caller is told rather than handed a pattern"
    (is (thrown? #?(:clj Exception :cljs js/Error) (sut/term-pattern "   ")))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stemmed
  "Matched text of every occurrence of the stemmed `term` in `text`."
  [term text]
  (matches-of term {:stem? true} text))

;; Boundaries and phrases
(deftest ^{:stratum 1} test-term-pattern-matches-whole-words-only
  (testing "given a term embedded in a longer word → no match"
    (is (= [] (matches-of "art" "restart the cartography"))))

  (testing "given the term standing alone → matched"
    (is (= ["art"] (matches-of "art" "the art of it"))))

  (testing "given a term ending in punctuation → the boundary is not demanded"
    (is (= ["c++"] (matches-of "c++" "we use c++ here")))))

(deftest ^{:stratum 1} test-term-pattern-spans-wrapped-phrases
  (testing "given a phrase broken across a line → still matched"
    (is (= ["low-hanging\nfruit"]
           (matches-of "low-hanging fruit" "the low-hanging\nfruit today")))))

(deftest ^{:stratum 1} test-term-pattern-folds-case
  (testing "given capitalised source text → matched all the same"
    (is (= ["comprehensive"] (matches-of "comprehensive" "A Comprehensive guide")))))

;------------------------------------------------------------------------------ Layer 2

;; Inflection
(deftest ^{:stratum 2} test-term-pattern-stemming
  (testing "given a regular word → plural, past and participle forms match"
    (is (= ["empower" "empowers" "empowered" "empowering"]
           (stemmed "empower" "empower empowers empowered empowering"))))

  (testing "given a word ending in silent e → the e is dropped before -ing"
    (is (= ["leverage" "leverages" "leveraged" "leveraging"]
           (stemmed "leverage" "leverage leverages leveraged leveraging"))))

  (testing "given a word ending in consonant plus y → the y becomes ie"
    (is (= ["synergy" "synergies"] (stemmed "synergy" "synergy and synergies"))))

  (testing "given a stem shared with an unrelated word → not matched"
    (is (= [] (stemmed "leverage" "level levering lever"))))

  (testing "given a word too short to stem → its final letter is kept"
    (is (= ["be"] (stemmed "be" "to be"))))

  (testing "given a phrase → only its final word inflects"
    (is (= ["deep dives"] (stemmed "deep dive" "two deep dives")))))
