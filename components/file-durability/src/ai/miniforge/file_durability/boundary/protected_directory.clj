;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.protected-directory
  "Resolve protected ancestry before creating private storage directories."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-policy :as policy]
            [ai.miniforge.file-durability.boundary.directory-path :as path-policy]
            [babashka.fs :as fs])
  (:import [java.nio.file FileAlreadyExistsException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} existing-ancestor [directory]
  (loop [path (fs/absolutize directory) segments ()]
    (if (fs/exists? path)
      [path segments]
      (recur (fs/parent path) (cons (fs/file-name path) segments)))))

(defn- ^{:stratum 0} check-ancestry! [directory leaf?]
  (doseq [path (reverse (take-while some? (iterate fs/parent directory)))]
    (policy/check! path (and leaf? (= path directory))))
  directory)

(defn- ^{:stratum 0} creation-steps [segments]
  (let [final-index (dec (count segments))]
    (map-indexed #(vector %2 (= %1 final-index)) segments)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} create-private! [directory [segment leaf?]]
  (let [path (fs/path directory segment)]
    (try
      (fs/create-dir path {:posix-file-permissions policy/private-permissions})
      (catch FileAlreadyExistsException _ nil))
    (path-policy/check! path leaf?)
    (check-ancestry! (fs/real-path path) leaf?)))

(defn ^{:stratum 1} existing! [directory]
  (when (= :posix (policy/filesystem-profile directory)) (path-policy/check! directory))
  (let [path (fs/real-path directory)]
    (when-not (fs/directory? path) (boundary/refuse-directory! path))
    (when (= :posix (policy/filesystem-profile path)) (check-ancestry! path true))
    path))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [directory]
  (let [[ancestor segments] (existing-ancestor directory)]
    (if (= :posix (policy/filesystem-profile ancestor))
      (do
        (path-policy/check! directory)
        (fs/real-path
         (reduce create-private! (check-ancestry! (fs/real-path ancestor) (empty? segments))
                  (creation-steps segments))))
      (fs/real-path (fs/create-dirs directory)))))

(comment ::protected-directory-creation)
