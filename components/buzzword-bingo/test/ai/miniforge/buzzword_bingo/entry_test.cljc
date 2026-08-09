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
(ns ai.miniforge.buzzword-bingo.entry-test
  (:require
   [ai.miniforge.buzzword-bingo.entry :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private test-categories
  {:loud {:category/weight 4}
   :mild {:category/weight 1}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} compiled
  "Compile `raw-entries` against the test category table."
  [& raw-entries]
  (sut/compile-all test-categories raw-entries))

;------------------------------------------------------------------------------ Layer 2

;; Compilation contract
(deftest ^{:stratum 2} test-entry-identity-is-derived-from-its-display-name
  (testing "given a multi-word term → the id is its slug"
    (is (= [:low-hanging-fruit]
           (mapv :entry/id (compiled {:entry/term "low-hanging fruit"
                                      :entry/category :mild})))))

  (testing "given a raw pattern entry → the label supplies the id"
    (is (= [:not-only-but-also]
           (mapv :entry/id (compiled {:entry/pattern  "not only[\\s\\S]{0,40}?but also"
                                      :entry/label    "not only but also"
                                      :entry/category :mild}))))))

(deftest ^{:stratum 2} test-entry-weight-falls-back-to-its-category
  (testing "given no per-entry weight → the category default applies"
    (is (= 4 (:entry/effective-weight
              (first (compiled {:entry/term "loud" :entry/category :loud}))))))

  (testing "given a per-entry weight → it overrides the category"
    (is (= 9 (:entry/effective-weight
              (first (compiled {:entry/term "loud" :entry/category :loud
                                :entry/weight 9})))))))

(deftest ^{:stratum 2} test-compilation-rejects-unusable-entries
  (testing "given an unknown category → the catalog is refused at load"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (compiled {:entry/term "x" :entry/category :nonexistent}))))

  (testing "given neither a term nor a label → the catalog is refused at load"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (compiled {:entry/category :mild})))))
