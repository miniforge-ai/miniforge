;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.edn-codec
  "Bounded single-form EDN with nanosecond-preserving instant reads."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [clojure.edn :as edn])
  (:import [java.io PushbackReader StringReader]
           [java.nio CharBuffer]
           [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-bytes (* 16 1024 1024))

(defn- ^{:stratum 0} read-instant [value]
  (java.time.Instant/parse value))

(defn ^{:stratum 0} encode [bundle]
  (hash/canonical-edn bundle))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} within-limit? [text]
  (and (string? text) (<= (count text) maximum-bytes)
       (<= (.remaining (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap text)))
           maximum-bytes)))

(defn- ^{:stratum 1} read-single-form [text]
  (with-open [reader (PushbackReader. (StringReader. text))]
    (let [eof (Object.)
          options {:readers {'inst read-instant} :eof eof}
          value (edn/read options reader)]
      (when (and (not (identical? eof value))
                 (identical? eof (edn/read options reader)))
        value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} decode-with-exception-handling [text]
  ;; No slingshot dependency; this is the untrusted EDN parsing boundary.
  (try
    (when (within-limit? text)
      (read-single-form text))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _ nil)))

(comment
  (decode-with-exception-handling "{} {}"))
