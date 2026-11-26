;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.directory-lease
  "Explicit inode-pin lifetime; portable across JVM and native Babashka."
  (:import [java.io Closeable]))

;------------------------------------------------------------------------------ Layer 0

(defprotocol ^{:stratum 0} DirectoryLease
  (release! [pin] "Invalidate the pin and release its native resource, idempotently.")
  (live? [pin] "True only while the native pin remains retained and live."))

(defprotocol ^{:stratum 0} DescriptorIdentity
  (identity-of [pin] "Device/inode from the held descriptor, or nil after loss/release."))

;------------------------------------------------------------------------------ Layer 1

(deftype ^{:stratum 1} PinnedDirectory [stream closed]
  DirectoryLease
  (release! [_]
    (when (compare-and-set! closed false true) (.close ^Closeable stream)))
  (live? [_] (not @closed)))

(comment (satisfies? DirectoryLease nil))
