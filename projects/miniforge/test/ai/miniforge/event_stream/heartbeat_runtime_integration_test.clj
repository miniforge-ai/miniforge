;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-runtime-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.current-publication :as publication]
            [ai.miniforge.event-stream.heartbeat-runtime-fixtures :as fixture]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-unknown [stream _ clock tasks]
  (let [handle (events/start-heartbeat! stream (random-uuid) :opsv/execute)]
    (fixture/advance! clock 10 90)
    (events/observe-heartbeat-progress! handle :fixture/work 1)
    (let [receipt (fixture/tick! tasks)]
      (is (= :unknown (:phase/progress-status receipt)))
      (is (= 10 (:heartbeat/elapsed-ms receipt)))
      (doseq [key [:phase/progress-count :phase/last-progress-at :phase/progress-gap-ms]]
        (is (not (contains? receipt key)))))
    (is (zero? (events/heartbeat-dropped-count handle)))
    (events/stop-heartbeat! handle)))

(defn- ^{:stratum 0} verify-progress [stream options clock tasks]
  (let [handle (fixture/start stream (random-uuid) #{:fixture/work})]
    (fixture/advance! clock 10 90)
    (is (= :none (:phase/progress-status (fixture/tick! tasks))))
    (fixture/advance! clock 20 80)
    (is (nil? (events/observe-heartbeat-progress! handle :fixture/work 1)))
    (fixture/advance! clock 40 70)
    (let [receipt (fixture/tick! tasks)]
      (is (= :observed (:phase/progress-status receipt)))
      (is (= 20 (:phase/progress-gap-ms receipt)))
      (is (= 1 (:phase/progress-count receipt))))
    (fixture/advance! clock 50 60)
    (events/observe-heartbeat-progress! handle :fixture/work 1)
    (events/observe-heartbeat-progress! handle :fixture/undeclared 2)
    (fixture/advance! clock 60 50)
    (let [receipt (fixture/tick! tasks)
          retained (events/get-events stream)]
      (is (= 40 (:phase/progress-gap-ms receipt)))
      (is (= 1 (:phase/progress-count receipt)))
      (is (= [0 1 2] (mapv :event/sequence-number retained)))
      (events/stop-heartbeat! handle)
      (events/close-current-event-stream! stream)
      (journal/with-options options (partial fixture/verify-retained retained)))))

(defn- ^{:stratum 0} verify-drops [stream _ clock tasks]
  (let [handle (fixture/start stream (random-uuid) #{:fixture/work})
        failure (anomaly/anomaly :unavailable "fixture unavailable" {})]
    (fixture/advance! clock 10 90)
    (fixture/tick! tasks)
    (fixture/advance! clock 20 80)
    (events/observe-heartbeat-progress! handle :fixture/work 1)
    (fixture/advance! clock 40 70)
    (with-redefs [publication/publish! (constantly failure)]
      (is (identical? failure (fixture/tick! tasks))))
    (is (= 1 (events/heartbeat-dropped-count handle)))
    (fixture/advance! clock 50 60)
    (let [receipt (fixture/tick! tasks)]
      (is (= 1 (:event/sequence-number receipt)))
      (is (= 3 (:heartbeat/sequence receipt)))
      (is (= 30 (:phase/progress-gap-ms receipt)))
      (is (= 1 (:phase/progress-count receipt))))
    (events/stop-heartbeat! handle)))

(defn- ^{:stratum 0} verify-reentry [stream _ clock tasks]
  (let [workflow-id (random-uuid)
        previous (fixture/start stream workflow-id #{:fixture/work})
        old-tick (last @tasks)
        first-receipt (old-tick)]
    (events/stop-heartbeat! previous)
    (fixture/advance! clock 20 80)
    (let [current (fixture/start stream workflow-id #{:fixture/work})]
      (is (nil? (old-tick)))
      (events/observe-heartbeat-progress! previous :fixture/work 1)
      (fixture/advance! clock 30 70)
      (let [receipt (fixture/tick! tasks)]
        (is (not= (:heartbeat/activation-id first-receipt) (:heartbeat/activation-id receipt)))
        (is (= :none (:phase/progress-status receipt)))
        (is (= 10 (:phase/progress-gap-ms receipt)))
        (is (= 1 (:heartbeat/sequence receipt))))
      (events/stop-heartbeat! current)
      (is (nil? (fixture/tick! tasks)))
      (is (= 2 (count (events/get-events stream)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unknown-coverage-is-not-fabricated-by-ticks-or-observations
  (fixture/with-runtime verify-unknown))

(deftest ^{:stratum 1} actual-progress-and-monotonic-gaps-survive-publication-and-replay
  (fixture/with-runtime verify-progress))

(deftest ^{:stratum 1} failed-ephemeral-attempts-do-not-consume-positions-or-reset-progress
  (fixture/with-runtime verify-drops))

(deftest ^{:stratum 1} stopped-activation-cannot-publish-or-affect-reentry
  (fixture/with-runtime verify-reentry))

(comment ::current-heartbeat-runtime-acceptance)
