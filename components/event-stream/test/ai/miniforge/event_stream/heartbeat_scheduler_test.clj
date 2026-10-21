;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-scheduler-test
  (:require [ai.miniforge.event-stream.boundary.heartbeat-scheduler :as scheduler]
            [ai.miniforge.event-stream.heartbeat-scheduler-fixtures :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent Executors ScheduledFuture]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private idle-interval-ms 60000)

(defn- ^{:stratum 0} idle-task [] nil)

(defn- ^{:stratum 0} throw-failure [failure & _] (throw failure))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} failed-scheduling-releases-executor-and-preserves-failure
  (doseq [failure [(Exception. "fixture scheduling failure") (Error. "fixture fatal failure")
                   (fixture/structured-failure)]]
    (let [executor (Executors/newSingleThreadScheduledExecutor)]
      (try+ (with-redefs [scheduler/schedule-future! (partial throw-failure failure)]
              (scheduler/schedule-on! executor idle-interval-ms idle-task))
        (is false "scheduling must fail")
        (catch Object _
          (is (identical? failure (:throwable &throw-context)))
          (is (.isShutdown executor)))
        (finally (.shutdownNow executor))))))

(deftest ^{:stratum 1} stop-cancels-and-releases-owned-resources-idempotently
  (let [handle (scheduler/start! :fixture idle-interval-ms idle-task)
        executor (:heartbeat/executor handle)
        future (:heartbeat/future handle)]
    (try+ (scheduler/stop! handle)
      (is (.isCancelled ^ScheduledFuture future))
      (is (.isShutdown ^java.util.concurrent.ScheduledExecutorService executor))
      (is (nil? (scheduler/stop! handle)))
      (is (nil? (scheduler/stop! nil)))
      (finally (scheduler/stop! handle)))))

(deftest ^{:stratum 1} cancellation-failure-does-not-leak-executor
  (let [failure (Exception. "fixture cancellation failure")
        handle (scheduler/start! :fixture idle-interval-ms idle-task)
        executor (:heartbeat/executor handle)]
    (try+ (with-redefs [scheduler/cancel-future! (partial throw-failure failure)]
            (scheduler/stop! handle))
      (is false "cancellation must fail")
      (catch Throwable caught
        (is (identical? failure caught))
        (is (.isShutdown executor)))
      (finally (.shutdownNow executor)))))

(comment ::scheduler-cleanup-acceptance)
