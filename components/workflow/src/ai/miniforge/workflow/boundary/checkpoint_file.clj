;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-file
  "Own one same-directory temporary file through atomic publication."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.boundary.checkpoint-publication :as publication]
            [babashka.fs :as fs])
  (:import [java.nio.file Files]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private temporary-prefix ".checkpoint-")

(def ^{:stratum 0} ^:private temporary-suffix ".tmp")

(defn- ^{:stratum 0} remove-temporary! [temporary]
  (Files/deleteIfExists (fs/path temporary)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} create-temporary! [target]
  (let [directory (fs/parent (fs/absolutize target))]
    (fs/create-dirs directory)
    (fs/create-temp-file {:dir directory :prefix temporary-prefix :suffix temporary-suffix})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write! [target data]
  (let [temporary (create-temporary! target)]
    (cleanup/finish! (partial publication/publish! temporary target data)
                     (partial remove-temporary! temporary))
    target))

(comment ::temporary-file-ownership)
