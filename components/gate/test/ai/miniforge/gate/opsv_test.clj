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
(ns ai.miniforge.gate.opsv-test
  (:require
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pack
  {:experiment-pack/targets {:services ["catalog"]
                             :environments ["staging"]}
   :experiment-pack/required-instrumentation [:latency]
   :experiment-pack/guardrails
   {:blast-radius {:replica-delta 2 :node-delta 1
                   :namespaces ["catalog"]}
    :abort-thresholds {:error-budget-burn 2.0 :saturation 0.9
                       :tail-latency 500.0}}
   :experiment-pack/actuation-intent :apply-allowed})

(def ^{:stratum 0} context
  {:opsv/instrumentation-status
   {:latency {:available? true :reliable? true}}
   :opsv/allowed-environments #{"staging"}
   :opsv/time-window-open-environments #{"staging"}
   :opsv/production-environments #{"production"}
   :opsv/production-allowlist #{}
   :opsv/blast-radius-limits
   {:max-replica-delta 2 :max-node-delta 1
    :allowed-namespaces #{"catalog"}}
   :opsv/apply-enabled? true
   :opsv/apply-service-allowlist #{"catalog"}})

(def ^{:stratum 0} evidence
  {:opsv/experiment-pack-hash "sha256:pack"
   :opsv/environment-fingerprint {:cluster "staging"}
   :opsv/metric-snapshot-artifact-refs
   [#uuid "00000000-0000-0000-0000-000000000201"]})

(def ^{:stratum 0} opsv-policy-pack
  (-> "policy_pack/packs/opsv-governance-1.0.0.pack.edn"
      io/resource slurp edn/read-string))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} passing-cases
  [[:opsv/instrumentation-gate :instrumentation pack context]
   [:opsv/environment-gate :environment pack context]
   [:opsv/blast-radius-gate :blast-radius pack context]
   [:opsv/abort-gate :abort pack context]
   [:opsv/actuation-gate :actuation pack context]
   [:opsv/evidence-completeness-gate :evidence-completeness evidence context]])

(def ^{:stratum 1} failing-cases
  [[:opsv/instrumentation-gate :instrumentation pack
    (assoc-in context [:opsv/instrumentation-status :latency :reliable?] false)]
   [:opsv/environment-gate :environment pack
    (assoc context :opsv/time-window-open-environments #{})]
   [:opsv/blast-radius-gate :blast-radius pack
    (assoc-in context [:opsv/blast-radius-limits :max-replica-delta] 1)]
   [:opsv/abort-gate :abort
    (update-in pack [:experiment-pack/guardrails :abort-thresholds]
               dissoc :tail-latency)
    context]
   [:opsv/actuation-gate :actuation pack
    (assoc context :opsv/apply-enabled? false)]
   [:opsv/evidence-completeness-gate :evidence-completeness
    (assoc evidence :opsv/metric-snapshot-artifact-refs []) context]])

(deftest ^{:stratum 1} production-always-requires-explicit-allowlisting
  (let [production-pack (assoc-in pack
                                  [:experiment-pack/targets :environments]
                                  ["production"])
        production-context (assoc context
                                  :opsv/allowed-environments #{"production"}
                                  :opsv/time-window-open-environments
                                  #{"production"}
                                  :opsv/production-environments #{})
        result (gate/check-gate :opsv/environment-gate production-pack
                                production-context)]
    (is (false? (:passed? result)))
    (is (= ["production"]
           (get-in result [:errors 0 :remediation :details
                           :production-not-allowlisted])))))

(deftest ^{:stratum 1} blast-radius-enforces-each-policy-axis
  (doseq [ctx [(assoc-in context
                         [:opsv/blast-radius-limits :max-replica-delta] 1)
               (assoc-in context
                         [:opsv/blast-radius-limits :max-node-delta] 0)
               (assoc-in context
                         [:opsv/blast-radius-limits :max-node-delta] -1)
               (assoc-in context
                         [:opsv/blast-radius-limits :allowed-namespaces] #{})]]
    (is (false? (:passed? (gate/check-gate :opsv/blast-radius-gate
                                           pack ctx))))))

(deftest ^{:stratum 1} malformed-safety-inputs-fail-closed
  (is (false? (:passed? (gate/check-gate
                         :opsv/instrumentation-gate
                         (dissoc pack :experiment-pack/required-instrumentation)
                         context))))
  (is (false? (:passed? (gate/check-gate
                         :opsv/abort-gate
                         (assoc-in pack
                                   [:experiment-pack/guardrails
                                    :abort-thresholds :tail-latency]
                                   ##NaN)
                         context)))))

(deftest ^{:stratum 1} safer-actuation-does-not-require-apply-authority
  (let [recommendation (assoc pack :experiment-pack/actuation-intent
                              :recommend-only)]
    (is (true? (:passed? (gate/check-gate :opsv/actuation-gate
                                          recommendation {}))))))

(deftest ^{:stratum 1} evidence-gate-requires-each-n4-field
  (doseq [incomplete [(dissoc evidence :opsv/experiment-pack-hash)
                      (assoc evidence :opsv/environment-fingerprint {})
                      (assoc evidence :opsv/metric-snapshot-artifact-refs [])]]
    (is (false? (:passed? (gate/check-gate
                           :opsv/evidence-completeness-gate
                           incomplete {}))))))

(deftest ^{:stratum 1} policy-pack-rules-run-the-mechanical-gates
  (let [invalid-context (assoc-in context
                                  [:opsv/instrumentation-status
                                   :latency :available?]
                                  false)
        result (policy-pack/check-artifact opsv-policy-pack pack
                                           (assoc invalid-context
                                                  :phase :discover))]
    (is (false? (:passed? result)))
    (is (= :opsv/instrumentation-gate
           (-> result :blocking first :code)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} all-opsv-gates-pass-compliant-input
  (doseq [[gate-key _gate-id artifact ctx] passing-cases]
    (testing (str gate-key)
      (let [result (gate/check-gate gate-key artifact ctx)]
        (is (true? (:passed? result)))
        (is (empty? (:errors result)))))))

(deftest ^{:stratum 2} all-opsv-gates-fail-with-typed-remediation
  (doseq [[gate-key gate-id artifact ctx] failing-cases]
    (testing (str gate-key)
      (let [result (gate/check-gate gate-key artifact ctx)
            error (first (:errors result))]
        (is (false? (:passed? result)))
        (is (= :opsv/gate-failed (:type error)))
        (is (= gate-id (:gate/id error)))
        (is (keyword? (:reason-code error)))
        (is (string? (:message error)))
        (is (keyword? (get-in error [:remediation :action])))
        (is (string? (get-in error [:remediation :summary])))
        (is (map? (get-in error [:remediation :details])))))))

(deftest ^{:stratum 2} opsv-gates-are-registered
  (doseq [[gate-key] passing-cases]
    (is (contains? (gate/list-gates) gate-key))))
