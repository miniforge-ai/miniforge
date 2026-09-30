;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-codec
  "Bounded Transit encoding for immutable artifact publication."
  (:require [ai.miniforge.artifact.publication-types :as types]
            [cognitect.transit :as transit]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStreamReader OutputStream]
           [java.nio.charset CodingErrorAction StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-bytes (* 16 1024 1024))

(defn- ^{:stratum 0} utf8-decoder []
  (doto (.newDecoder StandardCharsets/UTF_8)
    (.onMalformedInput CodingErrorAction/REPORT)
    (.onUnmappableCharacter CodingErrorAction/REPORT)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} bounded-output [^ByteArrayOutputStream buffer overflow?]
  (proxy [OutputStream] []
    (write
      ([value]
       (if (< (.size buffer) maximum-bytes)
         (.write buffer (int value))
         (reset! overflow? true)))
      ([bytes offset length]
       (if (<= (+ (.size buffer) length) maximum-bytes)
         (.write buffer bytes offset length)
         (reset! overflow? true))))))

(defn ^{:stratum 1} decode [bytes]
  (with-open [json-input (io/reader (InputStreamReader. (ByteArrayInputStream. bytes) (utf8-decoder)))
              transit-input (ByteArrayInputStream. bytes)]
    (when (= 1 (count (take 2 (json/parsed-seq json-input))))
      (transit/read (transit/reader transit-input :json types/read-options)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} encode [artifact]
  (with-open [output (ByteArrayOutputStream.)]
    (let [overflow? (atom false)]
      (transit/write (transit/writer (bounded-output output overflow?) :json types/write-options) artifact)
      (when-not @overflow?
        (let [bytes (.toByteArray output)]
          (when (= artifact (decode bytes)) bytes))))))
