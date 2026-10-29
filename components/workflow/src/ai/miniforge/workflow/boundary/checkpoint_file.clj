;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-file
  "Own one same-directory temporary file through atomic publication."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
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
  (let [directory (fs/parent (fs/absolutize target))]
    (fs/create-dirs directory)
    (fs/create-temp-file {:dir directory :prefix temporary-prefix :suffix temporary-suffix})))

(defn- ^{:stratum 1} publish! [temporary target data]
  (spit (fs/file temporary) (pr-str data))
  (Files/move (fs/path temporary) (fs/path target) atomic-replacement))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write! [target data]
  (let [temporary (create-temporary! target)]
    (cleanup/finish! (partial publish! temporary target data)
                     (partial remove-temporary! temporary))
    target))

(comment ::temporary-file-ownership)
