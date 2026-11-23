;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.governed-experiment-proposal :as proposal]
            [ai.miniforge.phase-opsv.kubernetes-load-pod :as pod]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} target-bound? [ctx input baseline]
  (let [declaration (:declaration input)
        original (get-in baseline [:environment/raw-observations :kubernetes/verification :kubernetes/source])]
    (and (= (:execution/id ctx) (:workflow/id declaration))
         (preparation/correlated? declaration (:request input))
         (preparation/target-bound? declaration original))))

(defn- ^{:stratum 0} prepared [input]
  (let [payload (pod/request input)
        digest (hash/content-hash payload)
        resources [(pod/resource-reference input)]
        target (get-in input [:declaration :runtime/target])]
    {:effect/class :effect/load-test :opsv/operation :start-load
     :runtime/target target :opsv/resources resources
     :opsv/payload-hash digest :kubernetes/request payload}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [ctx input baseline]
  (if (target-bound? ctx input baseline)
    (proposal/proposal ctx input (prepared input))
    (anomaly/anomaly :conflict (msg/ts :load/request-mismatch) {})))

(comment ::no-caller-selected-command-pod-name-uid-or-authority)
