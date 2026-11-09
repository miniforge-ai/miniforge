;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-baseline-restoration
  "API surface class 1: native read-only restoration construction, not authority or verified recovery."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.governed-replica-proposal :as proposal]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration :as model]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} capture-proposal! [ctx input plan]
  (if-not (model/configured-for-plan? input plan)
    (anomaly/anomaly :conflict (msg/ts :recovery/restoration-request-refused) {})
    (let [provider (dissoc (:restoration/provider input) :kubernetes/context)
          declaration (dissoc (get-in input [:recovery/input :load :declaration]) :workflow/id)]
      (anomaly/let-ok [captured (kubernetes/observe-verification! provider declaration)]
        (proposal/prepare! ctx (model/proposal-input input plan captured))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare! [ctx input]
  (anomaly/let-ok [_bounded (reporting/admit input)]
    (if-not (valid-arguments? [ctx input])
      (anomaly/anomaly :invalid-input (msg/ts :recovery/invalid-restoration-request) {})
      (anomaly/let-ok [admitted (retained/read! ctx (:recovery/input input) (:recovery/artifact-ref input))]
        (capture-proposal! ctx input (:recovery/plan admitted))))))

(comment
  (prepare! {} {}))
