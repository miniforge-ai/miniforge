;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.environment-comparison-bindings :as bindings]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.phase-opsv.verification-environment :as environment]
            [ai.miniforge.phase-opsv.verification-schema :as verification]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} acknowledged? [ctx assembly id]
  (and (= (:execution/id ctx) (:evidence-bundle/workflow-id assembly))
       (= (get-in ctx [:execution/input :opsv/evidence-bundle-id]) (:evidence-bundle/id assembly))
       (contains? (:opsv/artifact-refs assembly) id)))

(defn- ^{:stratum 0} correlated? [ctx request content]
  (let [pin (:comparison/pin content)
        prepared (:comparison/preparation content)]
    (and (= pin (:comparison/pin request))
         (= (select-keys request verification/correlation-keys)
            (select-keys prepared verification/correlation-keys))
         (= (bindings/comparison-binding (:execution/id ctx) (:runtime/target request) request)
            (get-in pin [:comparison/contract :comparison/binding])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid? [ctx request id value]
  (let [content (:artifact/content value)
        pin (:comparison/pin content)]
    (and (= id (:artifact/id value))
         (= :verification-environment (get-in value [:artifact/metadata :opsv/material-kind]))
         (= :opsv-verification-report (:artifact/type value))
         (protected/valid? ctx value)
         (correlated? ctx request content)
         (not (anomaly/anomaly?
                (environment/compare-observation pin (:comparison/hash pin) :rollback
                                                 (:comparison/preparation content)))))))

(comment ::original-retained-baseline-never-reconstructed-from-candidate-observations)
