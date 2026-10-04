;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-record
  "Versioned integrity envelope for immutable artifacts; not writer authentication."
  (:require [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.content-hash.interface :as hash])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode [bytes]
  (let [envelope (codec/decode bytes)
        wire (:publication/wire envelope)]
    (when (and (map? envelope)
               (= #{:publication/version :publication/content-hash :publication/wire}
                  (set (keys envelope)))
               (= 1 (:publication/version envelope))
               (string? wire)
               (= (:publication/content-hash envelope) (hash/content-hash wire)))
      (codec/decode (.getBytes ^String wire StandardCharsets/UTF_8)))))

(defn ^{:stratum 0} encode [artifact]
  (when-let [bytes (codec/encode artifact)]
    (let [wire (String. ^bytes bytes StandardCharsets/UTF_8)]
      (codec/encode {:publication/version 1
                     :publication/content-hash (hash/content-hash wire)
                     :publication/wire wire}))))
