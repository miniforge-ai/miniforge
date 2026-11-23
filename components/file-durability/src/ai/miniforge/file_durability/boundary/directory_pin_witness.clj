;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.directory-pin-witness
  "Create the witness before opening a descriptor, including snapshotting providers."
  (:require [ai.miniforge.file-durability.boundary.owned-resource :as owned]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} prefix
  "A fresh native name cannot be confused with a retained authority record."
  "miniforge-directory-pin-")

(def ^{:stratum 0 :private true} permissions
  "Private probes must not inherit umask-based sharing as an initial mode."
  (PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rw-------")))

(defn- ^{:stratum 0} remove! [^Path witness] (Files/deleteIfExists witness))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acquire! [witness acquire]
  (try+
    (acquire)
    (catch Object failure
      (remove! witness)
      (throw+ failure))))

(defn- ^{:stratum 1} verify-and-clean! [^Path witness verify stream]
  (try+
    (verify (.getFileName witness) stream)
    (finally (remove! witness))))

(defn- ^{:stratum 1} create! [^Path root]
  (Files/createTempFile root prefix "" (into-array FileAttribute [permissions])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-stream! [root acquire verify]
  (let [witness (create! root)
        stream (acquire! witness acquire)]
    (owned/call! stream (partial verify-and-clean! witness verify))))

(comment
  (let [root (Files/createTempDirectory "miniforge-pin-repl-" (make-array FileAttribute 0))]
    (try
      (let [witness (create! root)]
        (try
          (PosixFilePermissions/toString
           (Files/getPosixFilePermissions witness (make-array java.nio.file.LinkOption 0)))
          (finally (Files/delete witness))))
      (finally (Files/delete root)))))
