;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.governed-load-proposal
  "API surface class 1: retained-original load request construction, never execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.kubernetes-load-request :as model]
            [ai.miniforge.phase-opsv.kubernetes-load-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as context]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator [:tuple context/Context schema/Input]))

(def ^{:stratum 0 :private true} valid-original? (m/validator experiment/RetainedBaseline))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :load/invalid-request) {}))

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
      (anomaly/let-ok [baseline (retained/read! ctx (:request input) (:comparison/artifact-ref input))]
        (from-retained ctx input baseline)))))

(comment ::retention-and-input-admission-do-not-authorize-load-or-cleanup)
