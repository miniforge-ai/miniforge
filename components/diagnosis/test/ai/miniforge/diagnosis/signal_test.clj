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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.signal-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.diagnosis.interface :as diag]))

(def now (java.util.Date.))

;; ---------------------------------------------------------------------------- Signal extraction

(deftest extract-slo-breach-signals-test
  (testing "SLO breaches produce signals"
    (let [signals (diag/extract-signals
                   {:slo-checks [{:breached? true :sli/name :SLI-1
                                   :slo/target 0.85 :slo/actual 0.70 :slo/tier :standard}]})]
      (is (= 1 (count signals)))
      (is (= :slo-breach (:signal/type (first signals))))
      (is (= :high (:signal/severity (first signals))))))

  (testing "critical tier breaches are critical severity"
    (let [signals (diag/extract-signals
                   {:slo-checks [{:breached? true :sli/name :SLI-1
                                   :slo/target 0.95 :slo/actual 0.80 :slo/tier :critical}]})]
      (is (= :critical (:signal/severity (first signals))))))

  (testing "non-breached SLOs produce no signals"
    (let [signals (diag/extract-signals
                   {:slo-checks [{:breached? false :sli/name :SLI-1
                                   :slo/target 0.85 :slo/actual 0.90}]})]
      (is (empty? signals)))))

(deftest extract-failure-pattern-signals-test
  (testing "recurring failures above threshold produce signals"
    (let [events (repeat 5 {:failure/class :failure.class/timeout :timestamp now})
          signals (diag/extract-signals {:failure-events events}
                                        {:min-failure-count 3})]
      (is (= 1 (count signals)))
      (is (= :recurring-failure (:signal/type (first signals))))))

  (testing "below threshold produces no signals"
    (let [events (repeat 2 {:failure/class :failure.class/timeout :timestamp now})
          signals (diag/extract-signals {:failure-events events}
                                        {:min-failure-count 3})]
      (is (empty? signals)))))

(deftest extract-high-iteration-signals-test
  (testing "high iteration phases produce signals"
    (let [phases [{:phase :implement :iterations 5 :timestamp now}
                  {:phase :implement :iterations 4 :timestamp now}]
          signals (diag/extract-signals {:phase-metrics phases}
                                        {:iteration-threshold 3})]
      (is (= 1 (count signals)))
      (is (= :high-iteration-count (:signal/type (first signals))))
      (is (some? (:signal/affected-heuristic (first signals)))))))

;; ---------------------------------------------------------------------------- Correlation

(deftest correlate-symptoms-test
  (testing "related signals cluster together"
    (let [signals [{:signal/type :slo-breach :signal/severity :high
                    :signal/evidence {:sli-name :SLI-1}}
                   {:signal/type :recurring-failure :signal/severity :medium
                    :signal/evidence {:failure-class :failure.class/timeout}}]
          corrs (diag/correlate-symptoms signals)]
      ;; SLO breach + recurring failure are related
      (is (= 1 (count corrs)))
      (is (= :slo-driven (:correlation/type (first corrs))))))

  (testing "unrelated signals form separate clusters"
    (let [signals [{:signal/type :high-iteration-count :signal/severity :medium
                    :signal/affected-heuristic :agent-prompt/implement
                    :signal/evidence {:phase :implement}}
                   {:signal/type :high-iteration-count :signal/severity :medium
                    :signal/affected-heuristic :agent-prompt/verify
                    :signal/evidence {:phase :verify}}]
          corrs (diag/correlate-symptoms signals)]
      (is (= 2 (count corrs)))))

  (testing "empty signals produce empty correlations"
    (is (empty? (diag/correlate-symptoms [])))))

;; ---------------------------------------------------------------------------- Diagnosis

(deftest diagnose-test
  (testing "produces hypotheses from correlations"
    (let [corrs [{:correlation/signals [{:signal/type :recurring-failure
                                          :signal/evidence {:failure-class :failure.class/timeout :count 5}}]
                   :correlation/type :failure-pattern
                   :correlation/confidence 0.6
                   :correlation/hypothesis "Recurring timeouts"}]
          diagnoses (diag/diagnose corrs)]
      (is (= 1 (count diagnoses)))
      (is (uuid? (:diagnosis/id (first diagnoses))))
      (is (= :rule-addition (:diagnosis/suggested-improvement-type (first diagnoses))))
      (is (some? (:diagnosis/affected-heuristic (first diagnoses)))))))

;; ---------------------------------------------------------------------------- Full pipeline

(deftest run-diagnosis-test
  (testing "full pipeline produces expected structure"
    (let [result (diag/run-diagnosis
                  {:slo-checks [{:breached? true :sli/name :SLI-1
                                  :slo/target 0.85 :slo/actual 0.70 :slo/tier :standard}]
                   :failure-events (repeat 5 {:failure/class :failure.class/timeout
                                               :timestamp now})})]
      (is (vector? (:signals result)))
      (is (vector? (:correlations result)))
      (is (vector? (:diagnoses result)))
      (is (pos? (count (:signals result))))
      (is (pos? (count (:diagnoses result)))))))
