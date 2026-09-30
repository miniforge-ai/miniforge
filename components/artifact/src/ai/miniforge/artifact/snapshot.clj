;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.snapshot
  "Lossless artifact envelopes for embedding in text-oriented checkpoints."
  (:require [ai.miniforge.artifact.publication-boundary :as boundary]
            [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.artifact.publication-record :as record]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bounded-bytes [^String snapshot]
  (when (<= (count snapshot) codec/maximum-bytes)
    (let [bytes (.getBytes snapshot StandardCharsets/UTF_8)]
      (when (and (<= (alength bytes) codec/maximum-bytes)
                 (= snapshot (String. ^bytes bytes StandardCharsets/UTF_8)))
        bytes))))

(defn- ^{:stratum 0} encode-record [value]
  (if-let [bytes (record/encode value)]
    (String. ^bytes bytes StandardCharsets/UTF_8)
    (boundary/failure :invalid-input :publication/not-portable (:artifact/id value))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} decode-record [snapshot]
  (let [value (some-> snapshot bounded-bytes record/decode)]
    (if (schema/valid-artifact? value)
      value
      (boundary/failure :invalid-input :snapshot/invalid nil))))

(defn ^{:stratum 1} encode [value]
  (boundary/call-with-exception-handling
   (:artifact/id value) :invalid-input :publication/not-portable
   (partial encode-record value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} decode [snapshot]
  (boundary/call-with-exception-handling
   nil :invalid-input :snapshot/invalid (partial decode-record snapshot)))
