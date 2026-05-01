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

   Regression coverage: `check-format` must return the canonical
   `response/success` shape AND `gate/passed?` must accept it.
   Earlier the gate machinery only checked `(:passed? result)` and
   `response/success` produces `{:status :success}` with no `:passed?`
   key, so every implement phase tripped a spurious :format failure
   even when nothing was wrong. Both halves of that contract are
   exercised here."
  (:require
   [ai.miniforge.gate.format :as format-gate]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.response.interface :as response]
   [clojure.test :refer [deftest is testing]]))

(deftest check-format-returns-canonical-success
  (testing "check-format returns response/success and gate/passed? accepts it"
    (let [artifact {:code/files [{:path "src/foo.clj"}]}
          result (format-gate/check-format artifact {})]
      (is (response/success? result)
          "check-format must return the canonical response/success shape")
      (is (gate/passed? (assoc result :gate :format))
          "gate/passed? must accept the canonical shape returned by check-format")))

  (testing "check-format passes even when no files are formattable"
    (let [result (format-gate/check-format {:code/files []} {})]
      (is (response/success? result))
      (is (gate/passed? (assoc result :gate :format)))
      (is (= [] (get-in result [:output :formattable-files]))))))

(deftest check-format-reports-formattable-files
  (testing "Formattable files are listed in the result output"
    (let [artifact {:code/files [{:path "a.clj"} {:path "b.md"}]}
          result (format-gate/check-format artifact {})]
      (is (response/success? result))
      (is (vector? (get-in result [:output :formattable-files]))))))
