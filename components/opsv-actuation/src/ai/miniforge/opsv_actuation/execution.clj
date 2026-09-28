;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution
  "Compose durable proposals and fresh grant checks without issuing authority."
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.grant-boundary :as grant-boundary]
            [ai.miniforge.opsv-actuation.execution-schema :as schema]
            [ai.miniforge.opsv-actuation.governance :as governance]
            [ai.miniforge.opsv-actuation.messages :as msg]
            [ai.miniforge.opsv-actuation.proposal :as proposal]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute-claimed!
  "The durable record is an input boundary: reject altered payloads before I/O."
  [provider record]
  (if (m/validate schema/ClaimedPr record)
    (provider record (proposal/provider-content (:effect/proposal record)))
    {:effect/outcome :failed
     :effect/failure (msg/ts :execution/invalid-record)}))

(defn ^{:stratum 0} propose!
  [dir candidate grant-id decision now]
  (let [prepared (governance/prepare candidate decision)
        options {:effect-id (:effect/id candidate)
                 :effect-class :effect/pr-create
                 :grant-id grant-id
                 :envelope-id (:envelope/id decision)
                 :proposal prepared}]
    (effect/propose! dir options now)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} commit!
  [effect-dir grant-dir id issued-grant-id clock provider]
  (effect/commit-current! effect-dir id (partial grant-boundary/current grant-dir issued-grant-id)
                          clock (partial execute-claimed! provider)))

(comment
  (proposal/provider-content {}))
