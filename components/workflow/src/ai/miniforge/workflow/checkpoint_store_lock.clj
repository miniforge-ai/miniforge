;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-lock
  "One lock per checkpointed run, across processes: a file lock on
   `<root>/.locks/<run-id>.lock` taken with a timed `tryLock`, behind a
   timed in-process lock (a JVM may not hold two locks on one file, and
   threads of one process would otherwise race to the file lock).

   Pinning a run and the checkpoint GC's decision to delete it both run
   under it, so a pin either lands before the GC looks (and the run is
   kept) or after the run is gone (and the pin is refused)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.messages :as messages]
   [babashka.fs :as fs])
  (:import
   (java.nio.channels FileChannel)
   (java.nio.file OpenOption StandardOpenOption)
   (java.util.concurrent TimeUnit)
   (java.util.concurrent.locks ReentrantLock)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} lock-timeout-ms
  "How long to wait for another holder of a run's lock before giving up."
  10000)

(def ^{:stratum 0} ^:private lock-poll-ms 25)

(def ^{:stratum 0} locks-directory-name
  "Directory under the checkpoint root holding the per-run lock files. Not
   a run directory: the GC passes over it."
  ".locks")

(defonce ^{:stratum 0} ^:private process-locks (atom {}))

(defn- ^{:stratum 0} busy [workflow-run-id path]
  (anomaly/anomaly :unavailable
                   (messages/t :checkpoint/lock-busy {:run-id workflow-run-id})
                   {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} process-lock
  "The one in-process lock for `lock-file`."
  ^ReentrantLock [lock-file]
  (get (swap! process-locks update (str lock-file) #(or % (ReentrantLock.)))
       (str lock-file)))

(defn ^{:stratum 1} lock-file
  "The lock file of run `workflow-run-id` under `checkpoint-root`."
  [checkpoint-root workflow-run-id]
  (fs/path checkpoint-root locks-directory-name (str workflow-run-id ".lock")))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-run-lock
  "`(f)` while holding run `workflow-run-id`'s lock under `checkpoint-root`:
   the in-process lock, then the file lock, both within one deadline of
   `lock-timeout-ms`. Returns what `f` returns, or an `:unavailable`
   anomaly when another holder keeps the lock past the deadline.
   Exceptions from `f` propagate after the lock is released."
  [checkpoint-root workflow-run-id f]
  (let [path (lock-file checkpoint-root workflow-run-id)
        deadline (+ (System/nanoTime) (* lock-timeout-ms 1000000))
        in-process (process-lock path)]
    (if-not (.tryLock in-process (max 0 (- deadline (System/nanoTime))) TimeUnit/NANOSECONDS)
      (busy workflow-run-id path)
      (try
        (fs/create-dirs (fs/parent path))
        (with-open [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE
                                                                           StandardOpenOption/WRITE]))]
          ;; Hinted AutoCloseable: babashka does not expose FileLock.
          (let [^java.lang.AutoCloseable held (loop []
                                                (or (.tryLock channel)
                                                    (when (pos? (- deadline (System/nanoTime)))
                                                      (Thread/sleep lock-poll-ms)
                                                      (recur))))]
            (if held
              (try (f) (finally (.close held)))
              (busy workflow-run-id path))))
        (finally (.unlock in-process))))))

(comment
  (with-run-lock "/tmp/checkpoints" (random-uuid) (constantly :held)))
