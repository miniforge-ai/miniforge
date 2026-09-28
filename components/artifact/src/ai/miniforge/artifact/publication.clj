;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication
  "Synchronous immutable artifact publication, separate from mutable store caches."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.messages :as msg]
            [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.artifact.publication-files :as files]
            [ai.miniforge.schema.interface :as schema]
            [clojure.java.io :as io])
  (:import [java.nio.file FileAlreadyExistsException Files]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [type key id]
  (anomaly/anomaly type (msg/t key) {:artifact/id id}))

(defn- ^{:stratum 0} decoded [file]
  (some-> (files/read-bytes file) codec/decode))

(defn- ^{:stratum 0} publish-bytes! [file temporary bytes]
  (files/write! temporary bytes)
  (try (files/publish-link! file temporary)
       (catch FileAlreadyExistsException _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-record [directory id]
  (let [file (files/target directory id)]
    (cond
      (not (files/safe-directory? directory)) (failure :invalid-input :publication/unsafe-path id)
      (files/absent? file) nil
      (not (files/regular? file)) (failure :fault :publication/read-failed id)
      :else (let [record (decoded file)]
              (if (and (schema/valid-artifact? record) (= id (:artifact/id record)))
                record
                (failure :fault :publication/read-failed id))))))

(defn- ^{:stratum 1} confirm-record! [directory artifact]
  (let [id (:artifact/id artifact)
        file (files/target directory id)]
    (if (and (files/regular? file) (= artifact (decoded file)))
      (do (files/confirm! file) artifact)
      (failure :conflict :publication/id-conflict id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [directory artifact]
  (let [id (:artifact/id artifact)
        bytes (codec/encode artifact)]
    (cond
      (nil? bytes) (failure :invalid-input :publication/not-portable id)
      (not (files/safe-directory? directory)) (failure :invalid-input :publication/unsafe-path id)
      :else
      (let [temporary (files/temporary (io/file directory))]
        (try
          (publish-bytes! (files/target directory id) temporary bytes)
          (confirm-record! directory artifact)
          (finally (Files/deleteIfExists (.toPath temporary))))))))

(comment
  (read-record "/tmp" (random-uuid)))
