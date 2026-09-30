;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.adapter-factory
  "Construct callback-backed adapters without hiding behavior in configuration."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.protocol :as port]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} missing-verification [_request]
  (anomaly/anomaly :unavailable (msg/ts :verification/missing) {}))

(defn- ^{:stratum 0} callback? [value]
  ;; Invokable collections are data, not executable adapter callbacks.
  (or (fn? value) (and (var? value) (bound? value) (fn? @value))))

(defn- ^{:stratum 0} callback-adapter [discover-fn ramp-fn verify-fn]
  (reify port/OPSVAdapter
    (discover-signals [_ targets] (discover-fn targets))
    (run-guarded-ramp [_ pack] (ramp-fn pack))
    port/VerificationAdapter
    (run-verification [_ request] (verify-fn request))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create
  ([discover-fn ramp-fn] (create discover-fn ramp-fn missing-verification))
  ([discover-fn ramp-fn verify-fn]
   (if (every? callback? [discover-fn ramp-fn verify-fn])
     (callback-adapter discover-fn ramp-fn verify-fn)
     (anomaly/anomaly :invalid-input (msg/ts :adapter/invalid-callbacks) {}))))

(comment
  (create identity identity identity))
