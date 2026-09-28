;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-io
  "Create-only publication and validated reads at the local authority boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.execution-grant.store-durability :as durability]
            [ai.miniforge.execution-grant.store-path :as path]
            [ai.miniforge.execution-grant.store-read :as reader]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file FileAlreadyExistsException Files]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} suffixes
  {:grant ".grant.edn" :revocation ".revocation.edn"})

(def ^{:stratum 0} records-directory "grants")

(defn- ^{:stratum 0} failure
  [type message-key id kind]
  (anomaly/anomaly type (msg/t message-key) {:grant/id id :record/kind kind}))

(defn- ^{:stratum 0} temp-file
  ^File [^File target]
  (io/file (.getParentFile target) (str (.getName target) "." (random-uuid) ".tmp")))

(defn- ^{:stratum 0} write-record!
  [^File target ^File tmp encoded record]
  (io/make-parents target)
  (durability/write! tmp encoded)
  (Files/createLink (.toPath target) (.toPath tmp))
  (durability/sync-ancestry! (.getParentFile target))
  record)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} record-file
  ^File [dir id kind]
  (io/file dir records-directory (str id (get suffixes kind))))

(defn- ^{:stratum 1} encode-record
  [record kind]
  (try
    (let [wire (codec/->wire record)
          encoded (pr-str wire)]
      (if (= wire (edn/read-string encoded))
        encoded
        (failure :invalid-input :store/not-portable (:grant/id record) kind)))
    (catch Exception _
      (failure :invalid-input :store/not-portable (:grant/id record) kind))))

(defn- ^{:stratum 1} publish!
  [^File target encoded record kind]
  (let [^File tmp (temp-file target)
        id (:grant/id record)]
    (try
      (if (path/safe? target)
        (write-record! target tmp encoded record)
        (failure :fault :store/write-failed id kind))
      (catch FileAlreadyExistsException _
        (failure :conflict :store/id-conflict id kind))
      (catch Exception _
        (failure :fault :store/write-failed id kind))
      (finally (.delete tmp)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-record
  [dir id kind record-schema]
  (reader/read-record (record-file dir id kind) id record-schema
                      (partial failure :fault :store/read-failed id kind)))

(defn ^{:stratum 2} confirm!
  [dir record kind]
  (try
    (let [file (record-file dir (:grant/id record) kind)]
      (if (path/safe? file)
        (do (durability/confirm! file) record)
        (failure :fault :store/write-failed (:grant/id record) kind)))
    (catch Exception _
      (failure :fault :store/write-failed (:grant/id record) kind))))

(defn ^{:stratum 2} create!
  [dir record kind]
  (let [encoded (encode-record record kind)]
    (if (anomaly/anomaly? encoded)
      encoded
      (publish! (record-file dir (:grant/id record) kind) encoded record kind))))

(comment
  (encode-record {:grant/id (random-uuid)} :grant))
