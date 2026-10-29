;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-directory
  "Resolve protected ancestry before creating private checkpoint directories."
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory-policy :as policy]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.boundary.checkpoint-path-policy :as path-policy]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]])
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

(defn- ^{:stratum 0} create-private! [directory [segment leaf?]]
  (let [path (fs/path directory segment)]
    (try+
      (fs/create-dir path {:posix-file-permissions policy/private-permissions})
      (catch FileAlreadyExistsException _ nil))
    (policy/check! path leaf?)))

(defn- ^{:stratum 0} creation-steps [segments]
  (let [final-index (dec (count segments))]
    (map-indexed #(vector %2 (= %1 final-index)) segments)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} existing! [directory]
  (when (permissions/posix? directory) (path-policy/check! directory))
  (let [path (fs/real-path directory)]
    (when (permissions/posix? path) (check-ancestry! path true))
    path))

(defn ^{:stratum 1} prepare! [directory]
  (let [[ancestor segments] (existing-ancestor directory)]
    (if (permissions/posix? ancestor)
      (do
        (path-policy/check! directory)
        (fs/real-path
         (reduce create-private! (check-ancestry! (fs/real-path ancestor) (empty? segments))
                  (creation-steps segments))))
      (fs/real-path (fs/create-dirs directory)))))

(comment ::checkpoint-directory-creation)
