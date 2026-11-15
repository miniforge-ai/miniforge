;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-storage
  "Reserved run directories and monotonic state: admission creates; full stop replaces."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.run-schema :as schema]
            [ai.miniforge.execution-grant.store-read :as reader]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file FileAlreadyExistsException Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} records-directory
  "Separate run authority from immutable individual grant records."
  "runs")

(def ^{:stratum 0 :private true} record-name
  "One authoritative state file per UUID; absence never means permission."
  "authority.edn")

(def ^{:stratum 0 :private true} private-directory-permissions
  "A reserved run identity remains visible if its state publication fails."
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                             (PosixFilePermissions/fromString "rwx------"))]))

(defn ^{:stratum 0} failure [id type message-key]
  (anomaly/anomaly type (msg/t message-key) {:workflow-run/id id}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-file ^File [directory id]
  (io/file directory (str id) record-name))

(defn ^{:stratum 1} directory [root create? id]
  (let [file (io/file root records-directory)
        operation (if create? durability/prepare-protected-directory! durability/resolve-protected-directory)
        directory (operation file)
        profile (when-not (anomaly/anomaly? directory)
                  (durability/filesystem-profile (.toFile directory)))]
    (cond
      (anomaly/anomaly? directory) directory
      (anomaly/anomaly? profile) profile
      (= :posix profile) (.toFile ^java.nio.file.Path directory)
      :else (failure id :unavailable :run/protection-unqualified))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-record [directory id]
  (let [target (record-file directory id)
        protected (durability/resolve-protected-directory (.getParentFile target))]
    (if (anomaly/anomaly? protected) protected
        (reader/read-record target :workflow-run/id id schema/Record
                            #(failure id :fault :run/read-failed)))))

(defn ^{:stratum 2} reserve-with-exception-handling! [directory id]
  ;; Only Java filesystem failures cross this boundary; the component has no Slingshot dependency.
  (try
    (Files/createDirectory (.toPath (.getParentFile (record-file directory id))) private-directory-permissions)
    true
    (catch FileAlreadyExistsException _ false)
    (catch Exception _ (failure id :fault :run/write-failed))))

(defn ^{:stratum 2} confirm! [directory record]
  (let [confirmed (durability/confirm! (record-file directory (:workflow-run/id record)))]
    (if (anomaly/anomaly? confirmed) confirmed record)))
