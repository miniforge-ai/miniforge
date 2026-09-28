;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-codec
  "Bounded Transit encoding for immutable artifact publication."
  (:require [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream OutputStream]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-bytes (* 16 1024 1024))

(defn ^{:stratum 0} decode [bytes]
  (with-open [input (ByteArrayInputStream. bytes)]
    (transit/read (transit/reader input :json))))

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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} encode [artifact]
  (with-open [output (ByteArrayOutputStream.)]
    (let [overflow? (atom false)]
      (transit/write (transit/writer (bounded-output output overflow?) :json) artifact)
      (when-not @overflow?
        (let [bytes (.toByteArray output)]
          (when (= artifact (decode bytes)) bytes))))))
