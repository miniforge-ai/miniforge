;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.grant-boundary
  "Require all PR issuance bindings before the shared commit-time recheck."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} required-bindings
  (get-in grant/issuance-policies [:effect/pr-create :policy/scope-keys]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} bound-authority [dir id]
  (let [authority (grant/current dir id)
        scope (:grant/scope authority)]
    (cond
      (or (nil? authority) (anomaly/anomaly? authority)) authority
      (every? #(contains? scope %) required-bindings) authority
      :else (anomaly/anomaly :unauthorized (msg/ts :execution/unbound-grant) {}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} current
  "Compare with runtime-retained issuance identity before loading authority."
  [dir issued-id requested-id]
  (if (= issued-id requested-id)
    (bound-authority dir issued-id)
    (anomaly/anomaly :unauthorized (msg/ts :execution/substituted-grant) {})))

(comment
  required-bindings)
