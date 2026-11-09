;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.governed-replica-proposal
  "API surface class 1: trusted-process EDN proposal construction without effects or authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.governed-experiment-proposal :as model]
            [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.governed-replica-proposal-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-replica-request :as native]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(def ^{:stratum 0} ^:private valid-original? (m/validator experiment/RetainedBaseline))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :replicas/invalid-proposal) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} from-retained [ctx input baseline]
  (if-not (valid-original? baseline)
    (anomaly/anomaly :conflict (msg/ts :replicas/unusable-original) {})
    (anomaly/let-ok [prepared (native/prepare (:declaration input) (:request input) baseline
                                            (:current input) (:parameters input))]
      (model/proposal ctx input prepared))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [ctx input]
  (anomaly/let-ok [_bounded (reporting/admit input)]
    (if-not (valid-arguments? [ctx input])
      (invalid)
      (anomaly/let-ok [baseline (retained/read! ctx (:request input) (:comparison/artifact-ref input))]
        (from-retained ctx input baseline)))))

(comment ::no-caller-baseline-or-permissive-retention-default)
