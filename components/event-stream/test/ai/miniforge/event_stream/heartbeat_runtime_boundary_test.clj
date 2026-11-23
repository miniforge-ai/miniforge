;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-runtime-boundary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.heartbeat-scheduler :as scheduler]
            [ai.miniforge.event-stream.heartbeat-runtime-fixtures :as fixture]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-invalid-starts [stream _ _clock tasks]
  (let [workflow-id (random-uuid)]
    (doseq [opts [nil {:interval-ms nil} {:interval-ms 0} {:interval-ms -1}
                  {:progress-sources nil} {:progress-sources #{:work}}
                  {:progress-sources #{:workflow/phase-heartbeat}} {:unsupported true}]]
      (is (anomaly/anomaly? (events/start-heartbeat! stream workflow-id :opsv/execute opts))))
    (is (anomaly/anomaly? (events/start-heartbeat! stream nil :opsv/execute)))
    (is (anomaly/anomaly? (events/start-heartbeat! stream workflow-id nil)))
    (events/close-current-event-stream! stream)
    (is (anomaly/anomaly? (events/start-heartbeat! stream workflow-id :opsv/execute)))
    (is (empty? @tasks))))

(defn- ^{:stratum 0} verify-invalid-observations [stream _ _clock _tasks]
  (let [handle (fixture/start stream (random-uuid) #{:fixture/work})]
    (doseq [[source position] [[:fixture/work 0] [:fixture/work -1] [:fixture/work nil]
                               [:work 1] [:workflow/phase-heartbeat 1]]]
      (is (anomaly/anomaly? (events/observe-heartbeat-progress! handle source position))))
    (is (anomaly/anomaly? (events/stop-heartbeat! handle {:may-interrupt? :yes})))
    (events/stop-heartbeat! handle)))

(defn- ^{:stratum 0} throw-failure [failure & _] (throw+ failure))

(defn- ^{:stratum 0} verify-real-stop [stream _]
  (let [handle (events/start-heartbeat! stream (random-uuid) :opsv/execute {:interval-ms 60000})
        future (:heartbeat/future handle)
        executor (:heartbeat/executor handle)]
    (try+ (is (nil? (events/stop-heartbeat! handle)))
      (is (.isCancelled ^java.util.concurrent.ScheduledFuture future))
      (is (.isShutdown ^java.util.concurrent.ScheduledExecutorService executor))
      (is (zero? (events/heartbeat-dropped-count handle)))
      (finally (events/stop-heartbeat! handle)))))

(deftest ^{:stratum 0} malformed-handles-return-anomalies-not-fabricated-counters
  (doseq [handle [nil {} {:heartbeat/current? true :heartbeat/state (atom {})}]]
    (is (anomaly/anomaly? (events/heartbeat-dropped-count handle)))
    (is (anomaly/anomaly? (events/observe-heartbeat-progress! handle :fixture/work 1)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-start-failures [stream _]
  (let [failure (anomaly/anomaly :unavailable "fixture scheduling unavailable" {})
        fatal (Error. "fixture scheduling fatal")]
    (with-redefs [scheduler/start! (partial throw-failure failure)]
      (is (identical? failure (fixture/start stream (random-uuid) :unknown))))
    (with-redefs [scheduler/start! (partial throw-failure fatal)]
      (try+ (fixture/start stream (random-uuid) :unknown)
        (is false "fatal scheduling errors must propagate")
        (catch Error caught (is (identical? fatal caught)))))))

(defn- ^{:stratum 1} verify-cancellation-failure [stream _]
  (let [handle (events/start-heartbeat! stream (random-uuid) :opsv/execute {:interval-ms 60000})
        failure (anomaly/anomaly :unavailable "fixture cancellation unavailable" {})]
    (try+
      (with-redefs [scheduler/cancel-future! (partial throw-failure failure)]
        (is (identical? failure (events/stop-heartbeat! handle {:may-interrupt? true}))))
      (is (.isShutdown ^java.util.concurrent.ScheduledExecutorService (:heartbeat/executor handle)))
      (is (nil? (events/observe-heartbeat-progress! handle :fixture/work 1)))
      (finally (events/stop-heartbeat! handle)))))

(deftest ^{:stratum 1} invalid-or-closed-current-starts-allocate-no-executor
  (fixture/with-runtime verify-invalid-starts))

(deftest ^{:stratum 1} current-progress-and-stop-options-have-typed-boundaries
  (fixture/with-runtime verify-invalid-observations))

(deftest ^{:stratum 1} current-stop-releases-real-scheduler-resources
  (journal/with-stream verify-real-stop))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} scheduler-start-failures-preserve-anomalies-and-fatal-errors
  (journal/with-stream verify-start-failures))

(deftest ^{:stratum 2} cancellation-failure-keeps-closure-and-attempts-executor-cleanup
  (journal/with-stream verify-cancellation-failure))

(comment ::current-heartbeat-boundary-acceptance)
