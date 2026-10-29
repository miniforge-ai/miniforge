;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-file
  "Own one same-directory temporary file through atomic publication."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [babashka.fs :as fs])
  (:import [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private temporary-prefix ".checkpoint-")

(def ^{:stratum 0} ^:private temporary-suffix ".tmp")

(def ^{:stratum 0} ^:private atomic-replacement
  (into-array java.nio.file.CopyOption
              [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))

(defn- ^{:stratum 0} remove-temporary! [temporary]
  (Files/deleteIfExists (fs/path temporary)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} create-temporary! [target]
  (let [directory (directory/prepare! (fs/parent (fs/absolutize target)))]
    (fs/create-temp-file
     (cond-> {:dir directory :prefix temporary-prefix :suffix temporary-suffix}
       (permissions/posix? directory)
       (assoc :posix-file-permissions permissions/private-permissions)))))

(defn- ^{:stratum 1} publish! [temporary target data]
  (spit (fs/file temporary) (pr-str data))
  (permissions/prepare! temporary target)
  (Files/move (fs/path temporary) (fs/path target) atomic-replacement))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write! [target data]
  (let [temporary (create-temporary! target)
        published (fs/path (fs/parent temporary) (fs/file-name target))]
    (cleanup/finish! (partial publish! temporary published data)
                     (partial remove-temporary! temporary))
    target))

(comment ::temporary-file-ownership)
