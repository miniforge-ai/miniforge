;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-progress-port-test
  (:require [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.workflow.checkpoint-store-records :as checkpoints]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-heartbeats :as heartbeats]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} handle [id]
  {:heartbeat/current? true :heartbeat/fixture-id id})

(defn- ^{:stratum 0} context [] {:execution/id (random-uuid)})

(defn- ^{:stratum 0} interceptor [] {:config {:phase :plan}})

(defn- ^{:stratum 0} observe! [calls activation source position]
  (swap! calls conj [activation source position])
  nil)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} late-reports-retain-the-original-activation
  (let [first-handle (handle :first)
        second-handle (handle :second)
        owned (atom {:plan first-handle})
        calls (atom [])]
    (with-redefs [stream/observe-heartbeat-progress! (partial observe! calls)]
      (let [first-context (heartbeats/phase-context owned (context) :plan)]
        (swap! owned assoc :plan second-handle)
        (let [second-context (heartbeats/phase-context owned first-context :plan)]
          ((:execution/report-progress! first-context) :samples 1)
          ((:execution/report-progress! second-context) :samples 1)
          (is (= [[first-handle :samples 1] [second-handle :samples 1]] @calls)))))))

(deftest ^{:stratum 1} absent-or-legacy-handles-remove-stale-ports
  (let [stale (assoc (context) :execution/report-progress! identity)]
    (doseq [owned [{} {:plan :legacy-handle}]]
      (is (not (contains? (heartbeats/phase-context (atom owned) stale :plan)
                         :execution/report-progress!))))))

(deftest ^{:stratum 1} callback-context-captures-owned-handle-without-changing-observer-result
  (let [owned (handle :owned)
        wrapped (callbacks/wrap-phase-callbacks nil {:on-phase-start (constantly :observed)})
        ctx (context)
        phase (interceptor)
        calls (atom [])]
    (with-redefs [events/publish-phase-started! (constantly nil)
                  events/start-phase-heartbeat! (constantly owned)
                  events/stop-phase-heartbeat! (constantly nil)
                  stream/observe-heartbeat-progress! (partial observe! calls)]
      (is (= :observed ((:on-phase-start wrapped) ctx phase)))
      (let [active ((:phase-context wrapped) ctx phase)]
        ((:execution/report-progress! active) :samples 1)
        (is (= [[owned :samples 1]] @calls))
        (is (not (contains? (checkpoints/build-machine-snapshot active)
                           :execution/report-progress!))))
      ((:stop-phase-heartbeats! wrapped)))))

(comment ::activation-captured-progress)
