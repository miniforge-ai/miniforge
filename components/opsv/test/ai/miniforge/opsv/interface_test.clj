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
(ns ai.miniforge.opsv.interface-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.interface :as opsv]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; Canonical fixtures
(def ^{:stratum 0} valid-pack
  {:experiment-pack/id "catalog-ramp"
   :experiment-pack/version "1.0.0"
   :experiment-pack/targets
   {:services ["catalog"]
    :environments ["staging"]}
   :experiment-pack/workload
   {:profile :ramp
    :mix [{:request :read :weight 1.0}]
    :warmup-seconds 30
    :cooldown-seconds 30}
   :experiment-pack/success-criteria
   {:latency-p95-ms 200 :error-rate 0.01 :stable-seconds 60}
   :experiment-pack/guardrails
   {:abort-error-rate 0.05 :max-replicas 20 :window :staging}
   :experiment-pack/convergence
   {:parameters {:min-replicas 2 :max-replicas 20}
    :iteration-limit 8
    :confidence-threshold 0.9
    :minimum-measurement-window-seconds 30
    :required-repetitions 2}
   :experiment-pack/actuation-intent :recommend-only
   :experiment-pack/required-instrumentation [:cpu :backlog]})

(def ^{:stratum 0} valid-policy
  {:operational-policy/id "catalog-scaling"
   :operational-policy/version "1.0.0"
   :operational-policy/target-services ["catalog"]
   :operational-policy/target-envs ["staging"]
   :operational-policy/scaling
   {:drivers [:cpu :backlog] :thresholds {:cpu 0.7} :min 2 :max 20}
   :operational-policy/resources {:requests {:cpu "500m"}}
   :operational-policy/guardrails {:max-replicas 20}
   :operational-policy/verification-summary
   {:passed? true :confidence :high :caveats []}
   :operational-policy/rollback-plan {:action :restore-manifest}
   :operational-policy/evidence-refs
   [#uuid "00000000-0000-0000-0000-000000000001"]})

(def ^{:stratum 0} valid-risk
  {:score 0.35
   :level :medium
   :factors [{:factor :environment
              :input :staging
              :contribution 0.1
              :rationale "Staging is isolated"}]})

(def ^{:stratum 0} valid-verification
  {:passed? true
   :criteria-evaluation
   [{:criterion/id "latency-p95"
     :criterion/passed? true
     :criterion/observed 175
     :criterion/expected 200
     :criterion/reason-code :within-threshold}]
   :confidence :high
   :caveats []})

(def ^{:stratum 0} valid-actuation
  {:requested-actuation-mode :pr-only
   :effective-actuation-mode :pr-only
   :governed-effects
   [{:evidence/intent-id #uuid "00000000-0000-0000-0000-000000000002"
     :evidence/oir-id #uuid "00000000-0000-0000-0000-000000000003"
     :evidence/capability-id "grant-123"}]
   :pr-refs ["https://example.test/pr/1"]
   :apply-refs []
   :postcondition-artifact-refs []
   :rollback {:status :not-triggered :artifact-refs []}})

(defn ^{:stratum 0} invalid?
  [value]
  (anomaly/anomaly? value))

;------------------------------------------------------------------------------ Layer 1

;; Contract validation
(deftest ^{:stratum 1} test-required-experiment-pack-contract
  (is (= valid-pack (opsv/validate-experiment-pack valid-pack)))
  (testing "given a missing top-level field → validation returns an anomaly"
    (doseq [field (keys valid-pack)]
      (is (invalid? (opsv/validate-experiment-pack (dissoc valid-pack field)))
          (str "missing " field))))
  (testing "given a missing target or workload field → validation returns an anomaly"
    (doseq [[path field] [[[:experiment-pack/targets] :services]
                          [[:experiment-pack/targets] :environments]
                          [[:experiment-pack/workload] :profile]
                          [[:experiment-pack/workload] :mix]
                          [[:experiment-pack/workload] :warmup-seconds]
                          [[:experiment-pack/workload] :cooldown-seconds]]]
      (is (invalid? (opsv/validate-experiment-pack
                     (update-in valid-pack path dissoc field)))
          (str "missing " field))))
  (testing "given incomplete convergence controls → validation returns an anomaly"
    (doseq [field [:parameters :iteration-limit :confidence-threshold
                   :minimum-measurement-window-seconds
                   :required-repetitions]]
      (is (invalid? (opsv/validate-experiment-pack
                     (update valid-pack :experiment-pack/convergence
                             dissoc field)))
          (str "missing convergence control " field))))
  (testing "given an unknown key or mode → validation returns an anomaly"
    (is (invalid? (opsv/validate-experiment-pack
                   (assoc valid-pack :experiment-pack/unknown true))))
    (is (invalid? (opsv/validate-experiment-pack
                   (assoc valid-pack :experiment-pack/actuation-intent :apply)))))
  (testing "given invalid target or instrumentation identifiers → validation returns an anomaly"
    (is (invalid? (opsv/validate-experiment-pack
                   (assoc-in valid-pack
                             [:experiment-pack/targets :services 0]
                             :catalog))))
    (is (invalid? (opsv/validate-experiment-pack
                   (assoc-in valid-pack
                             [:experiment-pack/required-instrumentation 0]
                             "cpu"))))))

(deftest ^{:stratum 1} test-required-operational-policy-contract
  (is (= valid-policy (opsv/validate-operational-policy valid-policy)))
  (doseq [field (keys valid-policy)]
    (is (invalid? (opsv/validate-operational-policy (dissoc valid-policy field)))
        (str "missing " field))))

(deftest ^{:stratum 1} test-explainable-risk-contract
  (is (= valid-risk (opsv/validate-risk valid-risk)))
  (doseq [score [-0.01 1.01]]
    (is (invalid? (opsv/validate-risk (assoc valid-risk :score score)))))
  (doseq [field [:input :contribution :rationale]]
    (is (invalid? (opsv/validate-risk
                   (update valid-risk :factors
                           #(mapv (fn [factor] (dissoc factor field)) %))))
        (str "factor missing " field))))

(deftest ^{:stratum 1} test-per-criterion-verification-contract
  (is (= valid-verification (opsv/validate-verification valid-verification)))
  (is (invalid? (opsv/validate-verification
                 (update-in valid-verification [:criteria-evaluation 0]
                            dissoc :criterion/reason-code)))))

(deftest ^{:stratum 1} test-correlated-actuation-contract
  (is (= valid-actuation (opsv/validate-actuation valid-actuation)))
  (doseq [mode opsv/requested-actuation-modes]
    (is (= mode
           (:requested-actuation-mode
            (opsv/validate-actuation
             (assoc valid-actuation :requested-actuation-mode mode))))))
  (doseq [mode opsv/effective-actuation-modes]
    (is (= mode
           (:effective-actuation-mode
            (opsv/validate-actuation
             (assoc valid-actuation :effective-actuation-mode mode))))))
  (doseq [mode [:none :apply :automatic]]
    (is (invalid? (opsv/validate-actuation
                   (assoc valid-actuation :requested-actuation-mode mode)))))
  (doseq [mode [:apply :automatic]]
    (is (invalid? (opsv/validate-actuation
                   (assoc valid-actuation :effective-actuation-mode mode)))))
  (is (= (assoc valid-actuation :effective-actuation-mode :none)
         (opsv/validate-actuation
          (assoc valid-actuation :effective-actuation-mode :none))))
  (doseq [field [:evidence/intent-id
                 :evidence/oir-id
                 :evidence/capability-id]]
    (is (invalid? (opsv/validate-actuation
                   (update valid-actuation :governed-effects
                           #(mapv (fn [effect] (dissoc effect field)) %))))
        (str "effect missing " field))))

(deftest ^{:stratum 1} test-closed-top-level-contracts
  (doseq [[validate value] [[opsv/validate-experiment-pack valid-pack]
                            [opsv/validate-operational-policy valid-policy]
                            [opsv/validate-risk valid-risk]
                            [opsv/validate-verification valid-verification]
                            [opsv/validate-actuation valid-actuation]]]
    (is (invalid? (validate (assoc value :opsv/unknown true))))))

(deftest ^{:stratum 1} test-typed-validation-anomaly
  (let [result (opsv/validate-experiment-pack
                (dissoc valid-pack :experiment-pack/id))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (= :opsv/experiment-pack
           (get-in result [:anomaly/data :anomaly/schema])))))

;; Validated content identity
(deftest ^{:stratum 1} test-canonical-experiment-pack-hash
  (let [ordered (into (sorted-map) valid-pack)
        reordered (into (sorted-map-by
                         (fn [left right]
                           (compare (str right) (str left))))
                        valid-pack)]
    (is (= (opsv/experiment-pack-hash ordered)
           (opsv/experiment-pack-hash reordered)))
    (is (re-matches #"[0-9a-f]{64}" (opsv/experiment-pack-hash valid-pack))))
  (is (invalid? (opsv/experiment-pack-hash
                 (dissoc valid-pack :experiment-pack/id)))))
