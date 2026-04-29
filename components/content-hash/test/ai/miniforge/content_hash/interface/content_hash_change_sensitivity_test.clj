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

(ns ai.miniforge.content-hash.interface.content-hash-change-sensitivity-test
  "Validate small changes in input produce different hashes."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch]))

(deftest hash-changes-with-value
  (testing "Different values for the same key produce different hashes"
    (is (not= (ch/content-hash {:k "a"}) (ch/content-hash {:k "b"})))))

(deftest hash-changes-with-key
  (testing "Different keys produce different hashes"
    (is (not= (ch/content-hash {:a 1}) (ch/content-hash {:b 1})))))

(deftest hash-changes-with-extra-key
  (testing "Adding an extra key changes the hash"
    (is (not= (ch/content-hash {:a 1})
              (ch/content-hash {:a 1 :b 2})))))

(deftest hash-changes-with-type
  (testing "Different scalar types for the same field change the hash"
    (is (not= (ch/content-hash {:k 1})    (ch/content-hash {:k "1"})))
    (is (not= (ch/content-hash {:k :foo}) (ch/content-hash {:k "foo"})))))

(deftest hash-changes-with-nested-edit
  (testing "A change deep inside a nested structure changes the hash"
    (is (not= (ch/content-hash {:outer {:inner {:leaf 1}}})
              (ch/content-hash {:outer {:inner {:leaf 2}}})))))

(deftest hash-changes-with-collection-order
  (testing "Reordering a sequential collection changes the hash"
    ;; Vectors/lists are order-sensitive — only maps and sets are reordered.
    (is (not= (ch/content-hash [1 2 3]) (ch/content-hash [3 2 1])))))
