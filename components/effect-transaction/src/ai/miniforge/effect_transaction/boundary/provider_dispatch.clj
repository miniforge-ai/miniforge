;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.boundary.provider-dispatch
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.messages :as msg]
            [ai.miniforge.effect-transaction.provider-dispatch :as dispatch]
            [ai.miniforge.effect-transaction.provider-dispatch-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(def ^{:stratum 0} ^:private valid-diagnostics? (m/validator schema/DiagnosticArguments))

(defn- ^{:stratum 0} invalid [] (anomaly/anomaly :invalid-input (msg/t :dispatch/invalid-input) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} diagnostics [unconfirmed invalid-message]
  (if (valid-diagnostics? [unconfirmed invalid-message])
    (dispatch/diagnostics unconfirmed invalid-message)
    (invalid)))

(defn ^{:stratum 1} call
  ([dispatch-fn operation]
   (let [messages (dispatch/diagnostics (msg/t :dispatch/unconfirmed) (msg/t :dispatch/invalid-input))]
     (call dispatch-fn operation messages)))
  ([dispatch-fn operation messages]
   (if (valid-arguments? [dispatch-fn operation messages])
     (dispatch/call-with-exception-handling dispatch-fn operation messages)
     (invalid))))

(comment ::validated-public-dispatch-boundary)
