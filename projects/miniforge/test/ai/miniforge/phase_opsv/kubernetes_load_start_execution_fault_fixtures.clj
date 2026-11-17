;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-execution-fault-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} refusal [] (anomaly/anomaly :unavailable "injected evidence failure" {}))

(defn ^{:stratum 0} interrupt-with-unavailable-clock! [fixture failure & _]
  (reset! (:host/clock fixture) nil)
  (throw failure))

(def ^{:stratum 0} policy-deny! forward/deny-policy!)

(defn ^{:stratum 0} check-fenced [fixture result]
  (is (anomaly/anomaly? result))
  (is (true? (:stopped? (actuation/mutation-status (get-in fixture [:host/runtime :fence]))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unavailable-context! []
  (throw (ex-info "injected context failure" (refusal))))

(defn ^{:stratum 1} fail-outcome! [publish directory value]
  (if (= :succeeded (get-in value [:artifact/content :effect/transaction :effect/state]))
    (refusal)
    (publish directory value)))

(defn ^{:stratum 1} change-after-readiness! [fixture publish directory value]
  (let [result (publish directory value)]
    (when (= :native-load-start-readiness (get-in value [:artifact/metadata :opsv/material-kind]))
      (policy-deny! fixture))
    result))

(comment
  (refusal))
