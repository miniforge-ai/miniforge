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
