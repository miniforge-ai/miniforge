;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.publication-support
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} candidate "candidate")

(def ^{:stratum 0} previous "previous")

(def ^{:stratum 0} readonly "r--------")

(defn ^{:stratum 0} with-root [f]
  (let [root (.toFile (Files/createTempDirectory "mf-durability-publication-" (make-array FileAttribute 0)))]
    (try (f root)
         (finally (doseq [file (reverse (file-seq root))] (Files/deleteIfExists (.toPath file)))))))

(defn ^{:stratum 0} posix? [file]
  (try
    (Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0))
    true
    (catch UnsupportedOperationException _ false)))

(defn ^{:stratum 0} mode [file]
  (PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0))))

(defn ^{:stratum 0} move! [temporary target]
  (Files/move (.toPath temporary) (.toPath target)
              (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
  target)

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} candidate-bytes (.getBytes candidate StandardCharsets/UTF_8))

(defn ^{:stratum 1} files [root]
  (let [temporary (.toFile (Files/createTempFile (.toPath root) "candidate-" ".tmp" (make-array FileAttribute 0)))
        target (java.io.File. root "published.edn")]
    (spit target previous)
    {:temporary temporary
     :target target}))

(defn ^{:stratum 1} make-readonly! [file]
  (Files/setPosixFilePermissions (.toPath file) (PosixFilePermissions/fromString readonly)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-readonly! [temporary target]
  (make-readonly! temporary)
  (move! temporary target))

(comment ::owned-publication-fixtures)
