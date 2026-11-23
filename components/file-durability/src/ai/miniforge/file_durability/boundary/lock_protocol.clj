;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-protocol
  "Exercise OS lock exclusion in both directions on one fresh inode."
  (:require [babashka.fs :as fs])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} wait-ms
  "A cold JVM must start and complete its half of the probe within a fixed deadline."
  20000)

(def ^{:stratum 0 :private true} poll-ms
  "Wait for marker publication without spinning or assuming scheduling order."
  15)

(def ^{:stratum 0 :private true} probe-inode
  "Neither process locks any real authority file."
  "probe-lock")

(def ^{:stratum 0 :private true} lock-options
  "Opening a replaced symlink must not lock a file outside the probe directory."
  #{StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS})

(defn ^{:stratum 0} runtime-kind []
  (if (System/getProperty "babashka.version") :babashka :jvm))

(defn- ^{:stratum 0} marker-path [root marker]
  (fs/path root (name marker)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} allocate! [root]
  (Files/createFile (fs/path root probe-inode) (make-array FileAttribute 0)))

(defn ^{:stratum 1} signal! [root marker]
  (Files/createFile (marker-path root marker) (make-array FileAttribute 0))
  true)

(defn- ^{:stratum 1} wait-for-next-poll! [start]
  (when (< (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) start)) wait-ms)
    (Thread/sleep poll-ms)
    true))

(defn ^{:stratum 1} with-attempt [root f]
  (with-open [channel (FileChannel/open (fs/path root probe-inode) lock-options
                                        (make-array FileAttribute 0))]
    (if-let [^java.lang.AutoCloseable lock (.tryLock channel)]
      (with-open [_held lock] (f true))
      (f false))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} await? [root marker]
  (let [path (marker-path root marker)
        start (System/nanoTime)]
    (boolean
     (loop []
       (or (fs/regular-file? path {:nofollow-links true})
           (and (wait-for-next-poll! start) (recur)))))))

(comment (runtime-kind))
