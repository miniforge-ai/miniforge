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
(ns ai.miniforge.phase-opsv.model
  "Application-level transformations for the seven OPSV phases."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.evaluation :as evaluation]
   [ai.miniforge.phase-opsv.messages :as msg]
   [ai.miniforge.phase-opsv.protocol :as port]
   [ai.miniforge.phase-opsv.risk :as risk]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-output
  [ctx phase-key]
  (get-in ctx [:execution/phase-results phase-key :result :output]))

(defn- ^{:stratum 0} input-value
  [ctx key]
  (get-in ctx [:execution/input key]))

(defn- ^{:stratum 0} empty-ramp-anomaly
  [ramp]
  (when-not (seq (:steps ramp))
    (anomaly/anomaly :invalid-input (msg/ts :adapter/empty-ramp)
                     {:adapter/result-keys (vec (sort (keys ramp)))})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} adapter-anomaly
  [ctx]
  (when-not (satisfies? port/OPSVAdapter (input-value ctx :opsv/adapter))
    (anomaly/anomaly :invalid-input (msg/ts :adapter/missing)
                     {:input/key :opsv/adapter})))

(defn- ^{:stratum 1} ramp-shape-anomaly
  [ramp]
  (when-not (anomaly/anomaly? ramp)
    (empty-ramp-anomaly ramp)))

(defn- ^{:stratum 1} operational-policy
  [ctx convergence]
  (let [pack (input-value ctx :opsv/experiment-pack)
        targets (:experiment-pack/targets pack)
        evidence-refs (input-value ctx :opsv/evidence-refs)
        selected-step (get-in convergence [:state :selected-step])
        metrics (:step/metrics selected-step)]
    {:operational-policy/id (str (:experiment-pack/id pack) "-policy")
     :operational-policy/version "1.0.0"
     :operational-policy/target-services (:services targets)
     :operational-policy/target-envs (:environments targets)
     :operational-policy/scaling
     {:hpa {:api-version "autoscaling/v2"
            :metric :cpu
            :target-utilization (:hpa-target-utilization metrics)
            :min-replicas (:min-replicas metrics)
            :max-replicas (:max-replicas metrics)}
      :keda {:trigger :backlog
             :target (:keda-backlog-target metrics)}}
     :operational-policy/resources
     {:cpu-request-millicores (:cpu-request-millicores metrics)}
     :operational-policy/guardrails (:experiment-pack/guardrails pack)
     :operational-policy/verification-summary
     {:passed? false :confidence :pending :caveats []}
     :operational-policy/rollback-plan {:strategy :restore-previous-policy}
     :operational-policy/evidence-refs evidence-refs}))

(defn ^{:stratum 1} plan
  [ctx]
  (let [discovery (phase-output ctx :opsv/discover)
        pack (:opsv/experiment-pack discovery)
        risk (opsv/assess-risk
              (risk/factors pack
                            (input-value ctx :opsv/service-criticality))
              (input-value ctx :opsv/risk-thresholds))
        pack-hash (opsv/experiment-pack-hash pack)]
    (cond
      (anomaly/anomaly? risk) risk
      (anomaly/anomaly? pack-hash) pack-hash
      :else (assoc discovery
                   :opsv/risk-result risk
                   :opsv/experiment-pack-hash pack-hash))))

(defn ^{:stratum 1} converge
  [ctx]
  (let [executed (phase-output ctx :opsv/execute)
        config (:experiment-pack/convergence executed)
        initial-state {:steps (:opsv/ramp-steps executed) :history []}
        result (opsv/converge config initial-state
                              evaluation/convergence-step
                              evaluation/convergence-evaluation)]
    (if (anomaly/anomaly? result)
      result
      (assoc executed :opsv/convergence-result result))))

(defn ^{:stratum 1} verify
  [ctx]
  (let [synthesized (phase-output ctx :opsv/synthesize)
        pack (:opsv/experiment-pack synthesized)
        criteria (get-in pack [:experiment-pack/success-criteria :criteria])
        observations (get-in synthesized
                             [:opsv/convergence-result :state
                              :selected-step :step/observations])
        verification (opsv/verify-policy criteria observations
                                         evaluation/criterion-evaluation
                                         :high [])]
    (if (anomaly/anomaly? verification)
      verification
      (let [summary (select-keys verification [:passed? :confidence :caveats])
            policy (assoc (:opsv/operational-policy synthesized)
                          :operational-policy/verification-summary summary)
            validated (opsv/validate-operational-policy policy)]
        (if (anomaly/anomaly? validated)
          validated
          (assoc synthesized
                 :opsv/verification-result verification
                 :opsv/operational-policy validated
                 :opsv/policy-hash (content-hash/content-hash validated)
                 :opsv/metric-snapshot-artifact-refs
                 (input-value ctx :opsv/metric-snapshot-artifact-refs)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} discover
  [ctx]
  (let [pack (opsv/validate-experiment-pack
              (input-value ctx :opsv/experiment-pack))
        invalid-adapter (adapter-anomaly ctx)]
    (cond
      (anomaly/anomaly? pack) pack
      invalid-adapter invalid-adapter
      :else
      (let [adapter (input-value ctx :opsv/adapter)
            drivers (port/discover-signals
                     adapter (:experiment-pack/targets pack))]
        (if (anomaly/anomaly? drivers)
          drivers
          (assoc pack :opsv/experiment-pack pack
                 :opsv/candidate-drivers drivers))))))

(defn ^{:stratum 2} execute
  [ctx]
  (if-let [invalid (adapter-anomaly ctx)]
    invalid
    (let [planned (phase-output ctx :opsv/plan)
          adapter (input-value ctx :opsv/adapter)
          ramp (port/run-guarded-ramp adapter (:opsv/experiment-pack planned))
          empty-ramp (ramp-shape-anomaly ramp)]
      (cond
        (anomaly/anomaly? ramp) ramp
        empty-ramp empty-ramp
        :else (merge planned
                     {:opsv/environment-fingerprint
                      (:environment-fingerprint ramp)
                      :opsv/ramp-steps (:steps ramp)})))))

(defn ^{:stratum 2} synthesize
  [ctx]
  (let [converged (phase-output ctx :opsv/converge)
        proposal (operational-policy ctx (:opsv/convergence-result converged))
        validated (opsv/validate-operational-policy proposal)]
    (if (anomaly/anomaly? validated)
      validated
      (assoc converged
             :opsv/operational-policy validated
             :opsv/policy-hash (content-hash/content-hash validated)))))
