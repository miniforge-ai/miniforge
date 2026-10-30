;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.key-file
  "Create-only runtime signing key file, readable only by its owner.

   The key is written to a temporary file that NIO creates with owner-only
   permissions, then hard-linked into place, so an existing key is never
   replaced. `java.io.File` creation applies the process umask, which can
   leave a new file readable by other users.

   Only POSIX owner and mode bits are checked. An ACL that grants another
   user access to the key directory or file is not detected."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.file-durability.interface :as durability]
   [ai.miniforge.runtime-signing.key-material :as material]
   [ai.miniforge.runtime-signing.messages :as msg])
  (:import
   [java.io File]
   [java.nio.charset StandardCharsets]
   [java.nio ByteBuffer]
   [java.nio.file FileAlreadyExistsException Files LinkOption OpenOption Path
    StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failure [message-key]
  (anomaly/anomaly :unavailable (msg/t message-key) {}))

(defn- ^{:stratum 0} link-into-place!
  "Link `tmp` to `path` unless a key is already there, then force the
   directory entries."
  [^Path path ^Path tmp]
  (try (Files/createLink path tmp)
       (catch FileAlreadyExistsException _ nil))
  (durability/sync-ancestry! (.toFile (.getParent path))))

(defn- ^{:stratum 0} read-no-follow
  "The file's whole text, opened without following a final symbolic link,
   or nil when it exceeds `material/max-key-file-bytes`."
  [^Path path]
  (with-open [channel (Files/newByteChannel
                       path (into-array OpenOption [StandardOpenOption/READ
                                                    LinkOption/NOFOLLOW_LINKS]))]
    (let [buffer (ByteBuffer/allocate (inc material/max-key-file-bytes))]
      (loop []
        (when (and (.hasRemaining buffer) (pos? (.read channel buffer)))
          (recur)))
      (when (.hasRemaining buffer)
        (String. (.array buffer) 0 (.position buffer) StandardCharsets/UTF_8)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish!
  "Write a fresh key beside `path` and link it into place. When another
   process linked its key first, that key stands."
  [posix? ^Path path]
  (let [dir (.getParent path)
        _ (Files/createDirectories dir (material/owner-only posix? "rwx------"))
        tmp (Files/createTempFile dir ".runtime-signing-" ".tmp"
                                  (material/owner-only posix? "rw-------"))]
    (try
      (let [written (durability/write-temporary-bytes!
                     (.toFile tmp) (.getBytes ^String (material/fresh-key-text)
                                              StandardCharsets/UTF_8))]
        (if (anomaly/anomaly? written) written (link-into-place! path tmp)))
      (finally (Files/deleteIfExists tmp)))))

(defn- ^{:stratum 1} read-key [posix? ^Path path]
  (let [dir (.getParent path)
        owner (when posix? (material/process-owner dir))]
    (cond
      (not (material/safe-directory? posix? owner dir)) (failure :key/unsafe-directory)
      (Files/isSymbolicLink path) (failure :key/linked)
      ;; A FIFO or device would pass the other checks and block the read.
      (not (Files/isRegularFile path material/no-follow)) (failure :key/not-regular)
      (material/exposed? posix? owner path) (failure :key/exposed)
      :else (or (some-> (read-no-follow path) material/parse-key)
                (failure :key/malformed)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-or-create!
  "The key bytes in `file`, creating the file when absent, or an anomaly
   when the file is a link, readable by others, or malformed."
  [^File file]
  (try
    (let [path (.toAbsolutePath (.toPath file))
          posix? (material/posix-file-system? path)
          published (when-not (Files/exists path material/no-follow)
                      (publish! posix? path))]
      (if (anomaly/anomaly? published)
        published
        (read-key posix? path)))
    (catch Exception _ (failure :key/unreadable))))

(comment
  (load-or-create! (File. "/tmp/runtime-signing.key")))
