;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-scheduler-cleanup-test
  "JVM fault injection: Babashka cannot proxy ScheduledThreadPoolExecutor."
  (:require [ai.miniforge.event-stream.boundary.heartbeat-scheduler :as scheduler]
            [ai.miniforge.event-stream.heartbeat-scheduler-fixtures :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failing-executor [primary cleanup attempted]
  (proxy [ScheduledThreadPoolExecutor] [1]
    (scheduleAtFixedRate [_task _initial _period _unit] (throw primary))
    (shutdownNow [] (reset! attempted true) (throw cleanup))))

(defn- ^{:stratum 0} failure-cases []
  [[(Exception. "fixture schedule failure") (Exception. "fixture cleanup failure") :primary]
   [(Exception. "fixture schedule failure") (Error. "fixture cleanup fatal") :cleanup]
   [(Error. "fixture schedule fatal") (Exception. "fixture cleanup failure") :primary]
   [(Error. "fixture schedule fatal") (fixture/structured-failure) :primary]
   [(fixture/structured-failure) (Exception. "fixture cleanup failure") :primary]
   [(Exception. "fixture schedule failure") (fixture/structured-failure) :primary]])

(defn- ^{:stratum 0} idle-task [] nil)

(defn- ^{:stratum 0} failing-stop-executor [cleanup attempted]
  (proxy [ScheduledThreadPoolExecutor] [1]
    (shutdown [] (proxy-super shutdown) (reset! attempted true) (throw cleanup))))

(defn- ^{:stratum 0} throw-failure [failure & _] (throw failure))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} cleanup-failure-preserves-fatal-priority-and-ordinary-cause
  (doseq [[primary cleanup winner] (failure-cases)]
    (let [attempted (atom false)
          executor (failing-executor primary cleanup attempted)
          expected (if (= :primary winner) primary cleanup)]
      (try+ (scheduler/schedule-on! executor 1 idle-task)
        (is false "scheduling must fail")
        (catch Object _
          (is (identical? expected (:throwable &throw-context)))
          (is @attempted))
        (finally (.shutdown executor))))))

(deftest ^{:stratum 1} stop-failures-use-the-same-fatal-and-structured-cleanup-policy
  (doseq [[primary cleanup winner] (failure-cases)]
    (let [attempted (atom false)
          executor (failing-stop-executor cleanup attempted)
          handle (scheduler/schedule-on! executor 60000 idle-task)
          expected (if (= :primary winner) primary cleanup)]
      (try+ (with-redefs [scheduler/cancel-future! (partial throw-failure primary)]
              (scheduler/stop! handle))
        (is false "stop must fail")
        (catch Object _
          (is (identical? expected (:throwable &throw-context)))
          (is @attempted))
        (finally (.shutdownNow executor))))))

(comment ::jvm-resource-faults)
