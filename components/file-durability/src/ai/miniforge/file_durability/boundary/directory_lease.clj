;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.directory-lease
  "Explicit inode-pin lifetime; portable across JVM and native Babashka."
  (:import [java.io Closeable]))

;------------------------------------------------------------------------------ Layer 0

(defprotocol ^{:stratum 0} DirectoryLease
  (release! [pin] "Invalidate the pin and close its native stream, idempotently.")
  (live? [pin] "True until the owning host releases this pin."))

(defn ^{:stratum 0} create [stream]
  (PinnedDirectory. stream (atom false)))

;------------------------------------------------------------------------------ Layer 1

(deftype ^{:stratum 1 :private true} PinnedDirectory [stream closed]
  DirectoryLease
  (release! [_]
    (when (compare-and-set! closed false true) (.close ^Closeable stream)))
  (live? [_] (not @closed)))

(comment (satisfies? DirectoryLease nil))
