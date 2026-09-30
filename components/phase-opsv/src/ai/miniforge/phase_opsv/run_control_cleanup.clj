;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-cleanup
  "Serialize cleanup attempts and retain monotonic acknowledgments per run."
  (:require [ai.miniforge.phase-opsv.run-control-boundary :as boundary]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} abort! [run]
  (let [state (:cleanup run)]
    (locking state
      (if (:abort-confirmed? @state)
        true
        (let [result (boundary/abort-with-exception-handling run)]
          (when (true? result) (swap! state assoc :abort-confirmed? true))
          result)))))

(defn ^{:stratum 0} revoke! [run id now]
  (let [state (:cleanup run)]
    (locking state
      (if-let [confirmed (get-in @state [:revocations id])]
        confirmed
        (let [result (boundary/revoke-with-exception-handling run id now)]
          (when (or (:revoked? result) (:absent? result)) (swap! state assoc-in [:revocations id] result))
          result)))))

(comment
  (select-keys {} [:abort-confirmed? :revocations]))
