;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-settings-spec
  "Contracts for capsule resource input and Kubernetes executor timing settings.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} CapsuleBudget [:map [:resources/timeout-seconds pos-int?]])

(def ^{:stratum 0} ^:private Millis [:int {:min 1
                           :max Integer/MAX_VALUE}])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ExecutorSettings
  (conj CapsuleBudget
        [:kubernetes/creation-request-timeout-ms Millis]
        [:kubernetes/pod-ready-timeout-ms Millis]
        [:kubernetes/pod-ready-poll-interval-ms Millis]))

(comment CapsuleBudget ExecutorSettings)
