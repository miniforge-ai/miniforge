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
(ns ai.miniforge.schema.instrument-test
  "`instrument-fixture` checks declared function schemas, from `m/=>` or
   `:malli/schema` var metadata, only while the wrapped tests run."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.schema.interface :as schema]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} double-it
  [n]
  (* 2 n))

(defn ^{:stratum 0} broken-double
  "Declares an :int return, as var metadata, and returns a string."
  {:malli/schema [:=> [:cat :int] :int]}
  [n]
  (str (* 2 n)))

(def ^{:stratum 0} fixture
  (schema/instrument-fixture 'ai.miniforge.schema.instrument-test))

(defn ^{:stratum 0} thrown-type
  "The ex-data :type of the ex-info `f` throws."
  [f]
  (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (f))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} instrumented-call-is-checked-test
  (fixture
   (fn []
     (testing "a valid call returns the function's value"
       (is (= 4 (double-it 2))))
     (testing "an argument that breaks the input schema throws invalid-input"
       (is (= ::m/invalid-input (thrown-type #(double-it "2")))))
     (testing "a return value that breaks the output schema throws invalid-output"
       (is (= ::m/invalid-output (thrown-type #(broken-double 1))))))))

(deftest ^{:stratum 1} functions-are-restored-after-fixture-test
  (fixture (fn [] (double-it 1)))
  (testing "outside the fixture the original function runs unchecked"
    (is (= "2" (broken-double 1)))))

(m/=> double-it [:=> [:cat :int] :int])
