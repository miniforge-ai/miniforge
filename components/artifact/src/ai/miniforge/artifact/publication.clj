;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication
  "Synchronous immutable artifact publication, separate from mutable store caches."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.publication-boundary :as boundary :refer [failure]]
            [ai.miniforge.artifact.publication-record :as record]
            [ai.miniforge.artifact.publication-identity :as identity]
            [ai.miniforge.artifact.publication-files :as files]
            [ai.miniforge.schema.interface :as schema]
            [clojure.java.io :as io])
  (:import [java.nio.file FileAlreadyExistsException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} decoded [file id]
  (boundary/call-with-exception-handling id :fault :publication/read-failed
    #(let [value (some-> (files/read-bytes file) record/decode)]
       (if (and (schema/valid-artifact? value) (= id (:artifact/id value)))
         value
         (failure :fault :publication/read-failed id)))))

(defn- ^{:stratum 0} link-bytes! [file temporary]
  (try (files/publish-link! file temporary)
       (catch FileAlreadyExistsException _ nil)))

(defn- ^{:stratum 0} confirm-durability! [file artifact]
  (let [confirmed (files/confirm! file)]
    (if (anomaly/anomaly? confirmed)
      (failure :unavailable :publication/unconfirmed (:artifact/id artifact))
      artifact)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-bytes! [id file temporary bytes]
  (let [written (files/write! temporary bytes)]
    (if (anomaly/anomaly? written)
      (failure :unavailable :publication/unconfirmed id)
      (link-bytes! file temporary))))

(defn ^{:stratum 1} read-record [directory id]
  (let [file (files/target directory id)]
    (cond
      (not (boundary/safe-path-with-exception-handling files/safe-directory? directory))
      (failure :invalid-input :publication/unsafe-path id)
      (files/absent? file) nil
      (not (files/regular? file)) (failure :fault :publication/read-failed id)
      :else (decoded file id))))

(defn- ^{:stratum 1} confirm-record! [directory artifact]
  (let [id (:artifact/id artifact)
        file (files/target directory id)
        actual (when (files/regular? file) (decoded file id))]
    (cond
      (anomaly/anomaly? actual) actual
      (nil? actual) (failure :fault :publication/read-failed id)
      (identity/same-content? artifact actual) (confirm-durability! file artifact)
      :else (failure :conflict :publication/id-conflict id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [directory artifact]
  (let [id (:artifact/id artifact)
        bytes (boundary/call-with-exception-handling id :invalid-input :publication/not-portable
                                                    #(record/encode artifact))]
    (cond
      (anomaly/anomaly? bytes) bytes
      (nil? bytes) (failure :invalid-input :publication/not-portable id)
      (not (boundary/safe-path-with-exception-handling files/safe-directory? directory))
      (failure :invalid-input :publication/unsafe-path id)
      :else
      (let [temporary (files/temporary (io/file directory))]
        (boundary/publish-with-cleanup id
          (partial publish-bytes! id (files/target directory id) temporary bytes)
          (partial confirm-record! directory artifact)
          (partial files/delete-temporary! temporary))))))

(comment
  (read-record "/tmp" (random-uuid)))
