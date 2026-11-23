;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-admission-fixtures
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-plan :as plan]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.experiment-preflight-fixtures :as preflight]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as load]
            [ai.miniforge.phase-opsv.governed-replica-proposal-fixtures :as retained]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replica]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} admit [{:keys [ctx input]}]
  (phase/admit-kubernetes-recovery-plan! ctx input))

(defn ^{:stratum 0} settings [input digest]
  {:environment/class :staging :recovery/environment "staging"
   :recovery/declaration (get-in input [:load :declaration])
   :recovery/approved-plan-hashes #{digest}})

(defn- ^{:stratum 0} native-source [transform] (replica/input (preflight/input transform)))

(defn- ^{:stratum 0} with-metric-snapshot [ctx baseline]
  (let [[_discovery plan-step execute-step] support/handlers
        adapter (support/test-adapter support/ramp-steps (:environment-fingerprint baseline))
        configured (assoc-in ctx [:execution/opts :opsv/adapter] adapter)
        planned (storage/step configured plan-step)
        completed (storage/step planned execute-step)
        refs (get-in completed [:phase :result :output :opsv/metric-snapshot-artifact-refs])
        bundle (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store completed) bundle)
        directory (get-in completed [:execution/opts :opsv/artifact-directory])]
    (is (seq refs))
    (doseq [id refs]
      (is (= :telemetry (:artifact/type (artifact/read-published directory id))))
      (is (contains? (:opsv/artifact-refs assembly) id)))
    (assoc-in completed [:execution/input :opsv/metric-snapshot-artifact-refs] refs)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} configured [{:keys [ctx input] :as fixture}]
  (let [input {:load (load/input input) :recovery/duration-ms (get-in input [:limits :opsv/duration-ms])}
        prepared (plan/prepare! ctx input)
        namespace (get-in input [:load :declaration :runtime/target :namespace])
        policy (-> governance/policy
                   (assoc-in [:policy/context :opsv/blast-radius-limits :allowed-namespaces] #{namespace})
                   (assoc-in [:policy/context :opsv/apply-enabled?] false))
        ctx (-> ctx
                (assoc-in [:execution/input :opsv/experiment-pack] (get-in input [:load :request :experiment-pack]))
                (assoc-in [:execution/opts :opsv/governance] policy)
                (assoc-in [:execution/opts :opsv/recovery-plan-admission] (settings input (:recovery/hash prepared)))
                (with-metric-snapshot (get-in fixture [:native :baseline])))]
    (assoc fixture :ctx ctx :input input :prepared prepared)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-input
  ([f] (with-input identity f))
  ([transform f] (retained/with-input #(native-source transform) #(f (configured %)))))

(comment ::real-producer-and-acknowledgement-runtime-approval-is-separate-from-forward-opt-in)
