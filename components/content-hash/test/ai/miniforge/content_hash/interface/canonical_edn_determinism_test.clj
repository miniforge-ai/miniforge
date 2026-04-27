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

(ns ai.miniforge.content-hash.interface.canonical-edn-determinism-test
  "Validate canonical-edn produces the same string regardless of map
   insertion order. This is the property that makes content-hash safe
   on hash maps where iteration order is not guaranteed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch]))

(deftest canonical-edn-stable-across-insertion-order
  (testing "Two maps with same content but different insertion order serialize identically"
    (let [m1 (array-map :a 1 :b 2 :c 3)
          m2 (array-map :c 3 :a 1 :b 2)
          m3 (array-map :b 2 :c 3 :a 1)]
      (is (= (ch/canonical-edn m1) (ch/canonical-edn m2)))
      (is (= (ch/canonical-edn m2) (ch/canonical-edn m3))))))

(deftest canonical-edn-stable-across-hashmap-vs-arraymap
  (testing "Hash map and array map with same data produce same canonical EDN"
    (let [array-version  (array-map :a 1 :b 2 :c 3 :d 4 :e 5)
          ;; force a hash map
          hash-version   (into (hash-map) array-version)]
      (is (= (ch/canonical-edn array-version) (ch/canonical-edn hash-version))))))

(deftest canonical-edn-sorts-nested-maps
  (testing "Nested maps are also canonicalized"
    (let [m1 {:outer (array-map :z 1 :a 2)}
          m2 {:outer (array-map :a 2 :z 1)}]
      (is (= (ch/canonical-edn m1) (ch/canonical-edn m2))))))

(deftest canonical-edn-handles-large-maps
  (testing "Maps larger than the array-map promotion threshold produce stable output"
    ;; Clojure promotes array-maps to hash-maps above ~8 entries; we need to
    ;; confirm that canonicalization makes the string stable across that
    ;; transition.
    (let [pairs (mapv (fn [i] [(keyword (str "k" i)) i]) (range 32))
          forward (apply array-map (mapcat identity pairs))
          backward (apply array-map (mapcat identity (reverse pairs)))]
      (is (= (ch/canonical-edn forward) (ch/canonical-edn backward))))))
