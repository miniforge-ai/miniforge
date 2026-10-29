;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-lock
  "One lock per checkpointed run, across processes: a file lock on one of
   256 fixed slot files under `<root>/.locks/` (the run id's hash picks
   the slot), behind a timed in-process lock on that file. Pinning a
   run and the GC's decision to delete it both hold it.

   Slot files are never deleted. A lock is therefore always on the file
   its path names, and no file accumulates per run. Two runs that share a
   slot only wait for each other. Each acquisition opens its own channel
   and closes it on release. The in-process lock keeps that the only
   channel on the file in this process, since closing any descriptor of a
   file releases the process's POSIX locks on it. A slot file or `.locks`
   directory that is a symbolic link is refused, so a lock never writes
   outside its root.

   `<run-id>.lock` files under `.locks/` were the previous design's, one
   per run; `legacy-lock-file` names them so the GC can remove them under
   the run's lock."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.messages :as messages]
   [babashka.fs :as fs]
   [slingshot.slingshot :refer [try+]])
  (:import
   (java.nio.channels FileChannel)
   (java.nio.file LinkOption OpenOption StandardOpenOption)
   (java.util.concurrent TimeUnit)
   (java.util.concurrent.locks ReentrantLock)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} lock-timeout-ms
  "How long to wait for another holder of a run's lock before giving up."
  10000)

(def ^{:stratum 0} ^:private lock-poll-ms 25)

(def ^{:stratum 0} locks-directory-name
  "Directory under the checkpoint root holding the lock slot files. Not a
   run directory: the GC passes over it."
  ".locks")

(defonce ^{:stratum 0} ^:private process-locks (atom {}))

(defn ^{:stratum 0} slot-name
  "The slot file name for run `workflow-run-id`: `slot-<nn>`, one of 256
   (hex), from the run id's String hash, which every JVM and babashka
   compute alike."
  [workflow-run-id]
  (format "slot-%02x" (mod (.hashCode (str workflow-run-id)) 256)))

(defn- ^{:stratum 0} busy [workflow-run-id path]
  (anomaly/anomaly :unavailable
                   (messages/t :checkpoint/lock-busy {:run-id workflow-run-id})
                   {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}))

(defn- ^{:stratum 0} symlinked [workflow-run-id path]
  (anomaly/anomaly :unavailable
                   (messages/t :checkpoint/lock-symlink {:run-id workflow-run-id :path (str path)})
                   {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}))

(defn- ^{:stratum 0} locks-dir
  "`<root>/.locks` (`locks-directory-name`), built from the root's real
   path, so every path to one root (a symlink, a relative path) names the
   same files and in-process locks."
  [checkpoint-root]
  (let [root (if (fs/exists? checkpoint-root)
               (fs/real-path checkpoint-root)
               (fs/absolutize checkpoint-root))]
    (fs/path root ".locks")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acquire-file-lock!
  "Open slot file `path` and take its lock by `deadline` (nanoTime).
   Returns `{:channel :lock}`, or an `:unavailable` anomaly: busy past
   the deadline, a symlinked slot file or directory, or an I/O error
   (making the directory, opening or locking the file) at once."
  [path deadline workflow-run-id]
  (try+
    (if (or (fs/sym-link? (fs/parent path)) (fs/sym-link? path))
      (symlinked workflow-run-id path)
      (do (fs/create-dirs (fs/parent path))
          (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE
                                                                       StandardOpenOption/WRITE
                                                                       LinkOption/NOFOLLOW_LINKS]))]
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
                (throw e))))))
    (catch Exception e
      (anomaly/exception-anomaly :unavailable
                                 (messages/t :checkpoint/lock-failed
                                             {:run-id workflow-run-id
                                              :error (or (ex-message e) (.getName (class e)))})
                                 {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}
                                 e))))

(defn- ^{:stratum 1} process-lock
  "The one in-process lock for slot file `lock-file`."
  ^ReentrantLock [lock-file]
  (get (swap! process-locks update (str lock-file) #(or % (ReentrantLock.)))
       (str lock-file)))

(defn ^{:stratum 1} lock-file
  "The slot file holding run `workflow-run-id`'s lock under
   `checkpoint-root`."
  [checkpoint-root workflow-run-id]
  (fs/path (locks-dir checkpoint-root) (slot-name workflow-run-id)))

(defn ^{:stratum 1} legacy-lock-file
  "Run `workflow-run-id`'s lock file from the previous, one-file-per-run
   design."
  [checkpoint-root workflow-run-id]
  (fs/path (locks-dir checkpoint-root) (str workflow-run-id ".lock")))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} remove-legacy-lock-file!
  "Delete run `workflow-run-id`'s legacy lock file. Call it only while
   holding that run's lock, from `with-run-lock`'s `f`. True when this
   call deleted it; false when it was not there or could not be deleted."
  [checkpoint-root workflow-run-id]
  (try+
    (boolean (fs/delete-if-exists (legacy-lock-file checkpoint-root workflow-run-id)))
    (catch Exception _ false)))

(defn ^{:stratum 2} with-run-lock
  "`(f)` while holding run `workflow-run-id`'s lock under `checkpoint-root`:
   the in-process lock, then the slot file's lock, both within one
   deadline of `lock-timeout-ms`. Returns what `f` returns, or an
   `:unavailable` anomaly when the lock stays busy past the deadline or
   cannot be taken (an I/O error). Exceptions from `f` propagate after the
   lock is released."
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
