;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.runtime-boundary
  "Validate values from trusted runtime ports before they enter the coordinator."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.call :as call]
            [ai.miniforge.effect-transaction.current-schema :as schema]
            [ai.miniforge.effect-transaction.messages :as msg]
            [ai.miniforge.execution-grant.interface :as grant]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid-port-result
  [port]
  (anomaly/anomaly :unavailable (msg/t :commit/runtime-unavailable) {:port port}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-port
  [port result-schema thunk]
  (let [called (call/result thunk)
        value (:call/report called)]
    (cond
      (contains? called :call/error) (invalid-port-result port)
      (anomaly/anomaly? value) value
      (m/validate result-schema value) value
      :else (invalid-port-result port))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} current-grant
  [lookup-grant id]
  (read-port :grant-lookup grant/ExecutionGrant (partial lookup-grant id)))

(defn ^{:stratum 2} current-time
  [clock]
  (read-port :clock schema/ClockReading clock))

(comment
  (current-time (constantly nil)))
