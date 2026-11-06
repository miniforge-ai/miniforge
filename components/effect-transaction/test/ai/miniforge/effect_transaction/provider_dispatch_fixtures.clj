;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.provider-dispatch-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} receipt-id "A concrete provider receipt, distinct from a callback's fabricated report." ::receipt)

(defn ^{:stratum 0} messages []
  (effect/provider-dispatch-diagnostics (msg/t :dispatch/unconfirmed) (msg/t :dispatch/invalid-input)))

(defn ^{:stratum 0} refusal []
  (anomaly/anomaly :unauthorized (msg/t :grant/effect-class-mismatch) {}))

(defn ^{:stratum 0} report-port! [calls report]
  (swap! calls inc)
  report)

(defn ^{:stratum 0} throwing-port! [calls _report]
  (swap! calls inc)
  (throw (IllegalStateException. (msg/t :dispatch/unconfirmed))))

(defn ^{:stratum 0} twice! [operation] (operation) (operation))

(defn ^{:stratum 0} dispatch! [operation] (operation))

(defn ^{:stratum 0} after-result! [operation]
  (operation)
  (throw (AssertionError. (msg/t :dispatch/unconfirmed))))

(defn ^{:stratum 0} throwing-dispatch! [_operation]
  (throw (IllegalStateException. (msg/t :dispatch/unconfirmed))))

(defn ^{:stratum 0} interrupting-dispatch! [_operation]
  (throw (InterruptedException. (msg/t :dispatch/unconfirmed))))

(defn ^{:stratum 0} retain! [retained refusal operation]
  (reset! retained operation)
  refusal)

(defn ^{:stratum 0} threaded! [operation] @(future (operation)))

(defn ^{:stratum 0} zero-arguments [] nil)

(defn ^{:stratum 0} two-arguments [_first _second] nil)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} report [outcome]
  {:effect/outcome outcome
   :effect/observed {:provider/receipt receipt-id}})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} probe
  ([outcome] (probe outcome report-port!))
  ([outcome port]
   (let [calls (atom 0)
         reported (report outcome)
         operation (partial port calls reported)]
     {:calls calls
      :report reported
      :operation operation})))

(comment ::explicit-runtime-ports-no-network-or-filesystem)
