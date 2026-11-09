;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-admission
  (:require [ai.miniforge.phase-opsv.experiment-preflight :as preflight]
            [ai.miniforge.phase-opsv.governance-evaluation :as evaluation]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} gate-ids
  (conj preflight/gate-ids :recovery-plan))

(defn- ^{:stratum 0} target-settings [settings]
  {:experiment/declaration (:recovery/declaration settings)
   :experiment/environment (:recovery/environment settings)})

(defn- ^{:stratum 0} evidence [ctx request baseline]
  {:opsv/experiment-pack-hash (:experiment-pack/hash request)
   :opsv/environment-fingerprint (:environment-fingerprint baseline)
   :opsv/metric-snapshot-artifact-refs (get-in ctx [:execution/input :opsv/metric-snapshot-artifact-refs])})

(defn- ^{:stratum 0} approval-policy [policy settings prepared]
  (when policy
    (update policy :policy/context assoc :opsv/production-allowlist #{}
            :opsv/recovery-admission settings :opsv/recovery-plan prepared)))

(defn- ^{:stratum 0} target-refusal []
  (let [refused (evaluation/refusal :opsv/recovery-target :recovery/target-mismatch)
        guidance {:action :declare-matching-staging-recovery-target
                  :summary (msg/ts :recovery/target-remediation)
                  :details {:required/environment-class :staging :required/target-source :retained-original}}]
    (update-in refused [:errors 0] assoc :reason-code :recovery/target-mismatch :remediation guidance)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} target-refusals [ctx settings request baseline]
  (when-not (preflight/attempt-bound? ctx (target-settings settings) request baseline)
    [(target-refusal)]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate [ctx input prepared baseline policy settings]
  (let [request (get-in input [:load :request])
        observed (evidence ctx request baseline)
        policy (approval-policy policy settings prepared)
        refused (target-refusals ctx settings request baseline)
        evaluated (evaluation/evaluate gate-ids ctx (:experiment-pack request) observed policy refused)]
    (assoc prepared
           :recovery/plan-decision-envelope (:opsv/decision-envelope evaluated)
           :recovery/plan-gate-checks (:opsv/gate-checks evaluated)
           :recovery/plan-gate-results (:opsv/gate-results evaluated)
           :recovery/plan-refusals (vec refused))))

(comment ::independent-plan-admission-is-not-candidate-slo-success-or-an-execution-grant)
