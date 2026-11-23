;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.heartbeat-scheduler
  "Own scheduler resources independently of heartbeat publication semantics."
  (:require [ai.miniforge.event-stream.boundary.heartbeat-cleanup :as cleanup]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private thread-counter (AtomicLong. 0))

(defn- ^{:stratum 0} cancel-future! [^ScheduledFuture future interrupt?]
  (.cancel future (boolean interrupt?)))

(defn- ^{:stratum 0} schedule-future! [^ScheduledExecutorService executor interval-ms task]
  (.scheduleAtFixedRate executor ^Runnable task ^long interval-ms
                       ^long interval-ms TimeUnit/MILLISECONDS))

(defn- ^{:stratum 0} shutdown! [handle]
  (when-let [^ScheduledExecutorService executor (:heartbeat/executor handle)]
    (.shutdown executor)))

(defn- ^{:stratum 0} shutdown-now! [^ScheduledExecutorService executor]
  (.shutdownNow executor))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} cancel! [handle opts]
  (when-let [future (:heartbeat/future handle)]
    (cancel-future! future (get opts :may-interrupt? false))))

(defn- ^{:stratum 1} daemon-thread-factory [phase-id]
  (let [n (.incrementAndGet ^AtomicLong thread-counter)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str "miniforge-phase-heartbeat-" (name phase-id) "-" n))
          (.setDaemon true))))))

(defn ^{:stratum 1} schedule-on!
  "Take ownership of executor, including when scheduling fails. Propagate the
   original failure only after cleanup at this resource-allocation boundary."
  [^ScheduledExecutorService executor interval-ms task]
  (try+
    (let [future (schedule-future! executor interval-ms task)]
      {:heartbeat/executor executor :heartbeat/future future})
    (catch Object _
      (cleanup/after-failure! (:throwable &throw-context) (partial shutdown-now! executor)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stop!
  ([handle] (stop! handle {}))
  ([handle opts]
   (cleanup/finish! (partial cancel! handle opts) (partial shutdown! handle))))

(defn ^{:stratum 2} start! [phase-id interval-ms task]
  (let [executor (Executors/newSingleThreadScheduledExecutor (daemon-thread-factory phase-id))]
    (schedule-on! executor interval-ms task)))

(comment ::scheduler-resource-ownership)
