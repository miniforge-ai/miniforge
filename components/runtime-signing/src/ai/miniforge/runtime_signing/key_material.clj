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

(def ^{:stratum 0} known-owner
  "The owner this process creates files as, once a probe has found it."
  (atom nil))

(defn ^{:stratum 0} owned-by?
  "True when `owner` owns `path` itself (links not followed)."
  [owner ^Path path]
  (= owner (Files/getOwner path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))

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

(defn ^{:stratum 1} process-owner
  "The owner this process creates files as: the owner of a temporary file
   it creates in `dir`. Comparing owners, not the `user.name` property,
   holds when the UID has no passwd entry (a container run as a bare UID)
   or `user.name` is overridden. The probe goes in the key directory,
   which key creation writes anyway, so no other directory has to be
   writable; a key directory this process cannot write fails closed. A
   found owner is remembered; a failed probe is not."
  [^Path dir]
  (or @known-owner
      (let [probe (Files/createTempFile dir ".owner-" ".probe" (into-array FileAttribute []))]
        (try
          (reset! known-owner (Files/getOwner probe (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
          (finally (Files/deleteIfExists probe))))))

(defn ^{:stratum 1} fresh-key-text
  "New random key material as hex."
  []
  (let [key-bytes (byte-array key-length)]
    (.nextBytes (SecureRandom.) key-bytes)
    (hmac/hex key-bytes)))

(defn ^{:stratum 1} exposed?
  "True when someone other than `owner` owns the file at `path`, or
   another user holds any permission on it."
  [posix? owner ^Path path]
  (and posix?
       (or (not (owned-by? owner path))
           (boolean (some exposing-permissions
                          (Files/getPosixFilePermissions path no-follow))))))

(defn ^{:stratum 1} safe-directory?
  "True when `dir` is a real directory that only `owner` can write.
   POSIX ownership is not checked where POSIX modes do not exist."
  [posix? owner ^Path dir]
  (and (not (Files/isSymbolicLink dir))
       (or (not posix?)
           (and (owned-by? owner dir)
                (not-any? writable-by-others
                          (Files/getPosixFilePermissions dir no-follow))))))

(comment
  (parse-key (fresh-key-text)))
