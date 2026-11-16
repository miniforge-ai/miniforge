;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-publication
  "Atomic create-only admission and stop-only replacement of run authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.run-storage :as storage]
            [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.file-durability.interface :as durability])
  (:import [java.io File]
           [java.nio.file CopyOption FileAlreadyExistsException Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} private-file-permissions
  "Authority records are owner-only even inside existing read-shared directories."
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                             (PosixFilePermissions/fromString "rw-------"))]))

(def ^{:stratum 0 :private true} temporary-suffix
  "Unpublished sibling files are never interpreted as authoritative state."
  ".tmp")

(defn- ^{:stratum 0} encode [record]
  (-> record codec/->wire codec/wire-text codec/strict-utf8))

(defn- ^{:stratum 0} publish! [^File temporary ^File target replace?]
  (if replace?
    (Files/move (.toPath temporary) (.toPath target)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
    (Files/createLink (.toPath target) (.toPath temporary)))
  target)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} write! [directory record replace?]
  (let [target (storage/record-file directory (:workflow-run/id record))
        temporary (.toFile (Files/createTempFile (.toPath (.getParentFile target))
                                                (.getName target) temporary-suffix private-file-permissions))]
    (try
      (let [encoded (encode record)
            result (durability/publish-bytes! temporary encoded #(publish! temporary target replace?))]
        (if (anomaly/anomaly? result) result record))
      (finally (.delete temporary)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write-with-exception-handling! [directory record replace?]
  ;; A Java-only publication boundary; no direct Slingshot dependency is needed.
  (try
    (write! directory record replace?)
    (catch FileAlreadyExistsException _ (storage/failure (:workflow-run/id record) :conflict :run/id-conflict))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      (storage/failure (:workflow-run/id record) :fault :run/write-failed))
    (catch Exception _ (storage/failure (:workflow-run/id record) :fault :run/write-failed))))
