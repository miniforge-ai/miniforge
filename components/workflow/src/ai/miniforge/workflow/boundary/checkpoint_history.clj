;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-history
  "Append audit records without following links; create private POSIX files."
  (:require [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.messages :as messages]
            [babashka.fs :as fs])
  (:import [java.io IOException]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file LinkOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private append-options
  #{StandardOpenOption/CREATE StandardOpenOption/WRITE
    StandardOpenOption/APPEND LinkOption/NOFOLLOW_LINKS})

(defn- ^{:stratum 0} require-regular-history! [path]
  (when (and (fs/exists? path {:nofollow-links true})
             (not (fs/regular-file? path {:nofollow-links true})))
    (throw (IOException. (messages/system :checkpoint/unsafe-history {:path (str path)})))))

(defn- ^{:stratum 0} creation-attributes [path]
  (into-array FileAttribute
              (when (permissions/posix? (fs/parent path))
                [(PosixFilePermissions/asFileAttribute
                  (PosixFilePermissions/fromString permissions/private-permissions))])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} append! [path text]
  (let [path (fs/absolutize path)
        buffer (ByteBuffer/wrap (.getBytes ^String text StandardCharsets/UTF_8))]
    (require-regular-history! path)
    (with-open [channel (FileChannel/open (fs/path path) append-options (creation-attributes path))]
      (while (.hasRemaining buffer) (.write channel buffer)))))

(comment ::private-append-only-checkpoint-history)
