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
(ns ai.miniforge.buzzword-bingo.segment-test
  (:require
   [ai.miniforge.buzzword-bingo.segment :as sut]
   [clojure.string :as str]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private word-pattern #"[A-Za-z][A-Za-z'-]*")

(defn- ^{:stratum 0} newline-count [text]
  (count (filter #(= \newline %) text)))

(defn- ^{:stratum 0} fenced
  "A document with `body` inside a fenced code block."
  [prose body]
  (str prose "\n\n```clojure\n" body "\n```\n"))

(deftest ^{:stratum 0} test-mask-leaves-ordinary-prose-untouched
  (testing "given prose with no machine-readable regions → returned unchanged"
    (let [text "A plain sentence about a parser, with a comma and a full stop."]
      (is (= text (sut/prose-only text)))))

  (testing "given prose punctuation resembling markup → not mistaken for code"
    (is (str/includes? (sut/prose-only "the ratio is 3 > 2 in practice") "practice"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} surviving-words
  "Words still visible after masking `text`."
  ([text] (surviving-words text nil))
  ([text opts] (set (re-seq word-pattern (sut/prose-only text opts)))))

;; The mask keeps the source addressable
(deftest ^{:stratum 1} test-mask-is-position-preserving
  (testing "given any document → the mask has the same length"
    (let [text (fenced "keep this" "(def dropped 1)")]
      (is (= (count text) (count (sut/prose-only text))))))

  (testing "given a multi-line document → newlines survive masking"
    (let [text (fenced "keep this" "(def dropped 1)")]
      (is (= (newline-count text) (newline-count (sut/prose-only text)))))))

;------------------------------------------------------------------------------ Layer 2

;; What counts as prose
(deftest ^{:stratum 2} test-mask-blanks-code
  (testing "given a fenced block → its contents are dropped and prose kept"
    (is (= #{"keep"} (surviving-words (fenced "keep" "dropped")))))

  (testing "given a fence left open at end of document → the tail is dropped"
    (is (= #{"keep"} (surviving-words "keep\n\n```\ndropped\nalso"))))

  (testing "given an inline code span → only its contents are dropped"
    (is (= #{"keep" "and"} (surviving-words "keep `dropped` and `gone`")))))

(deftest ^{:stratum 2} test-mask-blanks-machine-readable-text
  (testing "given a bare URL → its slug is not prose"
    (is (= #{"keep"} (surviving-words "keep https://example.com/dropped-word"))))

  (testing "given a markdown link → the target is dropped and the label kept"
    (is (= #{"keep" "label"} (surviving-words "keep [label](https://example.com/dropped)"))))

  (testing "given filesystem paths → both rooted and extension-bearing forms are dropped"
    (is (= #{"keep" "and"}
           (surviving-words "keep ./dropped/file.clj and components/dropped/deps.edn"))))

  (testing "given a word pair joined by a slash → it is prose, not a path"
    (is (= #{"input" "output" "robust"} (surviving-words "input/output robust"))))

  (testing "given HTML comments and tags → both are dropped"
    (is (= #{"keep"} (surviving-words "<!-- dropped -->keep<span class=\"dropped\">")))))

(deftest ^{:stratum 2} test-mask-blanks-quotations-unless-asked
  (testing "given a blockquote → somebody else's prose is not counted by default"
    (is (= #{"mine"} (surviving-words "> theirs\nmine"))))

  (testing "given score-quotes? → the quotation is counted too"
    (is (= #{"theirs" "mine"} (surviving-words "> theirs\nmine" {:score-quotes? true})))))
