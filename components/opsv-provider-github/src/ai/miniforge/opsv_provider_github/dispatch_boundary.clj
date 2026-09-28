;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.dispatch-boundary
  "Bound a trusted callback to one synchronous provider attempt and retain its result."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-provider-github.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fallback [state]
  (or (:result @state)
      {:effect/outcome (if (:invoked? @state) :unknown-outcome :failed)
       :effect/failure (msg/t :create/dispatch-unconfirmed)}))

(defn- ^{:stratum 0} invoke-once! [state operation]
  (let [[before _] (swap-vals! state #(if (and (:open? %) (not (:invoked? %)))
                                       (assoc % :invoked? true) %))]
    (when (and (:open? before) (not (:invoked? before)))
      (let [result (operation)] (swap! state assoc :result result) result))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-exception-handling [dispatch operation]
  (let [state (atom {:open? true :invoked? false})]
    (try
      (let [result (dispatch #(invoke-once! state operation))]
        (if (:invoked? @state) (fallback state) result))
      (catch clojure.lang.ArityException _
        (if (:invoked? @state) (fallback state)
            (anomaly/anomaly :invalid-input (msg/t :input/invalid) {})))
      (catch InterruptedException _
        (let [result (fallback state)] (.interrupt (Thread/currentThread)) result))
      (catch Throwable _ (fallback state))
      (finally (swap! state assoc :open? false)))))
