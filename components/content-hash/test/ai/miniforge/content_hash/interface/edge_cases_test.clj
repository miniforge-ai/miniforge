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

(ns ai.miniforge.content-hash.interface.edge-cases-test
  "Edge cases: nil, empty containers, deeply nested structures, keyword
   keys, and mixed-type values."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch]))

(def ^:private hex-64-pattern #"[0-9a-f]{64}")

(deftest hashes-nil
  (testing "nil produces a valid hex hash"
    (let [h (ch/content-hash nil)]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h)))))

(deftest hashes-empty-map
  (testing "An empty map produces a valid hex hash"
    (let [h (ch/content-hash {})]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h)))))

(deftest hashes-empty-vector
  (testing "An empty vector produces a valid hex hash"
    (let [h (ch/content-hash [])]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h)))))

(deftest hashes-empty-set
  (testing "An empty set produces a valid hex hash"
    (let [h (ch/content-hash #{})]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h)))))

(deftest distinguishes-empty-containers
  (testing "Different empty containers hash to different values"
    (is (not= (ch/content-hash nil) (ch/content-hash {})))
    (is (not= (ch/content-hash {})  (ch/content-hash [])))
    (is (not= (ch/content-hash [])  (ch/content-hash #{})))
    (is (not= (ch/content-hash nil) (ch/content-hash [])))))

(deftest hashes-deeply-nested-structure
  (testing "Deep nesting still produces a stable, valid hash"
    (let [deep {:a {:b {:c {:d {:e {:f 1}}}}}}
          h    (ch/content-hash deep)]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h))
      (is (= h (ch/content-hash deep))))))

(deftest hashes-keyword-keys
  (testing "Maps with namespaced keyword keys hash deterministically"
    (let [m1 (array-map :ns/a 1 :ns/b 2)
          m2 (array-map :ns/b 2 :ns/a 1)]
      (is (= (ch/content-hash m1) (ch/content-hash m2))))))

(deftest hashes-mixed-key-types
  (testing "Maps with mixed key types (strings, keywords) hash deterministically"
    (let [m1 (array-map "a" 1 :b 2)
          m2 (array-map :b 2 "a" 1)]
      (is (= (ch/content-hash m1) (ch/content-hash m2))))))

(deftest hashes-mixed-value-types
  (testing "Mixed value types (strings, numbers, keywords, nil, booleans, vectors) hash"
    (let [v {:a "string"
             :b 42
             :c :keyword
             :d nil
             :e true
             :f [1 2 3]
             :g #{:x :y}}
          h (ch/content-hash v)]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h))
      (is (= h (ch/content-hash v))))))

(deftest hashes-string-input
  (testing "A bare string input still produces a valid hex hash"
    (let [h (ch/content-hash "hello world")]
      (is (= 64 (count h)))
      (is (re-matches hex-64-pattern h)))))
