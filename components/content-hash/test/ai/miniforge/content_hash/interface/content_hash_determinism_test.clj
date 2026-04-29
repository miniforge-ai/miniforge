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

(ns ai.miniforge.content-hash.interface.content-hash-determinism-test
  "Validate equal data produces equal hashes — including for hash maps
   whose internal iteration order differs from insertion order."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch]))

(deftest hash-stable-on-repeated-call
  (testing "Calling content-hash twice on the same value yields the same hash"
    (let [content {:type :terraform-plan :body "resource aws_instance"}]
      (is (= (ch/content-hash content) (ch/content-hash content))))))

(deftest hash-stable-across-insertion-order
  (testing "Maps with the same content but different insertion order hash identically"
    (let [m1 (array-map :a 1 :b 2 :c 3)
          m2 (array-map :c 3 :b 2 :a 1)]
      (is (= (ch/content-hash m1) (ch/content-hash m2))))))

(deftest hash-stable-across-arraymap-and-hashmap
  (testing "Same content as array-map and as hash-map hash identically"
    (let [pairs (mapv (fn [i] [(keyword (str "k" i)) i]) (range 32))
          arrayish  (apply array-map (mapcat identity pairs))
          hashish   (into (hash-map) arrayish)]
      (is (= (ch/content-hash arrayish) (ch/content-hash hashish))))))

(deftest hash-stable-on-nested-data
  (testing "Equal nested structures hash identically regardless of inner map order"
    (let [a {:outer (array-map :z 1 :a 2) :list [1 2 3]}
          b {:outer (array-map :a 2 :z 1) :list [1 2 3]}]
      (is (= (ch/content-hash a) (ch/content-hash b))))))
