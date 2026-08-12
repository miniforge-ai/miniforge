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
(ns ai.miniforge.buzzword-bingo.lexicon-test
  (:require
   [ai.miniforge.buzzword-bingo.lexicon :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; The shipped catalog
;; Guards the EDN a human edits by hand. A malformed entry throws at load, so
;; reaching these assertions at all proves the catalog compiled.
(deftest ^{:stratum 0} test-shipped-catalog-is-well-formed
  (testing "given the shipped catalog → every entry carries a usable pattern"
    (is (every? :entry/regex sut/entries)))

  (testing "given the shipped catalog → every weight is positive"
    (is (every? #(pos? (:entry/effective-weight %)) sut/entries)))

  (testing "given the shipped catalog → ids are unique, so tallies cannot collide"
    (is (= (count sut/entries) (count (set (map :entry/id sut/entries))))))

  (testing "given the shipped catalog → every entry names a declared category"
    (is (every? #(contains? sut/categories (:entry/category %)) sut/entries)))

  (testing "given the shipped catalog → it declares a version to stamp scans with"
    (is (string? sut/version))))
