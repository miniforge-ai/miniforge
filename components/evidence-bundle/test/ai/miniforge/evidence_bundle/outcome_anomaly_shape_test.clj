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

(ns ai.miniforge.evidence-bundle.outcome-anomaly-shape-test
  "Lock the dual-shape anomaly detection in `build-outcome-evidence`
   after the W2 anomaly convergence flip.

   evidence-bundle reads anomalies from arbitrary upstream producers
   (workflow/error info, nested :anomaly), so it must detect both
   canonical (`:anomaly/type`) and legacy (`:anomaly/category`) shapes
   until W5 retires the legacy producers. The dispatch follows
   `failure-classifier/classify-failure` (prefer canonical; fall back
   to legacy)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.response.interface :as response]))

(defn- workflow-state-with-error [error]
  {:workflow/id (random-uuid)
   :workflow/status :failed
   :workflow/spec {}
   :workflow/phases {}
   :workflow/gate-results []
   :workflow/error error})

;------------------------------------------------------------------------------ Canonical anomaly shape (post-W2 producers)

(deftest canonical-anomaly-at-error-info-routes-through-outcome
  (testing "a canonical anomaly map as :workflow/error is detected"
    (let [a       (anomaly/anomaly :fault "Compilation error" {})
          outcome (collector/build-outcome-evidence
                   (workflow-state-with-error a))]
      (is (false? (:outcome/success outcome)))
      (is (= "Compilation error" (:outcome/error-message outcome))))))

(deftest canonical-anomaly-nested-under-anomaly-routes-through-outcome
  (testing "a canonical anomaly under {:anomaly a} is also detected"
    (let [a       (anomaly/anomaly :timeout "agent timed out" {})
          outcome (collector/build-outcome-evidence
                   (workflow-state-with-error {:anomaly a}))]
      (is (false? (:outcome/success outcome)))
      (is (= "agent timed out" (:outcome/error-message outcome))))))

;------------------------------------------------------------------------------ Legacy anomaly shape (pre-W2 producers)

(deftest legacy-anomaly-at-error-info-still-detected
  (testing "a legacy :anomaly/category map as :workflow/error is detected"
    (let [a       (response/make-anomaly :anomalies/fault "boom")
          outcome (collector/build-outcome-evidence
                   (workflow-state-with-error a))]
      (is (false? (:outcome/success outcome)))
      (is (= "boom" (:outcome/error-message outcome))))))

(deftest legacy-anomaly-nested-under-anomaly-still-detected
  (testing "a legacy anomaly under {:anomaly a} is still detected"
    (let [a       (response/make-anomaly :anomalies/timeout "slow")
          outcome (collector/build-outcome-evidence
                   (workflow-state-with-error {:anomaly a}))]
      (is (false? (:outcome/success outcome)))
      (is (= "slow" (:outcome/error-message outcome))))))

;------------------------------------------------------------------------------ Non-anomaly fallback (legacy shape error map)

(deftest non-anomaly-error-falls-back-to-legacy-shape
  (testing "a plain error map (no :anomaly/type, no :anomaly/category)
            is preserved via the legacy non-anomaly fall-through"
    (let [outcome (collector/build-outcome-evidence
                   (workflow-state-with-error
                    {:message "old style" :phase :verify}))]
      (is (false? (:outcome/success outcome)))
      (is (= "old style" (:outcome/error-message outcome)))
      (is (= :verify (:outcome/error-phase outcome))))))
