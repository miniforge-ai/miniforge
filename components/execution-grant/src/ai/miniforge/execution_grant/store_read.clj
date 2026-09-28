;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-read
  "Fail-closed file reads: reject symlinks, trailing data and invalid authority."
  (:require [ai.miniforge.execution-grant.store-codec :as codec]
            [clojure.edn :as edn]
            [malli.core :as m])
  (:import [java.io File PushbackReader StringReader]
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

(defn- ^{:stratum 0} read-text
  [^File file]
  (let [options (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS])]
    (with-open [stream (Files/newInputStream (.toPath file) options)]
      (slurp stream :encoding "UTF-8"))))

(defn- ^{:stratum 0} absent?
  [^File file]
  (Files/notExists (.toPath file) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-record
  [file id record-schema on-error]
  (try
    (let [value (decode-record (read-text file))]
      (if (and (m/validate record-schema value) (= id (:grant/id value)))
        value
        (on-error)))
    (catch NoSuchFileException _
      (if (absent? file) nil (on-error)))
    (catch Exception _ (on-error))))

(comment
  (decode-record "{} {}"))
