;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.issued-commit-fixtures
  (:require [ai.miniforge.effect-transaction.fixtures :as fixtures]
            [ai.miniforge.effect-transaction.interface :as effect]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} lookup! [events current id]
  (swap! events conj [:lookup id])
  current)

(defn- ^{:stratum 0} clock! [events]
  (swap! events conj :clock)
  fixtures/now)

(defn- ^{:stratum 0} operate! [events record]
  (swap! events conj [:effect (:effect/id record)])
  (fixtures/succeed!))

(defn ^{:stratum 0} competing! [dir record grant _id]
  (effect/commit! dir record grant {} fixtures/now fixtures/succeed!)
  grant)

(defn ^{:stratum 0} commit! [{:keys [dir issued record lookup clock operation]}]
  (effect/commit-current! dir (:effect/id record) (:grant/id issued) lookup clock operation))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} setup
  ([] (setup false))
  ([substitute?]
   (let [dir (fixtures/tmp-dir)
         issued (fixtures/merge-grant)
         duplicate (fixtures/merge-grant {:effect-id (get-in issued [:grant/scope :effect/id])})
         actual (if substitute? duplicate issued)
         record (fixtures/propose-merge! dir actual)
         events (atom [])]
     {:dir dir :issued issued :current actual :record record :events events
      :lookup (partial lookup! events actual) :clock (partial clock! events)
      :operation (partial operate! events)})))

(comment ::independently-issued-identical-scopes-and-real-durable-records)
