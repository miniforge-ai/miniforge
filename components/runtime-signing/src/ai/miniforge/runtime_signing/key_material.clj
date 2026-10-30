;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.key-material
  "Helpers for the runtime signing key: generation, parsing, and the
   ownership and permissions that keep it owner-only."
  (:require
   [ai.miniforge.runtime-signing.hmac :as hmac]
   [clojure.string :as str])
  (:import
   [java.nio.file Files LinkOption Path]
   [java.nio.file.attribute FileAttribute PosixFilePermission PosixFilePermissions]
   [java.security SecureRandom]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} key-length
  "Bytes of key material: 256 bits."
  32)

(def ^{:stratum 0} max-key-file-bytes
  "A key file is 64 hex characters; anything past this bound is malformed."
  256)

(def ^{:stratum 0} no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^{:stratum 0} exposing-permissions
  "Any of these on the key file lets another user read or replace it."
  #{PosixFilePermission/GROUP_READ PosixFilePermission/GROUP_WRITE
    PosixFilePermission/GROUP_EXECUTE PosixFilePermission/OTHERS_READ
    PosixFilePermission/OTHERS_WRITE PosixFilePermission/OTHERS_EXECUTE})

(def ^{:stratum 0} writable-by-others
  "Any of these on the key directory lets another user swap the key file."
  #{PosixFilePermission/GROUP_WRITE PosixFilePermission/OTHERS_WRITE})

(def ^{:stratum 0} process-owner
  "The owner this process creates files as: the owner of a temporary file
   it creates once. Comparing owners, not the `user.name` property, holds
   when the UID has no passwd entry (a container run as a bare UID) or
   `user.name` is overridden."
  (delay
    (let [probe (Files/createTempFile "miniforge-owner-" ".probe" (into-array FileAttribute []))]
      (try
        (Files/getOwner probe (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
        (finally (Files/deleteIfExists probe))))))

(defn ^{:stratum 0} posix-file-system? [^Path path]
  (contains? (.supportedFileAttributeViews (.getFileSystem path)) "posix"))

(defn ^{:stratum 0} owner-only
  "Creation attributes for `posix-mode`; none where POSIX modes do not exist."
  [posix? posix-mode]
  (into-array FileAttribute
              (if posix?
                [(PosixFilePermissions/asFileAttribute
                  (PosixFilePermissions/fromString posix-mode))]
                [])))

(defn ^{:stratum 0} parse-key
  "Key bytes from 64 lowercase hex characters, or nil."
  [text]
  (let [trimmed (str/trim text)]
    (when (re-matches #"[0-9a-f]{64}" trimmed)
      (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                       (partition 2 trimmed))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} owned-by-process-user?
  "True when this process's owner owns `path` itself (links not followed)."
  [^Path path]
  (= @process-owner
     (Files/getOwner path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))

(defn ^{:stratum 1} fresh-key-text
  "New random key material as hex."
  []
  (let [key-bytes (byte-array key-length)]
    (.nextBytes (SecureRandom.) key-bytes)
    (hmac/hex key-bytes)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} exposed?
  "True when another user owns the file at `path` or holds any permission
   on it."
  [posix? ^Path path]
  (and posix?
       (or (not (owned-by-process-user? path))
           (boolean (some exposing-permissions
                          (Files/getPosixFilePermissions path no-follow))))))

(defn ^{:stratum 2} safe-directory?
  "True when `dir` is a real directory that only the process user can
   write. POSIX ownership is not checked where POSIX modes do not exist."
  [posix? ^Path dir]
  (and (not (Files/isSymbolicLink dir))
       (or (not posix?)
           (and (owned-by-process-user? dir)
                (not-any? writable-by-others
                          (Files/getPosixFilePermissions dir no-follow))))))

(comment
  (parse-key (fresh-key-text)))
