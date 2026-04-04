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

(ns ai.miniforge.gate.test
  "Test validation gates.

   - :tests-pass - All tests pass
   - :coverage - Coverage meets threshold"
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Test checking

(defn check-tests-pass
  "Check if tests pass.

   In real impl, this would run tests and check results.
   Here we check for test results in artifact metadata."
  [artifact _ctx]
  (let [test-results (or (get-in artifact [:metadata :test-results])
                         (get-in artifact [:artifact/metadata :test-results]))]
    (cond
      (nil? test-results)
      {:passed? true
       :warnings [{:type :no-tests
                   :message "No test results found"}]}

      (:passed? test-results)
      {:passed? true
       :test-count (:test-count test-results)
       :pass-count (:pass-count test-results)}

      :else
      {:passed? false
       :errors [{:type :tests-failed
                 :message (str (:fail-count test-results) " tests failed")
                 :failures (:failures test-results)}]})))

(defn check-coverage
  "Check if coverage meets threshold.

   Default threshold: 80%"
  [artifact ctx]
  (let [threshold (or (get-in ctx [:coverage-threshold]) 80)
        coverage (or (get-in artifact [:metadata :coverage])
                     (get-in artifact [:artifact/metadata :coverage]))]
    (cond
      (nil? coverage)
      {:passed? true
       :warnings [{:type :no-coverage
                   :message "No coverage data found"}]}

      (>= coverage threshold)
      {:passed? true
       :coverage coverage
       :threshold threshold}

      :else
      {:passed? false
       :errors [{:type :coverage-below-threshold
                 :message (str "Coverage " coverage "% below threshold " threshold "%")
                 :coverage coverage
                 :threshold threshold}]})))

;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :tests-pass)
(registry/register-gate! :coverage)

(defmethod registry/get-gate :tests-pass
  [_]
  {:name :tests-pass
   :description "Validates all tests pass"
   :check check-tests-pass
   :repair nil})

(defmethod registry/get-gate :coverage
  [_]
  {:name :coverage
   :description "Validates test coverage meets threshold (default 80%)"
   :check check-coverage
   :repair nil})

;; Aliases for common gate names
(registry/register-gate! :test)
(defmethod registry/get-gate :test [_] (registry/get-gate :tests-pass))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-tests-pass {:metadata {:test-results {:passed? true :test-count 10}}} {})
  (check-coverage {:metadata {:coverage 85}} {})
  (check-coverage {:metadata {:coverage 70}} {})
  :leave-this-here)
