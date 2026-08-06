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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} adapter-value
  [ctx]
  (or (get-in ctx [:execution/opts :opsv/adapter])
      (input-value ctx :opsv/adapter)))

(defn ^{:stratum 1} plan
  [ctx]
  (flow/continue
   (phase-output ctx :opsv/discover)
   (fn [discovery]
     (let [pack (:opsv/experiment-pack discovery)
           risk-result (opsv/assess-risk
                        (risk/factors
                         pack (input-value ctx :opsv/service-criticality))
                        (input-value ctx :opsv/risk-thresholds))
           pack-hash (opsv/experiment-pack-hash pack)]
       (cond
         (anomaly/anomaly? risk-result) risk-result
         (anomaly/anomaly? pack-hash) pack-hash
         :else (assoc discovery
                      :opsv/risk-result risk-result
                      :opsv/experiment-pack-hash pack-hash))))))

(defn ^{:stratum 1} converge
  [ctx]
  (flow/continue
   (phase-output ctx :opsv/execute)
   (fn [executed]
     (let [config (get-in executed
                          [:opsv/experiment-pack
                           :experiment-pack/convergence])
           initial-state {:steps (:opsv/ramp-steps executed) :history []}
           result (opsv/converge config initial-state
                                 evaluation/convergence-step
                                 evaluation/convergence-evaluation)]
       (if (anomaly/anomaly? result)
         result
         (assoc executed :opsv/convergence-result result))))))

(defn ^{:stratum 1} verify
  [ctx]
  (flow/continue (phase-output ctx :opsv/synthesize)
                 (partial verification/output ctx)))

(defn ^{:stratum 1} synthesize
  [ctx]
  (flow/continue
   (phase-output ctx :opsv/converge)
   (fn [converged]
     (let [proposal (policy/operational-policy
                     ctx (:opsv/convergence-result converged))
           validated (opsv/validate-operational-policy proposal)]
       (if (anomaly/anomaly? validated)
         validated
         (assoc converged
                :opsv/operational-policy validated
                :opsv/policy-hash (content-hash/content-hash validated)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} discover
  [ctx]
  (let [pack (opsv/validate-experiment-pack
              (input-value ctx :opsv/experiment-pack))
        invalid-adapter (adapter/adapter-anomaly
                         (adapter-value ctx))]
    (cond
      (anomaly/anomaly? pack) pack
      invalid-adapter invalid-adapter
      :else
      (let [adapter (adapter-value ctx)
            drivers (port/discover-signals
                     adapter (:experiment-pack/targets pack))]
        (if (anomaly/anomaly? drivers)
          drivers
          {:opsv/experiment-pack pack
           :opsv/candidate-drivers drivers})))))

(defn ^{:stratum 2} execute
  [ctx]
  (flow/continue
   (phase-output ctx :opsv/plan)
   (fn [planned]
     (if-let [invalid (adapter/adapter-anomaly
                       (adapter-value ctx))]
       invalid
       (let [adapter (adapter-value ctx)
             ramp (port/run-guarded-ramp
                   adapter (:opsv/experiment-pack planned))
             invalid-ramp (adapter/ramp-shape-anomaly ramp)]
         (cond
           (anomaly/anomaly? ramp) ramp
           invalid-ramp invalid-ramp
           :else (merge planned
                        {:opsv/environment-fingerprint
                         (:environment-fingerprint ramp)
                         :opsv/ramp-steps (:steps ramp)})))))))
