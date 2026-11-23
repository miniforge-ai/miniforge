;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial
  "API surface class 1: read-only fresh original-bound replica intent, not trial authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.governed-replica-proposal :as proposal]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-observation :as observation]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as original]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as recovery]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-replica-proposal :as model]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} refused [category]
  (anomaly/anomaly category (msg/ts :replicas/native-trial-refused) {}))

(defn- ^{:stratum 0} parameters [input]
  {:opsv/operation :trial-apply
   :replicas/count (:replicas/count input)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} capture-proposal! [ctx input admitted retained]
  (anomaly/let-ok [observed (observation/observe! ctx input admitted retained)]
    (proposal/prepare! ctx (model/proposal-input input
                            (get-in observed [:environment/raw-observations :kubernetes/verification :kubernetes/source])
                            (parameters input)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [ctx input]
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
  (anomaly/let-ok [_bounded (reporting/admit input)]
    (if-not (valid-arguments? [ctx input])
      (refused :invalid-input)
      (anomaly/let-ok [admitted (recovery/read! ctx (:recovery/input input) (:recovery/artifact-ref input))
                      retained (original/read! ctx (get-in input [:recovery/input :load :request])
                                               (get-in admitted [:recovery/plan :comparison/artifact-ref]))]
        (capture-proposal! ctx input admitted retained)))))

(comment
  (prepare! {} {}))
