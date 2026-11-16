;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-codec
  "Durable record timestamps and lossless EDN/UTF-8 encoding."
  (:require [ai.miniforge.execution-grant.temporal :as temporal])
  (:import [java.nio CharBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} wire-text [wire]
  (binding [*print-length* nil *print-level* nil *print-dup* false *print-readably* true
            *print-meta* false *print-namespace-maps* false]
    (pr-str wire)))

(defn- ^{:stratum 0} strict-encoder []
  (doto (.newEncoder StandardCharsets/UTF_8)
    (.onMalformedInput CodingErrorAction/REPORT)
    (.onUnmappableCharacter CodingErrorAction/REPORT)))

(def ^{:stratum 0} instant-keys
  [:grant/issued-at :grant/expires-at :grant/revoked-at
   :authority/created-at :authority/stopped-at])

(defn- ^{:stratum 0} encode-instant
  [record k]
  (if-some [value (get record k)]
    (assoc record k (str (temporal/->instant value)))
    record))

(defn- ^{:stratum 0} decode-instant
  [record k]
  (if-some [value (get record k)]
    (assoc record k (Instant/parse value))
    record))

(defn- ^{:stratum 0} normalize-instant
  [record k]
  (if-some [value (get record k)]
    (assoc record k (temporal/->instant value))
    record))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} strict-utf8 [text]
  (let [encoder (strict-encoder)
        buffer (.encode encoder (CharBuffer/wrap text))
        bytes (byte-array (.remaining buffer))]
    (.get buffer bytes)
    bytes))

(defn ^{:stratum 1} ->wire [record]
  (reduce encode-instant record instant-keys))

(defn ^{:stratum 1} <-wire [record]
  (reduce decode-instant record instant-keys))

(defn ^{:stratum 1} normalize [record]
  (reduce normalize-instant record instant-keys))

(comment
  (<-wire (->wire {:grant/revoked-at nil})))
