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
(ns ai.miniforge.buzzword-bingo.format-test
  (:require
   [ai.miniforge.buzzword-bingo.format :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Numbers
;; The whole reason this namespace exists: `(str 25.0)` is "25.0" on the JVM
;; and "25" in JavaScript, which would show up as a diff between the Babashka
;; CLI and the Node build reporting the same scan.
(deftest ^{:stratum 0} test-decimals-render-identically-on-every-host
  (testing "given a whole number → two decimal places all the same"
    (is (= "25.00" (sut/decimal 25.0))))

  (testing "given a fraction → rounded to two places, half up"
    (is (= "62.02" (sut/decimal 62.015)))
    (is (= "0.00" (sut/decimal 0.0)))
    (is (= "3.58" (sut/decimal 3.5837))))

  (testing "given a value below a tenth → the leading zero is kept"
    (is (= "0.05" (sut/decimal 0.05)))))

;; Columns
(deftest ^{:stratum 0} test-columns-hold-their-width
  (testing "given text shorter than the column → padded to it"
    (is (= "robust    " (sut/pad 10 "robust"))))

  (testing "given text longer than the column → left alone rather than cut"
    (is (= "low-hanging fruit" (sut/pad 10 "low-hanging fruit"))))

  (testing "given text longer than a truncation width → cut with an ellipsis"
    (is (= 8 (count (sut/truncate 8 "low-hanging fruit")))))

  (testing "given text within the truncation width → returned whole"
    (is (= "robust" (sut/truncate 8 "robust")))))
