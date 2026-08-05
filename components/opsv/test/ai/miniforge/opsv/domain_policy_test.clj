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
(ns ai.miniforge.opsv.domain-policy-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.interface :as opsv]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} risk-factors
  [{:factor :environment-class
    :input :staging
    :contribution 0.1
    :rationale "Staging is isolated"}
   {:factor :blast-radius
    :input {:services 2}
    :contribution 0.2
    :rationale "Two services are targeted"}
   {:factor :actuation-requested
    :input true
    :contribution 0.15
    :rationale "External mutation is requested"}
   {:factor :service-criticality
    :input :high
    :contribution 0.1
    :rationale "The service is customer-facing"}])

(def ^{:stratum 0} risk-thresholds
  {:medium 0.25 :high 0.5 :critical 0.75})

(def ^{:stratum 0} convergence-config
  {:parameters {:load-step 10 :config-step 1}
   :iteration-limit 3
   :confidence-threshold 0.8
   :minimum-measurement-window-seconds 30
   :required-repetitions 2})

(defn ^{:stratum 0} increment-state
  [state _iteration _config]
  (update state :value inc))

(defn ^{:stratum 0} convergence-evaluation
  [overrides]
  (merge {:success-criteria-satisfied? false
          :headroom-satisfied? false
          :guardrail-abort? false
          :confidence 0.2
          :measurement-window-seconds 30
          :repetitions 2}
         overrides))

(def ^{:stratum 0} verification-criteria
  [{:criterion/id "latency-p95" :criterion/expected 200}
   {:criterion/id "error-rate" :criterion/expected 0.01}])

(def ^{:stratum 0} verification-observations
  {"latency-p95" 175 "error-rate" 0.02})

(defn ^{:stratum 0} threshold-evaluator
  [criterion observed]
  (if (<= observed (:criterion/expected criterion))
    {:passed? true :reason-code :within-threshold}
    {:passed? false :reason-code :threshold-exceeded}))

(def ^{:stratum 0} apply-decision
  {:requested-actuation-mode :apply-allowed
   :verification-passed? true
   :gate-results [{:gate/id :instrumentation :gate/passed? true}
                  {:gate/id :environment :gate/passed? true}
                  {:gate/id :blast-radius :gate/passed? true}
                  {:gate/id :abort :gate/passed? true}
                  {:gate/id :actuation :gate/passed? true}
                  {:gate/id :evidence-completeness :gate/passed? true}]
   :safe-mode? false
   :pr-capability-valid? true
   :apply-capability-valid? true
   :rollback-verified? true
   :postconditions-configured? true})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-assess-risk-is-additive-and-explainable
  (let [result (opsv/assess-risk risk-factors risk-thresholds)]
    (is (= 0.55 (:score result)))
    (is (= :high (:level result)))
    (is (= risk-factors (:factors result)))))

