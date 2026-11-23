;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.opsv-constructor-cases
  "Shared canonical payloads for legacy constructors and current publication."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.interface.opsv :as opsv]
            [ai.miniforge.event-stream.opsv-fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} constructors
  [[events/experiment-planned opsv/ExperimentPlanned :opsv.experiment/planned
    {:opsv/experiment-pack-hash "pack"
     :opsv/targets {:services ["catalog"] :environments ["staging"]}
     :opsv/risk-score {:score 0.1 :level :low :factors []}}]
   [events/experiment-started opsv/ExperimentStarted :opsv.experiment/started
    {:opsv/experiment-pack-hash "pack"
     :opsv/environment-fingerprint
     {:cluster "test" :node-pools [] :image-digests {} :config-hash "cfg"}}]
   [events/load-step opsv/LoadStep :opsv/load-step
    {:opsv/step-id "1" :opsv/intended-load {:rps 5} :opsv/observed-load {:rps 5}}]
   [events/guardrail-abort opsv/GuardrailAbort :opsv.guardrail/abort
    {:opsv/trigger :errors :opsv/threshold {:max 1}
     :opsv/observed {:value 2} :opsv/rollback-action :restore}]
   [events/convergence-iteration opsv/ConvergenceIteration :opsv.convergence/iteration
    {:opsv/iteration-id "1" :opsv/params {:replicas 2} :opsv/observed-metrics-summary {:latency 100}}]
   [events/policy-proposed opsv/PolicyProposed :opsv.policy/proposed
    {:opsv/policy-hash "policy" :opsv/diff-artifact-refs [] :opsv/confidence :high}]
   [events/verification-result opsv/VerificationResult :opsv.verification/result
    {:opsv/passed? true :opsv/criteria-evaluation [] :opsv/confidence :high :opsv/caveats []}]
   [events/actuation-emitted opsv/ActuationEmitted :opsv.actuation/emitted
    {:opsv/requested-actuation-mode :recommend-only :opsv/effective-actuation-mode :recommend-only
     :opsv/governed-effects [] :opsv/pr-refs [] :opsv/apply-refs []}]
   [events/actuation-disposition opsv/ActuationDisposition :opsv.actuation/disposition fixtures/disposition-payload]
   [events/drift-detected opsv/DriftDetected :opsv.drift/detected
    {:opsv/signal :latency :opsv/deviation {:ratio 1.1} :opsv/suggested-rerun? true}]])
