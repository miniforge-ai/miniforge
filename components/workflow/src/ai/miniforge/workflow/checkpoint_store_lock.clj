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
   [babashka.fs :as fs]
   [slingshot.slingshot :refer [try+]])
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

(defn- ^{:stratum 1} acquire-file-lock!
  "Open run `workflow-run-id`'s lock file and take its lock by `deadline`
   (nanoTime). Returns `{:channel :lock}`, or an `:unavailable` anomaly:
   busy past the deadline, or an I/O error making the lock directory,
   opening the file or locking it."
  [path deadline workflow-run-id]
  (try+
    (fs/create-dirs (fs/parent path))
    (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE
                                                                 StandardOpenOption/WRITE]))]
      (try+
        ;; Hinted AutoCloseable: babashka does not expose FileLock.
        (let [^java.lang.AutoCloseable held (loop []
                                              (or (.tryLock channel)
                                                  (when (pos? (- deadline (System/nanoTime)))
                                                    (Thread/sleep lock-poll-ms)
                                                    (recur))))]
          (if held
            {:channel channel :lock held}
            (do (.close channel) (busy workflow-run-id path))))
        (catch Exception e
          (.close channel)
          (throw e))))
    (catch Exception e
      (anomaly/exception-anomaly :unavailable
                                 (messages/t :checkpoint/lock-failed
                                             {:run-id workflow-run-id
                                              :error (or (ex-message e) (.getName (class e)))})
                                 {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}
                                 e))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-run-lock
  "`(f)` while holding run `workflow-run-id`'s lock under `checkpoint-root`:
   the in-process lock, then the file lock, both within one deadline of
   `lock-timeout-ms`. Returns what `f` returns, or an `:unavailable`
   anomaly when the lock stays busy past the deadline or cannot be taken
   (an I/O error). Exceptions from `f` propagate after the lock is
   released. Lock files are never deleted: a waiter holding the old file
   would otherwise share the lock with a caller that made a new one."
  [checkpoint-root workflow-run-id f]
  (let [path (lock-file checkpoint-root workflow-run-id)
        deadline (+ (System/nanoTime) (* lock-timeout-ms 1000000))
        in-process (process-lock path)]
    (if-not (.tryLock in-process (max 0 (- deadline (System/nanoTime))) TimeUnit/NANOSECONDS)
      (busy workflow-run-id path)
      (try
        (let [acquired (acquire-file-lock! path deadline workflow-run-id)]
          (if (anomaly/anomaly? acquired)
            acquired
            (let [{:keys [channel lock]} acquired]
              (try (f)
                   (finally (.close ^java.lang.AutoCloseable lock)
                            (.close ^java.lang.AutoCloseable channel))))))
        (finally (.unlock in-process))))))

(comment
  (with-run-lock "/tmp/checkpoints" (random-uuid) (constantly :held)))
