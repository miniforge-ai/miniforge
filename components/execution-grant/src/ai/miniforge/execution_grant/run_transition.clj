;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-transition
  "Nonblocking per-run process locks cover the complete read/confirm/publish transition."
  (:require [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.execution-grant.run-storage :as storage]
            [clojure.java.io :as io])
  (:import [java.nio.channels FileChannel]
           [java.nio.file LinkOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util.concurrent.locks ReentrantLock]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} stripe-count
  "Bound lock bookkeeping at 64 stripes; collisions refuse transiently, never weaken serialization."
  64)

(def ^{:stratum 0 :private true} lock-suffix
  "Stable run-specific lock inodes live beside the reserved identity directories."
  ".lock")

(def ^{:stratum 0 :private true} options
  "Create the stable lock inode without following a final symlink."
  #{StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS})

(def ^{:stratum 0 :private true} permissions
  "Only the authority owner can write newly created transition locks."
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                             (PosixFilePermissions/fromString "rw-------"))]))

(defn- ^{:stratum 0} held-call! [^FileChannel channel directory id operation]
  (if-let [^java.lang.AutoCloseable held (.tryLock channel)]
    (with-open [_held held] (operation directory))
    (storage/failure id :conflict :run/id-conflict)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stripe-index [directory id]
  (mod (hash (str directory id)) stripe-count))

(defonce ^{:stratum 1 :private true
           :doc "Bound local exclusion prevents secondary descriptors releasing an owned OS lock."} local-locks
  ;; Opening/closing another descriptor can release this process's POSIX file lock.
  (vec (repeatedly stripe-count #(ReentrantLock.))))

(defn- ^{:stratum 1} locked-call! [directory id operation]
  (let [file (io/file directory (str id lock-suffix))]
    (with-open [channel (FileChannel/open (.toPath file) options permissions)]
      (if-not (true? (durability/file-protection file))
        (storage/failure id :fault :run/write-failed)
        (held-call! channel directory id operation)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call-with-exception-handling! [directory id operation]
  (let [^ReentrantLock local (nth local-locks (stripe-index directory id))]
    (if (and (not (.isHeldByCurrentThread local)) (.tryLock local))
      (try
        (locked-call! directory id operation)
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          (storage/failure id :fault :run/write-failed))
        (catch Exception _ (storage/failure id :fault :run/write-failed))
        (finally (.unlock local)))
      (storage/failure id :conflict :run/id-conflict))))
