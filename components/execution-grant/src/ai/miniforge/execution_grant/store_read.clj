;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-read
  "Fail-closed file reads: reject symlinks, trailing data and invalid authority."
  (:require [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.execution-grant.store-path :as path]
            [ai.miniforge.utf8.interface :as utf8]
            [clojure.edn :as edn]
            [malli.core :as m])
  (:import [java.io File InputStream PushbackReader StringReader]
           [java.nio.file Files LinkOption NoSuchFileException OpenOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} decode-record
  [encoded]
  (with-open [reader (PushbackReader. (StringReader. encoded))]
    (let [value (edn/read reader)
          eof (Object.)
          tail (edn/read {:eof eof} reader)]
      (when (identical? eof tail)
        (codec/<-wire value)))))

(defn- ^{:stratum 0} absent?
  [^File file]
  (Files/notExists (.toPath file) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- ^{:stratum 0} read-text
  "The file's text, or nil when it is not well-formed UTF-8."
  [^File file]
  (let [options (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS])]
    (with-open [^InputStream stream (Files/newInputStream (.toPath file) options)]
      (utf8/decode-strict (.readAllBytes stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} checked-record
  [encoded id record-schema on-error]
  (let [value (some-> encoded decode-record)]
    (if (and (m/validate record-schema value) (= id (:grant/id value)))
      value
      (on-error))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-record
  ([file id record-schema on-error]
   (read-record file id record-schema on-error true))
  ([file id record-schema on-error may-retry?]
   (try
     (if-not (path/safe? file)
       (on-error)
       (checked-record (read-text file) id record-schema on-error))
     (catch NoSuchFileException _
       (cond (absent? file) nil
             may-retry? (read-record file id record-schema on-error false)
             :else (on-error)))
     (catch Exception _ (on-error)))))

(comment
  (decode-record "{} {}"))
