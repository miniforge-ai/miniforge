;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-binding)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} reference [record]
  (first (get-in record [:effect/proposal :opsv/experiment :opsv/resources])))

(defn ^{:stratum 0} request [record] (get-in record [:effect/proposal :kubernetes/request]))

(comment ::shared-native-transaction-access-not-admission)
