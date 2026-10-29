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
   names, by file identity (`file-key`), and starts over when it is not.

   Nothing opens a second descriptor on a locked file: POSIX record locks
   belong to the process, and closing any descriptor of the file releases
   them. A lock file or lock directory that is a symbolic link is refused,
   so a lock never writes outside its root."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.messages :as messages]
   [babashka.fs :as fs]
   [slingshot.slingshot :refer [try+]])
  (:import
   (java.nio.channels FileChannel)
   (java.nio.file Files LinkOption OpenOption StandardOpenOption)
   (java.nio.file.attribute BasicFileAttributes FileAttribute)
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

(defn- ^{:stratum 0} same-file?
  "True when the path named one file throughout: just before the open
   (`before`), just after it (`opened`) and once the lock is held (`now`).
   Then the opened file is that file, and the lock is on the file the path
   names. False when any sample differs or the file was absent before the
   open: a file the open itself created could have been unlinked and
   replaced before `opened` was sampled."
  [before opened now]
  (and (not= :absent before)
       (= before opened now)))

(defn- ^{:stratum 0} open-lock-channel
  "A write channel on lock file `path`, created if absent, never through a
   link (NOFOLLOW_LINKS: a link swapped in after the check fails here)."
  ^FileChannel [path]
  (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE
                                                 StandardOpenOption/WRITE
                                                 LinkOption/NOFOLLOW_LINKS])))

(defn- ^{:stratum 0} create-lock-file!
  "Create lock file `path` if absent, without opening it. Nil either way:
   a file another process made first is as good, and a failure shows on
   the next attempt."
  [path]
  (try+ (Files/createFile path (make-array FileAttribute 0)) nil
        (catch Exception _ nil)))

(defn- ^{:stratum 0} symlinked [workflow-run-id path]
  (anomaly/anomaly :unavailable
                   (messages/t :checkpoint/lock-symlink {:run-id workflow-run-id :path (str path)})
                   {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}))

(defn- ^{:stratum 0} busy [workflow-run-id path]
  (anomaly/anomaly :unavailable
                   (messages/t :checkpoint/lock-busy {:run-id workflow-run-id})
                   {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}))

(defn- ^{:stratum 0} file-key
  "`path`'s file identity (device and inode on Unix), not following a
   link: `:absent` when nothing is there, nil where the platform has no
   file keys. Reads attributes only, opening nothing."
  [path]
  (let [no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
    (if (Files/exists path no-follow)
      (try+ (.fileKey (Files/readAttributes path BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-follow))
            (catch Exception _ :absent))
      :absent)))

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
   anomaly: busy past the deadline, a symlinked lock file or directory,
   or an I/O error making the lock directory, opening the file or locking
   it."
  [path deadline workflow-run-id]
  (try+
    (loop []
      (if (or (fs/sym-link? (fs/parent path)) (fs/sym-link? path))
        (symlinked workflow-run-id path)
        (let [_ (fs/create-dirs (fs/parent path))
              before (file-key path)]
          (if (= :absent before)
            ;; Create it first, then sample again: identity is checked only
            ;; on a file that existed before the open.
            (do (create-lock-file! path)
                (if (pos? (- deadline (System/nanoTime)))
                  (recur)
                  (busy workflow-run-id path)))
            (let [channel (open-lock-channel path)
                  opened (file-key path)
                  outcome (try+
                          ;; Hinted AutoCloseable: babashka does not expose FileLock.
                          (let [^java.lang.AutoCloseable held (loop []
                                                                (or (.tryLock channel)
                                                                    (when (pos? (- deadline (System/nanoTime)))
                                                                      (Thread/sleep lock-poll-ms)
                                                                      (recur))))]
                            (cond
                              (nil? held) (do (.close channel) (busy workflow-run-id path))
                              (same-file? before opened (file-key path)) {:channel channel :lock held}
                              :else (do (.close held) (.close channel)
                                        (if (pos? (- deadline (System/nanoTime)))
                                          ::stale
                                          (busy workflow-run-id path)))))
                          (catch Exception e
                            (.close channel)
                            (throw e)))]
              (if (= ::stale outcome) (recur) outcome))))))
    (catch Exception e
      (anomaly/exception-anomaly :unavailable
                                 (messages/t :checkpoint/lock-failed
                                             {:run-id workflow-run-id
                                              :error (or (ex-message e) (.getName (class e)))})
                                 {:workflow/run-id workflow-run-id :checkpoint/lock (str path)}
                                 e))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} remove-lock-file!
  "Delete run `workflow-run-id`'s lock file. Call it only while holding
   that run's lock, from `with-run-lock`'s `f`. True when this call
   deleted it; false when it was not there or could not be deleted."
  [checkpoint-root workflow-run-id]
  (try+
    (boolean (fs/delete-if-exists (lock-file checkpoint-root workflow-run-id)))
    (catch Exception _ false)))

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

(comment
  (with-run-lock "/tmp/checkpoints" (random-uuid) (constantly :held)))
