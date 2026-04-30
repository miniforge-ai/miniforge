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

(ns ai.miniforge.gate.format-test
  "Tests for the LSP format gate.

   Regression coverage: check-format must return the gate-protocol shape
   {:passed? true ...} so the gate-interface passed? predicate accepts it.
   Earlier returns of (response/success {...}) silently produced {:status
   :success ...} with no :passed? key, so every implement phase tripped a
   spurious :format gate failure even when nothing was wrong."
  (:require
   [ai.miniforge.gate.format :as format-gate]
   [ai.miniforge.gate.interface :as gate]
   [clojure.test :refer [deftest is testing]]))

(deftest check-format-returns-passed-true
  (testing "check-format returns the gate-protocol shape with :passed? true"
    (let [artifact {:code/files [{:path "src/foo.clj"}]}
          result (format-gate/check-format artifact {})]
      (is (true? (:passed? result))
          "check-format must set :passed? true so gate/passed? accepts it")
      (is (gate/passed? (assoc result :gate :format))
          "gate/passed? must accept the shape returned by check-format")))

  (testing "check-format passes even when no files are formattable"
    (let [result (format-gate/check-format {:code/files []} {})]
      (is (true? (:passed? result)))
      (is (= [] (:formattable-files result))))))

(deftest check-format-reports-formattable-files
  (testing "Formattable files are listed in the result"
    (let [artifact {:code/files [{:path "a.clj"} {:path "b.md"}]}
          result (format-gate/check-format artifact {})]
      (is (true? (:passed? result)))
      (is (vector? (:formattable-files result))))))
