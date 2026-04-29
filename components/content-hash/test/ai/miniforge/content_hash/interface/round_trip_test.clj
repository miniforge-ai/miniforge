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

(ns ai.miniforge.content-hash.interface.round-trip-test
  "Validate canonical-edn output round-trips through clojure.edn/read-string
   to a value that is = to the original input."
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch]))

(defn- round-trip [x]
  (edn/read-string (ch/canonical-edn x)))

(deftest round-trip-scalars
  (testing "Scalar values survive a canonical-edn -> read-string round trip"
    (is (= nil       (round-trip nil)))
    (is (= true      (round-trip true)))
    (is (= 42        (round-trip 42)))
    (is (= "hello"   (round-trip "hello")))
    (is (= :keyword  (round-trip :keyword)))
    (is (= 'symbol   (round-trip 'symbol)))))

(deftest round-trip-flat-map
  (testing "A flat map round-trips to an equal value"
    (let [m {:a 1 :b "two" :c :three}]
      (is (= m (round-trip m))))))

(deftest round-trip-nested-map
  (testing "A nested map round-trips to an equal value"
    (let [m {:outer {:inner {:leaf 42}}
             :list [1 2 3]
             :flag true}]
      (is (= m (round-trip m))))))

(deftest round-trip-collections
  (testing "Vectors, lists, and sets round-trip to equal values"
    (is (= [1 2 3]     (round-trip [1 2 3])))
    (is (= #{:a :b}    (round-trip #{:a :b})))
    ;; Lists are read back as lists in EDN; equality across list/vector
    ;; depends on type, so we test via vec for stability.
    (is (= [1 2 3] (vec (round-trip '(1 2 3)))))))
