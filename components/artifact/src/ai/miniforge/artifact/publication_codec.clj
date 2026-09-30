;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-codec
  "Bounded Transit encoding for immutable artifact publication."
  (:require [ai.miniforge.artifact.publication-boundary :as boundary]
            [ai.miniforge.artifact.publication-shape :as shape]
            [cognitect.transit :as transit]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStreamReader]
           [java.nio.charset CodingErrorAction StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-bytes (* 16 1024 1024))

(def ^{:stratum 0} read-options
  {:handlers {"list" (transit/read-handler #(apply list %))}})

(defn- ^{:stratum 0} utf8-decoder []
  (doto (.newDecoder StandardCharsets/UTF_8)
    (.onMalformedInput CodingErrorAction/REPORT)
    (.onUnmappableCharacter CodingErrorAction/REPORT)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decode [bytes]
  (with-open [json-input (io/reader (InputStreamReader. (ByteArrayInputStream. bytes) (utf8-decoder)))
              transit-input (ByteArrayInputStream. bytes)]
    (when (= 1 (count (take 2 (json/parsed-seq json-input))))
      (transit/read (transit/reader transit-input :json read-options)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} encode [artifact]
  (when (shape/bounded-data? artifact maximum-bytes)
    (with-open [output (ByteArrayOutputStream.)]
      (transit/write (transit/writer (boundary/bounded-output output maximum-bytes) :json) artifact)
      (let [bytes (.toByteArray output)]
        (when (= artifact (decode bytes)) bytes)))))
