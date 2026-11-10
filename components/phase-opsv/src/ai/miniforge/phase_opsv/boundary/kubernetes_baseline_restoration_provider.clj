;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-baseline-restoration-provider
  "API surface class 1: fresh original-baseline replica restoration; acknowledgement is not recovery verification."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-baseline-restoration :as restoration]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-provider :as replica]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-replica-proposal :as proposal]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :recovery/invalid-restoration-provider) {}))

(defn- ^{:stratum 0} submit! [runtime record dispatch]
  (let [input (:input runtime)]
    (anomaly/let-ok [current (restoration/prepare! (:context runtime) input)]
      (if (proposal/same-proposal? current record)
        (anomaly/let-ok [configured (settings/load! (:restoration/provider input))]
          (replica/patch! configured record dispatch))
        (anomaly/anomaly :conflict (msg/ts :recovery/restoration-request-refused) {})))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restore! [runtime record dispatch]
  (anomaly/let-ok [_bounded (reporting/admit [(:input runtime) record])]
    (if (valid-arguments? [runtime record dispatch])
      (submit! runtime record dispatch)
      (invalid))))

(comment
  (restore! {} {} identity))
