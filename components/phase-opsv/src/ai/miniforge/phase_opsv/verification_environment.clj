;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-environment
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.environment-comparison :as comparison]
            [ai.miniforge.phase-opsv.environment-comparison-failure :as failure]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [ai.miniforge.phase-opsv.verification-environment-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} plan [workflow-id baseline declaration request]
  (cond-> {:comparison/binding (assoc (select-keys request
                                                  [:verification/id :candidate/hash :experiment-pack/hash])
                                      :workflow/id workflow-id
                                      :runtime/target (:runtime/target declaration))
           :comparison/baseline baseline
           :comparison/changes (:comparison/changes declaration)}
    (contains? declaration :comparison/replica-contract)
    (assoc :comparison/replica-contract (:comparison/replica-contract declaration))))

(defn- ^{:stratum 0} invalid-declaration []
  (anomaly/anomaly :invalid-input (msg/ts :verification/invalid-environment-contract) {}))

(defn- ^{:stratum 0} hashes [value]
  (select-keys value [:candidate/hash :experiment-pack/hash]))

(defn ^{:stratum 0} compare-observation [pin expected-hash stage observed]
  (let [binding (get-in pin [:comparison/contract :comparison/binding])
        target (:runtime/target binding)]
    (if (not= target (:runtime/target observed))
      (failure/mismatch :target-mismatch target (:runtime/target observed))
      (comparison/compare-observation
        (cond-> {:comparison/pin pin
                 :comparison/expected-hash expected-hash
                 :comparison/binding binding
                 :comparison/stage stage
                 :comparison/observed (:environment-fingerprint observed)}
          (contains? observed :comparison/replica-observations)
          (assoc :comparison/replica-observations (:comparison/replica-observations observed)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pin [ctx baseline request]
  (let [declaration (get-in ctx [:execution/opts :opsv/verification-environment])]
    (cond
      (not (m/validate schema/Declaration declaration)) (invalid-declaration)
      (not= (hashes request) (hashes declaration))
      (failure/mismatch :declaration-mismatch (hashes declaration) (hashes request))
      :else (comparison/pin (plan (context/workflow-id ctx) baseline declaration request)))))

(comment ::host-pins-before-acquisition-and-keeps-the-independent-digest)
