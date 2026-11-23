;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-signal-model
  "Bind acquired metric labels to the explicitly selected service target."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} expected-labels
  ([configuration]
   (let [labels (:discovery/metric-labels configuration)
         target (:discovery/kubernetes configuration)]
     (expected-labels (:kubernetes/cluster-id target) (:kubernetes/namespace target) (:discovery/service configuration) labels)))
  ([cluster namespace service labels]
   {(:cluster labels) cluster
    (:namespace labels) namespace
    (:service labels) service}))

(defn ^{:stratum 0} matches? [expected observation]
  (= expected (select-keys (:measurement/labels observation) (keys expected))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} driver [kind expected observation]
  (if (matches? expected observation)
    {:driver kind
     :measurement observation}
    (anomaly/anomaly :conflict (msg/ts :discovery/unbound-signal) {})))

(comment ::correlated-observations-not-verification)
