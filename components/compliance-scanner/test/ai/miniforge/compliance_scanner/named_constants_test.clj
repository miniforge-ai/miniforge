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
(ns ai.miniforge.compliance-scanner.named-constants-test
  (:require
   [ai.miniforge.compliance-scanner.named-constants :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} tokens [s] (mapv :token (sut/scan-numeric-tokens s)))

(deftest ^{:stratum 0} string-with-newline-keeps-line-numbers-test
  (testing "a multi-line string does not desync line tracking"
    (let [r (sut/scan-numeric-tokens "(def s \"a\nb\")\n(def n 42)")]
      (is (= [{:token "42"}] (mapv #(select-keys % [:token]) r)))
      (is (= 3 (:line (first r))) "42 is on line 3, after the 2-line string"))))

(deftest ^{:stratum 0} analyze-content-shape-test
  (testing "violation records carry rule id, file, line, and the token"
    (let [[v] (sut/analyze-content "x.clj" "(def t 5000)")]
      (is (= :std/named-constants (:rule/id v)))
      (is (= "x.clj" (:file v)))
      (is (= "5000" (:current v)))
      (is (= :magic-numeric (:kind v)))
      (is (pos-int? (:line v))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} flags-magic-numeric-literals-test
  (testing "numbers outside the sentinel set are magic"
    (is (= ["300000"] (tokens "(def x {:t 300000})")))
    (is (= ["130"] (tokens "(recur 130 acc)")))
    (is (= ["10" "60"] (tokens "(max 10 (- cols 60))")))
    (is (= ["0xFF"] (tokens "(bit-and x 0xFF)")) "hex is magic")
    (is (= ["7200"] (tokens "\"sleep\" 7200")) "int after a string still flagged")))

(deftest ^{:stratum 1} exempts-structural-sentinels-test
  (testing "-1, 0, 1, 2 are structural and never flagged"
    (is (= [] (tokens "(if (< n 2) 0 1)")))
    (is (= [] (tokens "(dec 1) (inc 0) (nth xs -1)")))
    (is (= [] (tokens "(* x 2)")) "the doubling identity is exempt")))

(deftest ^{:stratum 1} ignores-strings-comments-symbols-test
  (testing "numbers inside strings, comments, or symbols are not code literals"
    (is (= [] (tokens "(def p \"config/foo/messages/64.edn\")")) "in a string")
    (is (= [] (tokens ";; timeout is 300000 ms")) "in a comment")
    (is (= [] (tokens "(def h sha256-hex)")) "digits inside a symbol")
    (is (= [] (tokens "(str \"ver 300\")")) "number in a string arg")))

(deftest ^{:stratum 1} char-literals-not-retokenized-test
  (testing "unicode/octal/named char literals don't leak digits as numbers"
    (is (= [] (tokens "(def c \\u0030)")) "\\u0030 digits not a number")
    (is (= [] (tokens "(def c \\o101)")) "\\o101 digits not a number")
    (is (= [] (tokens "(when (= ch \\newline) x)")) "named char literal")
    (is (= [] (tokens "(def c \\1)")) "digit char literal is not a magic number")
    (is (= ["42"] (tokens "(def c \\a) (def n 42)")) "a real number after a char literal still flags")))
