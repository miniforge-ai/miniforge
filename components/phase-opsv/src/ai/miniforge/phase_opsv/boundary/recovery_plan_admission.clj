;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.recovery-plan-admission
  "API surface class 1: fresh retained inputs and independent runtime-owned N4 admission."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-plan :as plan]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.governance-schema :as governance]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.recovery-plan-admission :as admission]
            [ai.miniforge.phase-opsv.recovery-plan-admission-schema :as schema]
            [ai.miniforge.phase-opsv.recovery-plan-gate]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-policy? (m/validator [:maybe governance/RuntimePolicy]))

(def ^{:stratum 0 :private true} valid-settings? (m/validator [:maybe schema/Settings]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} configuration [ctx]
  (let [policy (get-in ctx [:execution/opts :opsv/governance])
        settings (get-in ctx [:execution/opts :opsv/recovery-plan-admission])]
    (if (and (valid-policy? policy) (valid-settings? settings))
      {:policy policy :settings settings}
      (anomaly/anomaly :invalid-input (msg/ts :recovery/invalid-admission) {}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} admit! [ctx input]
  (anomaly/let-ok [configured (configuration ctx)
                  _bounded (reporting/admit [(:settings configured) input])
                  prepared (plan/prepare! ctx input)
                  baseline (retained/read! ctx (get-in input [:load :request])
                                          (get-in input [:load :comparison/artifact-ref]))]
    (admission/evaluate ctx input prepared baseline (:policy configured) (:settings configured))))

(comment ::no-caller-decision-no-execution-opt-in-no-publication-or-provider-call)
