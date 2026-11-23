;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.directory-pin
  "Keep a witnessed native directory inode allocated until its owner closes it."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-pin-scan :as scan]
            [ai.miniforge.file-durability.boundary.directory-pin-witness :as witness]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory])
  (:import [java.nio.file Files Path]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} native-streams
  "These qualified Unix implementations retain opendir state until close, not EOF."
  #{"sun.nio.fs.UnixDirectoryStream" "sun.nio.fs.UnixSecureDirectoryStream"})

(defn- ^{:stratum 0} open-stream! [^Path root]
  (Files/newDirectoryStream root))

(defn- ^{:stratum 0} admitted-root? [^Path root limit until]
  (and (scan/admitted? limit until)
       (= root (directory/existing! (.toFile root)))
       (= root (acl/check! root))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} bind! [root limit until name stream]
  (if (and (contains? native-streams (.getName (class stream)))
           (scan/contains-witness? stream name limit until))
    (lease/->PinnedDirectory stream (atom false))
    (boundary/unqualified-store root :storage/directory-pin)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} open!
  "Return an owned DirectoryLease or an anomaly. Limit and absolute monotonic
   deadline belong to trusted caller configuration; filesystem exceptions flow
   to the enclosing component boundary. This does not qualify storage."
  [root limit until]
  (if (admitted-root? root limit until)
    (witness/with-stream! root (partial open-stream! root) (partial bind! root limit until))
    (boundary/unqualified-store root :storage/directory-pin)))

(comment (open! (.toPath (java.io.File. ".")) 0 (System/nanoTime)))
