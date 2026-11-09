;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-plan
  "API surface class 1: bounded plan construction from uncached acknowledged original evidence."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan :as model]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(def ^{:stratum 0 :private true} valid-original? (m/validator experiment/RetainedBaseline))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :recovery/invalid-plan) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} from-retained [ctx input baseline]
  (if (valid-original? baseline)
    (model/prepare ctx input baseline)
    (anomaly/anomaly :conflict (msg/ts :replicas/unusable-original) {})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [ctx input]
  (anomaly/let-ok [_bounded (reporting/admit input)]
    (if-not (valid-arguments? [ctx input])
      (invalid)
      (let [native (:load input)]
        (anomaly/let-ok [baseline (retained/read! ctx (:request native) (:comparison/artifact-ref native))]
          (from-retained ctx input baseline))))))

(comment ::no-approval-retention-grant-activation-or-provider-call)
