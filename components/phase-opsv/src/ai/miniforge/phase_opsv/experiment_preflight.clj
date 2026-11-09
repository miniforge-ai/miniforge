;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.experiment-preflight
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.governance-evaluation :as evaluation]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gate-ids
  "Trials retain safety/evidence checks, not permission for final policy actuation."
  (filterv #(not= :actuation %) opsv/opsv-gate-ids))

(defn- ^{:stratum 0} evidence [ctx request baseline]
  (let [pack-hash (:experiment-pack/hash request)
        fingerprint (:environment-fingerprint baseline)
        refs (get-in ctx [:execution/input :opsv/metric-snapshot-artifact-refs])]
    {:opsv/experiment-pack-hash pack-hash
     :opsv/environment-fingerprint fingerprint
     :opsv/metric-snapshot-artifact-refs refs}))

(defn- ^{:stratum 0} staging-policy [policy]
  ;; A production allowlist for final actuation cannot enable temporary trials.
  (when policy (assoc-in policy [:policy/context :opsv/production-allowlist] #{})))

(defn ^{:stratum 0} attempt-bound?
  "Share exact runtime declaration, staging membership and retained-original correlation checks."
  [ctx settings request baseline]
  (let [declaration (:experiment/declaration settings)
        pack (:experiment-pack request)
        target (:runtime/target request)
        environments (get-in pack [:experiment-pack/targets :environments])
        original (get-in request [:comparison/pin :comparison/contract :comparison/baseline])]
    (and (= (:execution/id ctx) (:workflow/id declaration))
         (preparation/correlated? declaration request)
         (= pack (get-in ctx [:execution/input :opsv/experiment-pack]))
         (= #{(:experiment/environment settings)} (set environments))
         (contains? (set (get-in pack [:experiment-pack/guardrails :blast-radius :namespaces]))
                    (:namespace target))
         (contains? (set (get-in pack [:experiment-pack/targets :services]))
                    (:kubernetes/deployment-name declaration))
         (= (:runtime/target request) (:runtime/target baseline))
         (= original (:environment-fingerprint baseline)))))

(defn- ^{:stratum 0} result [request evaluated]
  (let [decision (:opsv/decision-envelope evaluated)
        checks (:opsv/gate-checks evaluated)
        results (:opsv/gate-results evaluated)
        binding (get-in request [:comparison/pin :comparison/contract :comparison/binding])
        digest (get-in request [:comparison/pin :comparison/hash])]
    {:opsv/experiment-decision-envelope decision
     :opsv/experiment-gate-checks checks
     :opsv/experiment-gate-results results
     :comparison/binding binding
     :comparison/hash digest}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} refusals [ctx settings request baseline]
  (cond
    (not (:experiment/enabled? settings))
    [(evaluation/refusal :opsv/experiment-enabled :experiment/disabled)]
    (not (attempt-bound? ctx settings request baseline))
    [(evaluation/refusal :opsv/experiment-binding :experiment/target-mismatch)]
    :else []))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate [ctx settings request baseline policy]
  (let [pack (:experiment-pack request)
        observed (evidence ctx request baseline)
        refused (refusals ctx settings request baseline)
        policy (staging-policy policy)
        evaluated (evaluation/evaluate gate-ids ctx pack observed policy refused)]
    (result request evaluated)))

(comment ::gate-decision-not-mutation-retention-or-recovery-authority)
