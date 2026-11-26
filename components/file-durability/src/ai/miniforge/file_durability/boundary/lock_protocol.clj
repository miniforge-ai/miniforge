;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-protocol
  "Exercise OS lock exclusion in both directions on one fresh inode."
  (:require [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]
            [babashka.fs :as fs])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

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

(defn- ^{:stratum 0} pause! [milliseconds] (Thread/sleep milliseconds))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} wait-for-next-poll! [until poll-ms]
  (when (deadline/live? until)
    (pause! (min poll-ms (deadline/remaining-ms until)))
    true))

(defn ^{:stratum 1} allocate! [root]
  (Files/createFile (fs/path root probe-inode) (make-array FileAttribute 0)))

(defn ^{:stratum 1} signal! [root marker]
  (Files/createFile (marker-path root marker) (make-array FileAttribute 0))
  true)

(defn ^{:stratum 1} with-attempt [root f]
  (with-open [channel (FileChannel/open (fs/path root probe-inode) lock-options
                                        (make-array FileAttribute 0))]
    (if-let [^java.lang.AutoCloseable lock (.tryLock channel)]
      (with-open [_held lock] (f true))
      (f false))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} await? [root marker until poll-ms]
  (let [path (marker-path root marker)]
    (boolean
     (loop []
       (and (deadline/live? until)
            (or (and (fs/regular-file? path {:nofollow-links true}) (deadline/live? until))
                (and (wait-for-next-poll! until poll-ms) (recur))))))))

(comment (runtime-kind))
