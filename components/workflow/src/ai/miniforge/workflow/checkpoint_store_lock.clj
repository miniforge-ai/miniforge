;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-lock
  "One lock per checkpointed run, across processes: a file lock on
   `<root>/.locks/<run-id>.lock` behind a timed in-process lock. Pinning a
   run and the GC's decision to delete it both hold it.

   The GC deletes a lock file only while holding it (`remove-lock-file!`).
   Another process may be waiting on that file, and then lock it after it
   is gone, while a newcomer locks a new file at the same path; so every
   acquirer checks that the file it locked is still the one the path
   names (`holds-path?`) and starts over when it is not."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.messages :as messages]
   [babashka.fs :as fs]
   [slingshot.slingshot :refer [try+]])
  (:import
   (java.nio ByteBuffer)
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

(defn- ^{:stratum 0} holds-path?
  "True when `channel`, whose lock this process holds, is the file `path`
   names now: a fresh token written through the channel reads back by
   path. False when the file was unlinked or the path replaced."
  [^FileChannel channel path]
  (let [token (str (random-uuid))]
    (.truncate channel 0)
    (.write channel (ByteBuffer/wrap (.getBytes token "UTF-8")) 0)
    (= token (try+ (slurp (str path)) (catch Exception _ nil)))))

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
  "The lock file of run `workflow-run-id` under `checkpoint-root`, built
   from the root's real path, so every path to one root (a symlink, a
   relative path) names one lock file and one in-process lock."
  [checkpoint-root workflow-run-id]
  (let [root (if (fs/exists? checkpoint-root)
               (fs/real-path checkpoint-root)
               (fs/absolutize checkpoint-root))]
    (fs/path root locks-directory-name (str workflow-run-id ".lock"))))

(defn- ^{:stratum 1} acquire-file-lock!
  "Open run `workflow-run-id`'s lock file and take its lock by `deadline`
   (nanoTime), starting over while the locked file is no longer the one
   the path names. Returns `{:channel :lock}`, or an `:unavailable`
   anomaly: busy past the deadline, or an I/O error making the lock
   directory, opening the file or locking it."
  [path deadline workflow-run-id]
  (try+
    (loop []
      (fs/create-dirs (fs/parent path))
      (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE
                                                                   StandardOpenOption/WRITE]))
            outcome (try+
                      ;; Hinted AutoCloseable: babashka does not expose FileLock.
                      (let [^java.lang.AutoCloseable held (loop []
                                                            (or (.tryLock channel)
                                                                (when (pos? (- deadline (System/nanoTime)))
                                                                  (Thread/sleep lock-poll-ms)
                                                                  (recur))))]
                        (cond
                          (nil? held) (do (.close channel) (busy workflow-run-id path))
                          (holds-path? channel path) {:channel channel :lock held}
                          :else (do (.close held) (.close channel)
                                    (if (pos? (- deadline (System/nanoTime)))
                                      ::stale
                                      (busy workflow-run-id path)))))
                      (catch Exception e
                        (.close channel)
                        (throw e)))]
        (if (= ::stale outcome) (recur) outcome)))
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
   released. `f` may delete the lock file (`remove-lock-file!`): waiters
   that locked the old file start over on the path."
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

(defn ^{:stratum 2} remove-lock-file!
  "Delete run `workflow-run-id`'s lock file. Call it only while holding
   that run's lock, from `with-run-lock`'s `f`. Best effort: nil whether
   or not the file went."
  [checkpoint-root workflow-run-id]
  (try+
    (fs/delete-if-exists (lock-file checkpoint-root workflow-run-id))
    nil
    (catch Exception _ nil)))

(comment
  (with-run-lock "/tmp/checkpoints" (random-uuid) (constantly :held)))
