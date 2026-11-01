;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-pin-publication-support
  (:require [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock])
  (:import [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks LockSupport ReentrantLock]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} wait-seconds 10)

(def ^{:stratum 0} wait-ms 10000)

(defn ^{:stratum 0} latch [] (CountDownLatch. 1))

(def ^{:stratum 0} ^:private contention-poll-nanos 1000000)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} await-contention! [root id collect]
  (let [path (checkpoint-lock/lock-file root id)
        lock (#'checkpoint-lock/process-lock path)
        deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS wait-seconds))]
    (while (and (not (.hasQueuedThreads ^ReentrantLock lock))
                (not (realized? collect))
                (< (System/nanoTime) deadline))
      (LockSupport/parkNanos contention-poll-nanos))
    (.hasQueuedThreads ^ReentrantLock lock)))

(defn ^{:stratum 1} await! [^CountDownLatch signal]
  (.await signal wait-seconds TimeUnit/SECONDS))

(defn ^{:stratum 1} before-pin-temporary! [create! inside release options]
  ;; Pause before reservation changes the directory's mtime and GC sees it as fresh.
  (when (= paths/pin-marker-temp-prefix (:prefix options))
    (.countDown ^CountDownLatch inside)
    (.await ^CountDownLatch release wait-seconds TimeUnit/SECONDS))
  (create! options))

(comment ::pin-publication-interleaving)
