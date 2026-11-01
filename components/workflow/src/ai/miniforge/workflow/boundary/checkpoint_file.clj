;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-file
  "Own one same-directory temporary file through atomic publication.

   Durable: the temporary's bytes reach the disk before the rename, and the
   directory entries from the target's directory up are forced after it, so
   a file this returns survives a crash, and a crash before the rename
   leaves the previous file."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.messages :as messages]
            [babashka.fs :as fs])
  (:import [java.io IOException]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private temporary-prefix ".checkpoint-")

(def ^{:stratum 0} ^:private temporary-suffix ".tmp")

(def ^{:stratum 0} ^:private atomic-replacement
  (into-array java.nio.file.CopyOption
              [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))

(defn- ^{:stratum 0} remove-temporary! [temporary]
  (Files/deleteIfExists (fs/path temporary)))

(defn ^{:stratum 0} fill!
  "Write `text` into the fresh temporary file `temporary` and force it to
   disk; throws an IOException when it did not get there."
  [temporary ^String text]
  (let [result (durability/write-temporary-bytes! (fs/file temporary)
                                                  (.getBytes text StandardCharsets/UTF_8))]
    (when (anomaly/anomaly? result)
      (throw (IOException. ^String (messages/t :checkpoint/not-durable
                                               {:path (str temporary) :error (:anomaly/message result)}))))))

(defn ^{:stratum 0} sync-directory!
  "Force the directory entries of `directory` and its ancestors to disk;
   throws an IOException when that fails. Nothing on Windows, which cannot
   open a directory to force it."
  [directory]
  (let [result (when-not (fs/windows?) (durability/sync-ancestry! (fs/file directory)))]
    (when (anomaly/anomaly? result)
      (throw (IOException. ^String (messages/t :checkpoint/not-durable
                                               {:path (str directory) :error (:anomaly/message result)}))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} create-temporary! [target prepare-directory! prefix]
  (let [directory (prepare-directory! (fs/parent (fs/absolutize target)))]
    (fs/create-temp-file
     (cond-> {:dir directory
              :prefix prefix
              :suffix temporary-suffix}
       (permissions/posix? directory)
       (assoc :posix-file-permissions permissions/private-permissions)))))

(defn- ^{:stratum 1} publish! [temporary target data]
  (fill! temporary (pr-str data))
  (permissions/prepare! temporary target)
  (Files/move (fs/path temporary) (fs/path target) atomic-replacement)
  (sync-directory! (fs/parent target)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write!
  ([target data] (write! target data {}))
  ([target data {:keys [prepare-directory! prefix]
                :or {prepare-directory! directory/prepare!
                     prefix temporary-prefix}}]
   (let [temporary (create-temporary! target prepare-directory! prefix)
         published (fs/path (fs/parent temporary) (fs/file-name target))]
     (cleanup/finish! (partial publish! temporary published data)
                      (partial remove-temporary! temporary))
     (str published))))

(comment ::temporary-file-ownership)