(deftest ^{:stratum 1} test-assess-risk-normalizes-score
  (let [factors (mapv #(assoc % :contribution 0.4) risk-factors)
        result (opsv/assess-risk factors risk-thresholds)]
    (is (= 1.0 (:score result)))
    (is (= :critical (:level result)))))

(deftest ^{:stratum 1} test-assess-risk-classifies-every-level
  (doseq [[score level] [[0.1 :low] [0.25 :medium]
                         [0.5 :high] [0.75 :critical]]]
    (let [factors (-> (mapv #(assoc % :contribution 0.0) risk-factors)
                      (assoc-in [0 :contribution] score))]
      (is (= level (:level (opsv/assess-risk factors risk-thresholds)))))))

(deftest ^{:stratum 1} test-assess-risk-validates-transparent-policy-input
  (testing "required factors are present exactly once"
    (is (anomaly/anomaly?
         (opsv/assess-risk (vec (rest risk-factors)) risk-thresholds)))
    (is (anomaly/anomaly?
         (opsv/assess-risk (conj risk-factors (first risk-factors))
                           risk-thresholds))))
  (testing "contributions are normalized and thresholds are ordered"
    (is (anomaly/anomaly?
         (opsv/assess-risk
          (assoc-in risk-factors [0 :contribution] 1.1)
          risk-thresholds)))
    (is (anomaly/anomaly?
         (opsv/assess-risk risk-factors
                           {:medium 0.5 :high 0.25 :critical 0.75})))))

(deftest ^{:stratum 1} test-converge-stops-on-every-terminal-condition
  (doseq [[expected evaluation]
          [[:success-criteria-satisfied
            (convergence-evaluation
             {:success-criteria-satisfied? true :headroom-satisfied? true})]
           [:guardrail-abort
            (convergence-evaluation {:guardrail-abort? true})]
           [:confidence-threshold-reached
            (convergence-evaluation {:confidence 0.8})]]]
    (let [result (opsv/converge convergence-config
                                {:value 0}
                                increment-state
                                (fn [_state _iteration _config] evaluation))]
      (is (= expected (:terminal-reason result)))
      (is (= 1 (:iterations result)))))
  (let [result (opsv/converge
                convergence-config
                {:value 0}
                increment-state
                (fn [_state _iteration _config]
                  (convergence-evaluation {})))]
    (is (= :iteration-limit-reached (:terminal-reason result)))
    (is (= 3 (:iterations result)))
    (is (= {:value 3} (:state result)))))

(deftest ^{:stratum 1} test-converge-enforces-stabilization
  (let [result (opsv/converge
                convergence-config
                {:value 0}
                increment-state
                (fn [_state iteration _config]
                  (convergence-evaluation
                   {:success-criteria-satisfied? true
                    :headroom-satisfied? true
                    :measurement-window-seconds (* iteration 15)
                    :repetitions iteration})))]
    (is (= :success-criteria-satisfied (:terminal-reason result)))
    (is (= 2 (:iterations result)))))

(deftest ^{:stratum 1} test-converge-validates-boundary-contracts
  (is (anomaly/anomaly?
       (opsv/converge (assoc convergence-config :iteration-limit 0)
                      {:value 0}
                      increment-state
                      (fn [_state _iteration _config]
                        (convergence-evaluation {})))))
  (is (anomaly/anomaly?
       (opsv/converge (assoc convergence-config :parameters {})
                      {:value 0}
                      increment-state
                      (fn [_state _iteration _config]
                        (convergence-evaluation {})))))
  (is (anomaly/anomaly?
       (opsv/converge convergence-config
                      {:value 0}
                      increment-state
                      (fn [_state _iteration _config]
                        {:guardrail-abort? true})))))

(deftest ^{:stratum 1} test-verify-policy-emits-every-criterion
  (let [result (opsv/verify-policy verification-criteria
                                   verification-observations
                                   threshold-evaluator
                                   :medium
                                   ["Dependency noise"])]
    (is (false? (:passed? result)))
    (is (= ["latency-p95" "error-rate"]
           (mapv :criterion/id (:criteria-evaluation result))))
    (is (= [true false]
           (mapv :criterion/passed? (:criteria-evaluation result))))
    (is (= :medium (:confidence result)))
    (is (= ["Dependency noise"] (:caveats result)))))

(deftest ^{:stratum 1} test-verify-policy-validates-input-and-evaluator-output
  (is (anomaly/anomaly?
       (opsv/verify-policy [] {} threshold-evaluator :low [])))
  (is (anomaly/anomaly?
       (opsv/verify-policy verification-criteria
                           verification-observations
                           (fn [_criterion _observed] {:passed? true})
                           :high
                           [])))
  (is (anomaly/anomaly?
       (opsv/verify-policy verification-criteria
                           verification-observations
                           (fn [_criterion _observed]
                             {:passed? true
                              :reason-code :within-threshold
                              :unknown true})
                           :high
                           [])))
  (is (anomaly/anomaly?
       (opsv/verify-policy (conj verification-criteria
                                 (first verification-criteria))
                           verification-observations
                           threshold-evaluator
                           :high
                           []))))

(deftest ^{:stratum 1} test-effective-actuation-requires-every-apply-precondition
  (is (= :apply-allowed (opsv/effective-actuation apply-decision)))
  (doseq [field [:verification-passed?
                 :apply-capability-valid?
                 :rollback-verified?
                 :postconditions-configured?]]
    (is (not= :apply-allowed
              (opsv/effective-actuation (assoc apply-decision field false)))
        (str "apply blocked by " field)))
  (is (not= :apply-allowed
            (opsv/effective-actuation
             (assoc-in apply-decision [:gate-results 0 :gate/passed?] false)))))

(deftest ^{:stratum 1} test-effective-actuation-degrades-without-promotion
  (is (= :recommend-only
         (opsv/effective-actuation
          (assoc apply-decision :requested-actuation-mode :recommend-only))))
  (is (= :pr-only
         (opsv/effective-actuation
          (assoc apply-decision :requested-actuation-mode :pr-only))))
  (is (= :pr-only
         (opsv/effective-actuation
          (assoc apply-decision :apply-capability-valid? false))))
  (is (= :recommend-only
         (opsv/effective-actuation
          (assoc apply-decision
                 :apply-capability-valid? false
                 :pr-capability-valid? false))))
  (is (= :recommend-only
         (opsv/effective-actuation
          (assoc apply-decision
                 :requested-actuation-mode :pr-only
                 :pr-capability-valid? false)))))

(deftest ^{:stratum 1} test-safe-mode-forces-no-actuation
  (doseq [requested opsv/requested-actuation-modes]
    (is (= :none
           (opsv/effective-actuation
            (assoc apply-decision
                   :requested-actuation-mode requested
                   :safe-mode? true))))))

(deftest ^{:stratum 1} test-effective-actuation-validates-gate-evidence
  (is (anomaly/anomaly?
       (opsv/effective-actuation (assoc apply-decision :gate-results []))))
  (is (anomaly/anomaly?
       (opsv/effective-actuation
        (update apply-decision :gate-results pop))))
  (is (anomaly/anomaly?
       (opsv/effective-actuation
        (update apply-decision :gate-results
                conj {:gate/id :rollback})))))
