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
   [ai.miniforge.phase-opsv.adapter :as adapter]
   [ai.miniforge.phase-opsv.evaluation :as evaluation]
   [ai.miniforge.phase-opsv.flow :as flow]
   [ai.miniforge.phase-opsv.policy :as policy]
   [ai.miniforge.phase-opsv.protocol :as port]
   [ai.miniforge.phase-opsv.risk :as risk]
   [ai.miniforge.phase-opsv.verification :as verification]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-output
  [ctx phase-key]
  (get-in ctx [:execution/phase-results phase-key :result :output]))

(defn- ^{:stratum 0} input-value
  [ctx key]
  (get-in ctx [:execution/input key]))

(defn- ^{:stratum 0} adapter-value
  [ctx]
  (or (get-in ctx [:execution/opts :opsv/adapter])
      (get-in ctx [:execution/input :opsv/adapter])))

(defn- ^{:stratum 0} converged-output
  [executed]
  (let [config (get-in executed [:opsv/experiment-pack
                                 :experiment-pack/convergence])
        initial-state {:steps (:opsv/ramp-steps executed) :history []}
        result (opsv/converge config initial-state
                              evaluation/convergence-step
                              evaluation/convergence-evaluation)]
    (if (anomaly/anomaly? result)
      result
      (assoc executed :opsv/convergence-result result))))

(defn- ^{:stratum 0} synthesized-output
  [ctx converged]
  (let [proposal (policy/operational-policy
                  ctx (:opsv/convergence-result converged))
        validated (opsv/validate-operational-policy proposal)]
    (if (anomaly/anomaly? validated)
      validated
      (assoc converged
             :opsv/operational-policy validated
             :opsv/policy-hash (content-hash/content-hash validated)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} planned-output
  [ctx discovery]
  (let [pack (:opsv/experiment-pack discovery)
        risk-result (opsv/assess-risk
                     (risk/factors pack
                                   (input-value ctx :opsv/service-criticality))
                     (input-value ctx :opsv/risk-thresholds))
        pack-hash (opsv/experiment-pack-hash pack)]
    (cond
      (anomaly/anomaly? risk-result) risk-result
      (anomaly/anomaly? pack-hash) pack-hash
      :else (assoc discovery :opsv/risk-result risk-result
                   :opsv/experiment-pack-hash pack-hash))))

(defn ^{:stratum 1} discover
  [ctx]
  (let [pack (opsv/validate-experiment-pack
              (input-value ctx :opsv/experiment-pack))
        runtime-adapter (adapter-value ctx)
        invalid-adapter (adapter/adapter-anomaly runtime-adapter)]
    (cond
      (anomaly/anomaly? pack) pack
      invalid-adapter invalid-adapter
      :else
      (let [drivers (port/discover-signals
                     runtime-adapter (:experiment-pack/targets pack))]
        (if (anomaly/anomaly? drivers)
          drivers
          {:opsv/experiment-pack pack
           :opsv/candidate-drivers drivers})))))

(defn- ^{:stratum 1} executed-output
  [ctx planned]
  (let [runtime-adapter (adapter-value ctx)
        invalid-adapter (adapter/adapter-anomaly runtime-adapter)]
    (if invalid-adapter
      invalid-adapter
      (let [ramp (port/run-guarded-ramp
                  runtime-adapter (:opsv/experiment-pack planned))
            invalid-ramp (adapter/ramp-shape-anomaly ramp)]
        (cond
          (anomaly/anomaly? ramp) ramp
          invalid-ramp invalid-ramp
          :else (assoc planned
                       :opsv/environment-fingerprint
                       (:environment-fingerprint ramp)
                       :opsv/ramp-steps (:steps ramp)))))))

(defn ^{:stratum 1} converge
  [ctx]
  (flow/continue (phase-output ctx :opsv/execute) converged-output))

(defn ^{:stratum 1} synthesize
  [ctx]
  (flow/continue (phase-output ctx :opsv/converge)
                 (partial synthesized-output ctx)))

(defn ^{:stratum 1} verify
  [ctx]
  (flow/continue (phase-output ctx :opsv/synthesize)
                 (partial verification/output ctx)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} plan
  [ctx]
  (flow/continue (phase-output ctx :opsv/discover)
                 (partial planned-output ctx)))

(defn ^{:stratum 2} execute
  [ctx]
  (flow/continue (phase-output ctx :opsv/plan)
                 (partial executed-output ctx)))
