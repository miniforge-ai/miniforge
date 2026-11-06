;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.experiment-preflight
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-opsv.boundary.experiment-results :as results]
            [ai.miniforge.phase-opsv.boundary.verification-receipt :as receipt]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.experiment-preflight :as preflight]
            [ai.miniforge.phase-opsv.experiment-preflight-schema :as schema]
            [ai.miniforge.phase-opsv.governance-schema :as governance]
            [ai.miniforge.phase-opsv.verification-preparation-schema :as preparation]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-settings? (m/validator schema/Settings))

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(def ^{:stratum 0} ^:private valid-policy? (m/validator governance/RuntimePolicy))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} policy [ctx request]
  (let [value (get-in ctx [:execution/opts :opsv/governance])]
    (cond
      (not (valid-arguments? [ctx request])) (results/invalid :experiment/invalid-preflight)
      (and (some? value) (not (valid-policy? value))) (results/invalid :governance/invalid-policy)
      :else value)))

(defn- ^{:stratum 1} settings [ctx]
  (try+
    (let [defaults (config/load-config-resource "config/phase-opsv/experiment.edn")
          overrides (get-in ctx [:execution/opts :opsv/experiment-execution] {})
          configured (when (map? overrides) (merge defaults overrides))]
      (if (valid-settings? configured) configured (results/invalid :experiment/invalid-preflight)))
    (catch Object _ (results/failed! (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate [ctx request baseline]
  (anomaly/let-ok [configured (settings ctx)
                  _bounded (reporting/admit [configured request baseline])
                  governed (policy ctx request)
                  observed (receipt/admit preparation/Receipt :experiment/invalid-preflight request baseline)]
    (preflight/evaluate ctx configured request observed governed)))

(comment ::runtime-policy-only-no-native-io-or-grant-issuance)
