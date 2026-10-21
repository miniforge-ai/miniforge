;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-contract-fixtures
  (:require [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} progress [status]
  (case status
    :none {:phase/progress-count 0 :phase/progress-gap-ms 30}
    :observed {:phase/progress-count 1 :phase/last-progress-at (java.util.Date. 20)
               :phase/progress-gap-ms 10}
    :unknown {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} payload [status]
  (merge {:phase/active-since (java.util.Date. 0)
          :heartbeat/activation-id (random-uuid)
          :heartbeat/sequence 1
          :heartbeat/observed-at (java.util.Date. 30)
          :heartbeat/interval-ms 10
          :heartbeat/elapsed-ms 30
          :phase/progress-status status}
         (progress status)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} draft [stream workflow-id status]
  (events/phase-heartbeat stream workflow-id :opsv/verify (payload status)))

(comment (payload :unknown))
