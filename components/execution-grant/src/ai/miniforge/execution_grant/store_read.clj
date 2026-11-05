;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-read
  "Fail-closed file reads: reject symlinks, trailing data and invalid authority."
  (:require [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.execution-grant.store-path :as path]
            [clojure.edn :as edn]
            [malli.core :as m])
  (:import [java.io File InputStreamReader PushbackReader StringReader]
           [java.nio.charset CodingErrorAction StandardCharsets]
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

(defn- ^{:stratum 0} utf8-decoder
  []
  (doto (.newDecoder StandardCharsets/UTF_8)
    (.onMalformedInput CodingErrorAction/REPORT)
    (.onUnmappableCharacter CodingErrorAction/REPORT)))

(defn- ^{:stratum 0} absent?
  [^File file]
  (Files/notExists (.toPath file) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} checked-record
  [encoded id record-schema on-error]
  (let [value (decode-record encoded)]
    (if (and (m/validate record-schema value) (= id (:grant/id value)))
      value
      (on-error))))

(defn- ^{:stratum 1} read-text
  [^File file]
  (let [options (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS])]
    (with-open [stream (Files/newInputStream (.toPath file) options)
                reader (InputStreamReader. stream (utf8-decoder))]
      (slurp reader))))

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
