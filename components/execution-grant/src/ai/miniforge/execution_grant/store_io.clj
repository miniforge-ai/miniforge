;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-io
  "Create-only publication and validated reads at the local authority boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.execution-grant.store-path :as path]
            [ai.miniforge.execution-grant.store-publication :as publication]
            [ai.miniforge.execution-grant.store-read :as reader]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} suffixes
  {:grant ".grant.edn" :revocation ".revocation.edn"})

(def ^{:stratum 0} records-directory "grants")

(defn- ^{:stratum 0} failure
  [type message-key id kind]
  (anomaly/anomaly type (msg/t message-key) {:grant/id id :record/kind kind}))

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
        (publication/confirm! file record (partial failure :fault :store/write-failed (:grant/id record) kind))
        (failure :fault :store/write-failed (:grant/id record) kind)))
    (catch Exception _
      (failure :fault :store/write-failed (:grant/id record) kind))))

(defn ^{:stratum 2} create!
  [dir record kind]
  (let [encoded (encode-record record kind)
        id (:grant/id record)]
    (if (anomaly/anomaly? encoded)
      encoded
      (publication/publish-with-exception-handling!
       (record-file dir id kind) encoded record
       (partial failure :fault :store/write-failed id kind)
       (partial failure :conflict :store/id-conflict id kind)))))

(comment
  (encode-record {:grant/id (random-uuid)} :grant))
