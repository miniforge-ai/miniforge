;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-run
  "Bind fresh candidate measurements to one verification invocation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.protocol :as port]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} correlation-keys
  [:verification/id :candidate/hash :experiment-pack/hash])

(def ^{:stratum 0} Receipt
  [:map {:closed true}
   [:verification/id :uuid]
   [:candidate/hash :string]
   [:experiment-pack/hash :string]
   [:environment-fingerprint [:map-of :keyword :any]]
   [:observations [:map-of :string :any]]
   [:confidence [:and number? [:fn #(<= 0 % 1)]]]
   [:metric-snapshot-artifact-refs [:vector :uuid]]])

(defn- ^{:stratum 0} request-values [policy pack]
  (let [policy-hash (hash/content-hash policy)
        pack-hash (hash/content-hash pack)]
    {:verification/id (random-uuid)
     :candidate/hash policy-hash
     :experiment-pack/hash pack-hash
     :candidate/policy policy
     :experiment-pack pack}))

(defn- ^{:stratum 0} invoke-with-exception-handling [adapter request]
  ;; This component has no slingshot dependency; this is its adapter boundary.
  (try
    (port/run-verification adapter request)
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _
      (anomaly/anomaly :unavailable (msg/ts :verification/failed) {}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request [synthesized]
  (let [policy (opsv/validate-operational-policy (:opsv/operational-policy synthesized))
        pack (opsv/validate-experiment-pack (:opsv/experiment-pack synthesized))
        fingerprint (:opsv/environment-fingerprint synthesized)]
    (cond
      (anomaly/anomaly? policy) policy
      (anomaly/anomaly? pack) pack
      (not (and (map? fingerprint) (seq fingerprint)))
      (anomaly/anomaly :invalid-input (msg/ts :adapter/missing-fingerprint) {})
      :else (request-values policy pack))))

(defn- ^{:stratum 1} correlated-measurements
  [request measurements]
  (when (and (map? request) (map? measurements))
    (merge (select-keys request correlation-keys) measurements)))

(defn- ^{:stratum 1} validate-receipt [request fingerprint result]
  (cond
    (anomaly/anomaly? result) result
    (not (m/validate Receipt result))
    (anomaly/anomaly :invalid-input (msg/ts :verification/invalid) {})
    (not= (select-keys request correlation-keys) (select-keys result correlation-keys))
    (anomaly/anomaly :conflict (msg/ts :verification/mismatched) {})
    (not (and (seq fingerprint) (= fingerprint (:environment-fingerprint result))))
    (anomaly/anomaly :conflict (msg/ts :verification/environment-mismatch) {})
    :else result))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} receipt
  "Correlate measurements after an adapter executes this request; no authority."
  [request measurements]
  (let [result (correlated-measurements request measurements)]
    (validate-receipt request (:environment-fingerprint result) result)))

(defn ^{:stratum 2} execute [ctx synthesized]
  (let [adapter (get-in ctx [:execution/opts :opsv/adapter])
        request (request synthesized)]
    (cond
      (anomaly/anomaly? request) request
      (satisfies? port/VerificationAdapter adapter)
      (validate-receipt request (:opsv/environment-fingerprint synthesized)
                        (invoke-with-exception-handling adapter request))
      :else (anomaly/anomaly :unavailable (msg/ts :verification/missing) {}))))

(comment
  (m/validate Receipt {}))
