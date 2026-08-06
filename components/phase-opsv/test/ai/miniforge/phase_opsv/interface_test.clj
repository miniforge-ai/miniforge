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
(ns ai.miniforge.phase-opsv.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.phase-opsv.events :as events]
   [ai.miniforge.phase-opsv.interface :as opsv-phase]
   [ai.miniforge.phase-opsv.risk :as risk]
   [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} namespaced-phase-contract-test
  (testing "the application contract uses collision-free phase keys"
    (is (= [:opsv/discover :opsv/plan :opsv/execute :opsv/converge
            :opsv/synthesize :opsv/verify :opsv/actuate]
           opsv-phase/phase-keys))))

(deftest ^{:stratum 0} non-finite-blast-risk-test
  (let [pack (-> (support/execution-context nil)
                 (get-in [:execution/input :opsv/experiment-pack])
                 (assoc-in [:experiment-pack/guardrails :blast-radius :replica-delta]
                           Double/NaN))]
    (is (= 0.25 (:contribution (second (risk/factors pack nil)))))))

(deftest ^{:stratum 0} missing-confidence-threshold-event-test
  (let [stream (event-stream/create-event-stream)
        workflow-id (random-uuid)
        evidence-id (random-uuid)]
    (events/emit-phase-events!
     {:execution/id workflow-id
      :execution/input {:opsv/evidence-bundle-id evidence-id}
      :event-stream stream}
     :opsv/synthesize
     {:opsv/policy-hash "policy-hash"
      :opsv/experiment-pack {:experiment-pack/convergence {}}
      :opsv/convergence-result {:evaluation {:confidence 0.9}}})
    (is (= :low (:opsv/confidence (first (event-stream/get-events stream)))))))

