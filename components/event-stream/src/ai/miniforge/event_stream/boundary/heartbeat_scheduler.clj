;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.heartbeat-scheduler
  "Own scheduler resources independently of heartbeat publication semantics."
  (:require [ai.miniforge.event-stream.boundary.critical :as critical]
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

(defn- ^{:stratum 0} release-failed! [^ScheduledExecutorService executor failure]
  (try+
    (.shutdownNow executor)
    (catch Throwable cleanup
      (when-let [fatal (or (critical/cause failure) (critical/cause cleanup))]
        (critical/propagate! fatal))
      (when-not (identical? failure cleanup) (.addSuppressed ^Throwable failure cleanup)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stop!
  ([handle] (stop! handle {}))
  ([handle {:keys [may-interrupt?] :or {may-interrupt? false}}]
   (try+
     (when-let [^ScheduledFuture future (:heartbeat/future handle)]
       (cancel-future! future may-interrupt?))
     (finally
       (when-let [^ScheduledExecutorService executor (:heartbeat/executor handle)]
         (.shutdown executor))))
   nil))

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
    (catch Throwable failure
      (release-failed! executor failure)
      (throw failure))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [phase-id interval-ms task]
  (let [executor (Executors/newSingleThreadScheduledExecutor (daemon-thread-factory phase-id))]
    (schedule-on! executor interval-ms task)))

(comment ::scheduler-resource-ownership)
