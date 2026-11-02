;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-signal-model
  "Bind acquired metric labels to the explicitly selected service target."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} expected-labels [configuration]
  (let [labels (:discovery/metric-labels configuration)
        target (:discovery/kubernetes configuration)]
    {(:cluster labels) (:kubernetes/cluster-id target)
     (:namespace labels) (:kubernetes/namespace target)
     (:service labels) (:discovery/service configuration)}))

(defn ^{:stratum 0} driver [kind expected observation]
  (let [observed (select-keys (:measurement/labels observation) (keys expected))]
    (if (= expected observed)
      {:driver kind
       :measurement observation}
      (anomaly/anomaly :conflict (msg/ts :discovery/unbound-signal) {}))))

(comment ::correlated-observations-not-verification)
