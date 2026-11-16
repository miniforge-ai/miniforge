;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-file-policy
  "No-follow POSIX ownership and write protection for authority and lock files."
  (:require [babashka.fs :as fs])
  (:import [java.nio.file.attribute PosixFilePermission]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} other-writes
  "File-content writes are unsafe even when the enclosing directory protects replacement."
  #{PosixFilePermission/GROUP_WRITE PosixFilePermission/OTHERS_WRITE})

(def ^{:stratum 0 :private true} root-owner
  "The privileged account can own trusted read-shared authority files."
  "root")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} protected? [file]
  (let [attributes (fs/read-attributes file "posix:*" {:nofollow-links true})
        trusted-owners (hash-set root-owner (System/getProperty "user.name"))]
    (and (:isRegularFile attributes)
         (contains? trusted-owners (str (:owner attributes)))
         (not-any? other-writes (:permissions attributes)))))
