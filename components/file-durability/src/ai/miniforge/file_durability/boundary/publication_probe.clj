;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.publication-probe
  "Check publication primitives on disposable files, not the authority records."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.atomic-publication :as atomic]
            [ai.miniforge.file-durability.io :as file-io]
            [ai.miniforge.file-durability.boundary.directory-policy :as directory-policy]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileAlreadyExistsException Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} probe-prefix
  "A private workspace prevents interference with records or other probes."
  "miniforge-publication-probe-")

(def ^{:stratum 0 :private true} destination-name
  "Both publication modes must operate on the same destination entry."
  "destination")

(def ^{:stratum 0 :private true} candidate-name
  "A candidate stays on the same filesystem as the destination."
  "candidate")

(defn- ^{:stratum 0} probe-value []
  (str (UUID/randomUUID)))

(defn- ^{:stratum 0} bytes-match? [^File file ^String expected]
  (Arrays/equals (Files/readAllBytes (.toPath file)) (.getBytes expected StandardCharsets/UTF_8)))

(defn- ^{:stratum 0} refuses-relink? [candidate destination]
  (try+
    (atomic/link! candidate destination)
    false
    (catch FileAlreadyExistsException _ true)))

(defn- ^{:stratum 0} replace-candidate! [^File candidate destination ^String replacement]
  ;; A different inode is required to demonstrate replacement.
  (Files/delete (.toPath candidate))
  (Files/createFile (.toPath candidate) (make-array FileAttribute 0))
  (file-io/publish-bytes! candidate (.getBytes replacement StandardCharsets/UTF_8)
                          (partial atomic/replace! candidate destination))
  (Files/notExists (.toPath candidate) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} create-and-replace! [candidate destination]
  (let [initial (probe-value)
        replacement (probe-value)]
    (file-io/write-new-text! candidate initial)
    (atomic/link! candidate destination)
    (file-io/confirm! destination)
    (and (bytes-match? destination initial)
         (refuses-relink? candidate destination)
         (replace-candidate! candidate destination replacement)
         (bytes-match? destination replacement))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check! [^Path directory]
  (when-not (= :posix (directory-policy/filesystem-profile directory))
    (boundary/refuse-directory! directory))
  (let [work (fs/create-temp-dir {:dir directory :prefix probe-prefix
                                  :posix-file-permissions directory-policy/private-permissions})
        candidate (.toFile (fs/path work candidate-name))
        destination (.toFile (fs/path work destination-name))]
    (try+
      (if (create-and-replace! candidate destination)
        :publication-verified
        (boundary/unverified-publication directory))
      (finally
        (Files/deleteIfExists (.toPath candidate))
        (Files/deleteIfExists (.toPath destination))
        (Files/delete work)))))
