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

(defn- ^{:stratum 0} invoke-once! [owner state operation]
  (let [same-thread? (identical? owner (Thread/currentThread))
        [before _] (swap-vals! state #(cond
                                      (not same-thread?) (assoc % :rejected? true)
                                      (and (:open? %) (not (:invoked? %))) (assoc % :invoked? true)
                                      :else %))]
    (when (and same-thread? (:open? before) (not (:invoked? before)))
      (let [result (operation)] (swap! state assoc :result result) result))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-exception-handling [dispatch operation]
  (let [state (atom {:open? true :invoked? false})
        owner (Thread/currentThread)]
    (try
      (let [result (dispatch #(invoke-once! owner state operation))]
        (if (or (:invoked? @state) (:rejected? @state)) (fallback state) result))
      (catch clojure.lang.ArityException _
        (if (:invoked? @state) (fallback state)
            (anomaly/anomaly :invalid-input (msg/t :input/invalid) {})))
      (catch InterruptedException _
        (let [result (fallback state)] (.interrupt (Thread/currentThread)) result))
      (catch Throwable _ (fallback state))
      (finally (swap! state assoc :open? false)))))
