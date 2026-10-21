;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-scheduler-cleanup-test
  "JVM fault injection: Babashka cannot proxy ScheduledThreadPoolExecutor."
  (:require [ai.miniforge.event-stream.boundary.heartbeat-scheduler :as scheduler]
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
   [(Error. "fixture schedule fatal") (Exception. "fixture cleanup failure") :primary]])

(defn- ^{:stratum 0} idle-task [] nil)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} cleanup-failure-preserves-fatal-priority-and-ordinary-cause
  (doseq [[primary cleanup winner] (failure-cases)]
    (let [attempted (atom false)
          executor (failing-executor primary cleanup attempted)
          expected (if (= :primary winner) primary cleanup)]
      (try+ (scheduler/schedule-on! executor 1 idle-task)
        (is false "scheduling must fail")
        (catch Throwable caught
          (is (identical? expected caught))
          (is @attempted))
        (finally (.shutdown executor))))))

(comment ::jvm-resource-faults)