(deftest ^{:stratum 0} seven-phase-value-flow-test
  (let [result (reduce support/run-transformation
                       (support/execution-context
                        (support/test-adapter support/ramp-steps))
                       support/handlers)
        discovery (support/phase-output result :opsv/discover)
        planning (support/phase-output result :opsv/plan)
        convergence (support/phase-output result :opsv/converge)
        synthesis (support/phase-output result :opsv/synthesize)
        verification (support/phase-output result :opsv/verify)
        actuation (support/phase-output result :opsv/actuate)]
    (testing "adapter observations flow through bounded convergence"
      (is (= #{:opsv/experiment-pack :opsv/candidate-drivers} (set (keys discovery))))
      (is (= #{:cpu :backlog} (set (map :driver (:opsv/candidate-drivers discovery)))))
      (is (= :success-criteria-satisfied
             (get-in convergence [:opsv/convergence-result :terminal-reason])))
      (is (= 2 (get-in convergence [:opsv/convergence-result :iterations]))))
    (testing "risk and verification evidence derive from the experiment"
      (is (= ["staging"] (get-in planning [:opsv/risk-result :factors 0 :input])))
      (is (= :low (get-in verification [:opsv/verification-result :confidence]))))
    (testing "synthesis returns interoperable scaling recommendations"
      (is (= 65 (get-in synthesis
                        [:opsv/operational-policy
                         :operational-policy/scaling :hpa
                         :target-utilization])))
      (is (= 100 (get-in synthesis
                         [:opsv/operational-policy
                          :operational-policy/scaling :keda :target])))
      (is (= (content-hash/content-hash
              (:opsv/operational-policy verification))
             (:opsv/policy-hash verification))))
    (testing "a promoted PR has no direct external effects"
      (is (= :pr-only
             (get-in actuation
                     [:opsv/actuation-record :effective-actuation-mode])))
      (is (= [] (get-in actuation
                        [:opsv/actuation-record :governed-effects]))))))

(deftest ^{:stratum 0} fail-closed-boundary-test
  (testing "discover rejects a missing adapter as anomaly data"
    (is (anomaly/anomaly?
         (opsv-phase/discover (support/execution-context nil)))))
  (testing "execute rejects an adapter ramp with no observations"
    (let [adapter (support/test-adapter [])
          discovered (support/run-transformation
                      (support/execution-context adapter)
                      (first support/handlers))
          planned (support/run-transformation
                   discovered (second support/handlers))]
      (is (anomaly/anomaly? (opsv-phase/execute planned)))))
  (testing "execute rejects an adapter ramp without a fingerprint"
    (let [adapter (support/test-adapter support/ramp-steps nil)
          discovered (support/run-transformation
                      (support/execution-context adapter)
                      (first support/handlers))
          planned (support/run-transformation
                   discovered (second support/handlers))]
      (is (anomaly/anomaly? (opsv-phase/execute planned)))))
  (testing "execute rejects a non-map adapter result as anomaly data"
    (let [adapter (support/test-adapter-result [])
          discovered (support/run-transformation
                      (support/execution-context adapter)
                      (first support/handlers))
          planned (support/run-transformation
                   discovered (second support/handlers))
          result (opsv-phase/execute planned)]
      (is (anomaly/anomaly? result))
      (is (= "clojure.lang.PersistentVector"
             (get-in result [:anomaly/data :adapter/result-type])))))
  (testing "risk classification fails when policy thresholds are absent"
    (let [ctx (update (support/execution-context
                       (support/test-adapter support/ramp-steps))
                      :execution/input dissoc :opsv/risk-thresholds)
          discovered (support/run-transformation ctx
                                                 (first support/handlers))]
      (is (anomaly/anomaly? (opsv-phase/plan discovered)))))
  (testing "unknown environments receive the maximum environment contribution"
    (let [ctx (assoc-in (support/execution-context
                         (support/test-adapter support/ramp-steps))
                        [:execution/input :opsv/experiment-pack
                         :experiment-pack/targets :environments]
                        ["unknown"])
          discovered (support/run-transformation ctx (first support/handlers))
          planned (support/run-transformation discovered
                                              (second support/handlers))]
      (is (= 0.4 (get-in (support/phase-output planned :opsv/plan)
                         [:opsv/risk-result :factors 0 :contribution])))))
  (testing "missing or invalid gate results cannot promote a requested PR"
    (doseq [gate-results [::missing nil [] :invalid
                          [{:gate/id :instrumentation :gate/passed? true}]]]
      (let [ctx (cond->
                 (-> (support/execution-context
                      (support/test-adapter support/ramp-steps))
                     (update :execution/input dissoc :opsv/gate-results))
                  (not= ::missing gate-results)
                  (assoc-in [:execution/input :opsv/gate-results] gate-results))
            result (reduce support/run-transformation ctx support/handlers)]
        (is (= :recommend-only
               (get-in (support/phase-output result :opsv/actuate)
                       [:opsv/actuation-record :effective-actuation-mode]))))))
  (testing "every downstream transformation preserves its upstream anomaly"
    (let [upstream (anomaly/anomaly :invalid-input "upstream failed" {})]
      (doseq [[[upstream-key _] [phase-key handler]]
              (partition 2 1 support/handlers)]
        (let [ctx (assoc-in (support/execution-context nil)
                            [:execution/phase-results upstream-key
                             :result :output]
                            upstream)]
          (is (= upstream (handler ctx))
              (str phase-key " must preserve " upstream-key " anomalies")))))))

(deftest ^{:stratum 0} flat-success-criteria-flow-test
  (let [ctx (assoc-in
             (support/execution-context
              (support/test-adapter support/ramp-steps))
             [:execution/input :opsv/experiment-pack :experiment-pack/success-criteria]
             {:cpu-utilization 0.7 :backlog-per-replica 100.0})
        result (reduce support/run-transformation ctx support/handlers)
        evaluations (get-in (support/phase-output result :opsv/verify)
                            [:opsv/verification-result :criteria-evaluation])]
    (is (= #{"cpu-utilization" "backlog-per-replica"}
           (set (map :criterion/id evaluations))))))
