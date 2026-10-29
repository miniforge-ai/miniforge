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
  (:require [ai.miniforge.gate.messages :as messages]
            [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

;; Test checking
(defn- ^{:stratum 0} artifact-test-results
  "Legacy source: test results stashed on the artifact's metadata."
  [artifact]
  (or (get-in artifact [:metadata :test-results])
      (get-in artifact [:artifact/metadata :test-results])))

(defn- ^{:stratum 0} phase-test-result
  "The gated phase's own result when it carries test metrics, else nil.

   `apply-gate-validation` hands gates the entered ctx, whose [:phase :result]
   is the phase result (verify: `phase/success` or `phase/error` with
   `phase/test-metrics`). A result is test-bearing when its :metrics has
   :fail-count — that is the verify contract; other phases' :metrics
   (tokens, cost) never carry it, so they fall through to the legacy source."
  [ctx]
  (let [result (get-in ctx [:phase :result])]
    (when (contains? (:metrics result) :fail-count)
      result)))

(def ^{:stratum 0} default-coverage-threshold
  "Coverage percentage the :coverage gate requires when ctx sets none."
  80)

(defn- ^{:stratum 0} check-phase-test-result
  "Judge a test-bearing phase result. Fails on any failing test, on a phase
   :status :error with no failing tests (parse error, crashed or timed out
   test command), and on a success that counted no tests — a verify that
   could not count its tests has not passed them. The one zero-test pass is
   a change-aware runner reporting nothing to test (`:no-tests?`), recorded
   as a :nothing-to-test warning. The verify :failures ride on the gate
   error so the implementer's gate-denial section names the tests to fix."
  [result]
  (let [metrics    (:metrics result)
        fail-count (get metrics :fail-count 0)
        pass-count (get metrics :pass-count 0)
        failures   (vec (get metrics :failures []))]
    (cond
      (pos? fail-count)
      {:passed? false
       :pass-count pass-count
       :fail-count fail-count
       :errors [{:type :tests-failed
                 :message (messages/t (if (= 1 fail-count)
                                        :tests-pass/failed-one
                                        :tests-pass/failed)
                                      {:fail-count fail-count})
                 :failures failures}]}

      (= :error (:status result))
      {:passed? false
       :pass-count pass-count
       :fail-count fail-count
       :errors [{:type :verify-error
                 :message (or (get-in result [:error :message])
                              (:summary result)
                              (messages/t :tests-pass/verify-error))
                 :failures failures}]}

      (and (zero? pass-count) (:no-tests? metrics))
      {:passed? true
       :test-count 0
       :pass-count 0
       :warnings [{:type :nothing-to-test
                   :message (messages/t :tests-pass/nothing-to-test)}]}

      (zero? pass-count)
      {:passed? false
       :pass-count 0
       :fail-count 0
       :errors [{:type :no-tests-ran
                 :message (messages/t :tests-pass/no-tests-ran)
                 :failures failures}]}

      :else
      {:passed? true
       :test-count pass-count
       :pass-count pass-count})))

(defn- ^{:stratum 0} check-artifact-test-results
  "Judge the legacy artifact-metadata shape; nil results fail."
  [test-results]
  (cond
    (nil? test-results)
    {:passed? false
     :errors [{:type :no-tests
               :message (messages/t :tests-pass/no-results)}]}

    (:passed? test-results)
    {:passed? true
     :test-count (:test-count test-results)
     :pass-count (:pass-count test-results)}

    :else
    {:passed? false
     :errors [{:type :tests-failed
               :message (let [n (get test-results :fail-count 0)]
                          (messages/t (if (= 1 n) :tests-pass/failed-one :tests-pass/failed)
                                      {:fail-count n}))
               :failures (:failures test-results)}]}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-coverage
  "Check if coverage meets threshold (ctx :coverage-threshold, default
   `default-coverage-threshold`).

   Fails with no coverage data: a configured coverage gate that saw no
   measurement has not shown the threshold was met. Nothing in miniforge
   measures coverage yet, so the gate is not in any phase's defaults."
  [artifact ctx]
  (let [threshold (get ctx :coverage-threshold default-coverage-threshold)
        coverage (or (get-in artifact [:metadata :coverage])
                     (get-in artifact [:artifact/metadata :coverage]))]
    (cond
      (nil? coverage)
      {:passed? false
       :errors [{:type :no-coverage
                 :message (messages/t :coverage/no-data)}]}

      (>= coverage threshold)
      {:passed? true
       :coverage coverage
       :threshold threshold}

      :else
      {:passed? false
       :errors [{:type :coverage-below-threshold
                 :message (messages/t :coverage/below-threshold
                                      {:coverage coverage :threshold threshold})
                 :coverage coverage
                 :threshold threshold}]})))

(defn ^{:stratum 1} check-tests-pass
  "Check if tests pass.

   Reads, in order: the gated phase's own result (verify's test metrics at
   [:phase :result :metrics] in the ctx — the production path; verify's
   :output carries no test results), then the legacy artifact-metadata
   :test-results.
   With neither present the gate fails with a :no-tests error.

   Before the phase-result source existed, verify's nil :output meant the
   gate saw no results and passed every run — including runs with failing
   tests (checkpoint f413dd80, 2026-09-04)."
  [artifact ctx]
  (if-let [result (phase-test-result ctx)]
    (check-phase-test-result result)
    (check-artifact-test-results (artifact-test-results artifact))))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} registry/get-gate :tests-pass
  [_]
  {:name :tests-pass
   :description "Validates all tests pass"
   :check check-tests-pass
   :repair nil})

(defmethod ^{:stratum 2} registry/get-gate :coverage
  [_]
  {:name :coverage
   :description "Validates test coverage meets threshold (default 80%)"
   :check check-coverage
   :repair nil})

(defmethod ^{:stratum 2} registry/get-gate :test [_] (registry/get-gate :tests-pass))

;; Registry
(registry/register-gate! :tests-pass)

(registry/register-gate! :coverage)

;; Aliases for common gate names
(registry/register-gate! :test)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-tests-pass {:metadata {:test-results {:passed? true :test-count 10}}} {})
  (check-tests-pass nil {:phase {:result {:status :error
                                           :metrics {:pass-count 3 :fail-count 1
                                                     :failures [{:test "t" :location "a.clj:1"}]}}}})
  (check-coverage {:metadata {:coverage 85}} {})
  (check-coverage {:metadata {:coverage 70}} {})
  :leave-this-here)
