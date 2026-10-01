;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.control-fixtures
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} timestamp #inst "2026-09-30T12:00:00Z")

(def ^{:stratum 0} principal "control-test")

(def ^{:stratum 0} justification "Operator requested pause")

(defn ^{:stratum 0} valid-action? [record]
  (:valid? (validation/validate-schema domain/control-action-evidence-schema record)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} requester []
  {:principal principal :listener-id (random-uuid) :capability :control :role :admin})

(defn ^{:stratum 1} approval []
  {:status :approved
   :approvers [{:principal principal :timestamp timestamp :decision :approved}]})

(defn ^{:stratum 1} policy-check []
  {:policy-check/pack-id "opsv"
   :policy-check/pack-version "1.0.0"
   :policy-check/phase :verify
   :policy-check/checked-at timestamp
   :policy-check/violations []
   :policy-check/passed? true
   :policy-check/duration-ms 0})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} action []
  {:action/id (random-uuid)
   :action/type :pause
   :action/timestamp timestamp
   :action/requester (requester)
   :action/justification justification
   :action/approval (approval)
   :action/result {:status :success}
   :action/pre-state {:paused false}
   :action/post-state {:paused true}})

(defn ^{:stratum 2} requested [stream workflow-id id]
  (events/control-action-requested stream workflow-id id :pause (requester)))

(comment
  (action))
