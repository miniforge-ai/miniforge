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
(ns ai.miniforge.buzzword-bingo.regex-test
  (:require
   [ai.miniforge.buzzword-bingo.regex :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private dotted-capital-i
  "U+0130. Lowercases to two codepoints under full Unicode rules, which
   would shift every index derived from the folded string."
  "İ")

(defn- ^{:stratum 0} literal-matches
  "Matched text of every occurrence of the escaped literal `s` in `text`."
  [s text]
  (mapv :match/text (sut/find-all (re-pattern (sut/escape s)) text)))

(defn- ^{:stratum 0} match-indices [pattern text]
  (mapv :match/index (sut/find-all pattern text)))

(deftest ^{:stratum 0} test-find-all-terminates-on-a-zero-width-pattern
  (testing "given a pattern that can match nothing → enumeration still ends"
    (is (= 4 (count (sut/find-all #"x?" "abc"))))))

;------------------------------------------------------------------------------ Layer 1

;; Case folding, escaping, enumeration
(deftest ^{:stratum 1} test-case-folding-preserves-indices
  (testing "given ASCII capitals → folded to lowercase"
    (is (= "comprehensive" (sut/ascii-lower "Comprehensive"))))

  (testing "given a codepoint that expands under full Unicode folding → length unchanged"
    (is (= (count dotted-capital-i) (count (sut/ascii-lower dotted-capital-i)))))

  (testing "given non-ASCII letters → left alone"
    (is (= (str "robust " dotted-capital-i)
           (sut/ascii-lower (str "Robust " dotted-capital-i))))))

(deftest ^{:stratum 1} test-escaping-makes-metacharacters-literal
  (testing "given a literal containing regex syntax → matched as written"
    (is (= ["c++"] (literal-matches "c++" "we use c++ here"))))

  (testing "given a dot in a literal → does not match an arbitrary character"
    (is (= [] (literal-matches "a.b" "axb")))))

(deftest ^{:stratum 1} test-find-all-reports-positions-in-source-order
  (testing "given repeated occurrences → offsets ascend and address the source"
    (is (= [0 12] (match-indices #"robust" "robust code robust prose")))))
