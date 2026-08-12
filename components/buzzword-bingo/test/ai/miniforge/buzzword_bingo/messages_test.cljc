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
(ns ai.miniforge.buzzword-bingo.messages-test
  (:require
   [ai.miniforge.buzzword-bingo.messages :as sut]
   [clojure.string :as str]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Lookup
(deftest ^{:stratum 0} test-catalog-lookup
  (testing "given a key in the catalog → its string"
    (is (= "BUZZWORD BINGO" (sut/t :report/heading))))

  (testing "given placeholders → substituted from params"
    (is (= "score 62.02 per 1000 words"
           (sut/t :report/score-line {:value "62.02"}))))

  (testing "given several placeholders → all substituted"
    (let [rendered (sut/t :report/tally-many {:hits 4 :words 29 :weighted 8})]
      (is (not (str/includes? rendered "{")))
      (is (str/includes? rendered "4"))
      (is (str/includes? rendered "29"))))

  ;; A report that loses a line is better than a report that throws, and the
  ;; key name says which string is missing.
  (testing "given a key absent from the catalog → its name, not an exception"
    (is (= "missing-string" (sut/t :nowhere/missing-string)))))

;; Counting
(deftest ^{:stratum 0} test-plural-forms-are-chosen-by-key
  (testing "given exactly one → the singular key"
    (is (= "1 time" (sut/counted :report/count-one :report/count-many 1 {:count 1}))))

  (testing "given more than one → the plural key"
    (is (= "3 times" (sut/counted :report/count-one :report/count-many 3 {:count 3}))))

  (testing "given none → the plural key, as English does"
    (is (= "0 times" (sut/counted :report/count-one :report/count-many 0 {:count 0})))))
