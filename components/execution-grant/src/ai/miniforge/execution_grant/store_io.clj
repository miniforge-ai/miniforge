;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-io
  "Create-only publication and validated reads at the local authority boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.store-codec :as codec]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m])
  (:import [java.io File PushbackReader StringReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileAlreadyExistsException Files NoSuchFileException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} suffixes
  {:grant ".grant.edn" :revocation ".revocation.edn"})

(defn- ^{:stratum 0} failure
  [type message-key id kind]
  (anomaly/anomaly type (msg/t message-key) {:grant/id id :record/kind kind}))

(defn- ^{:stratum 0} temp-file
  ^File [^File target]
  (io/file (.getParentFile target) (str (.getName target) "." (random-uuid) ".tmp")))

(defn- ^{:stratum 0} decode-record
  [encoded]
  (with-open [reader (PushbackReader. (StringReader. encoded))]
    (let [value (edn/read reader)
          eof (Object.)
          tail (edn/read {:eof eof} reader)]
      (when (identical? eof tail)
        (codec/<-wire value)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} record-file
  ^File [dir id kind]
  (io/file dir (str id (get suffixes kind))))

(defn- ^{:stratum 1} read-file
  [^File file id kind record-schema]
  (try
    (let [encoded (Files/readString (.toPath file) StandardCharsets/UTF_8)
          value (decode-record encoded)]
      (if (and (m/validate record-schema value) (= id (:grant/id value)))
        value
        (failure :fault :store/read-failed id kind)))
    (catch NoSuchFileException _ nil)
    (catch Exception _
      (failure :fault :store/read-failed id kind))))

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
      (io/make-parents target)
      (spit tmp encoded :encoding "UTF-8")
      (Files/createLink (.toPath target) (.toPath tmp))
      record
      (catch FileAlreadyExistsException _
        (failure :conflict :store/id-conflict id kind))
      (catch Exception _
        (failure :fault :store/write-failed id kind))
      (finally (.delete tmp)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-record
  [dir id kind record-schema]
  (read-file (record-file dir id kind) id kind record-schema))

(defn ^{:stratum 2} create!
  [dir record kind]
  (let [encoded (encode-record record kind)]
    (if (anomaly/anomaly? encoded)
      encoded
      (publish! (record-file dir (:grant/id record) kind) encoded record kind))))

(comment
  (encode-record {:grant/id (random-uuid)} :grant))
