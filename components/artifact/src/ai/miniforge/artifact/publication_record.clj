;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-record
  "Versioned integrity envelope for immutable artifacts; not writer authentication."
  (:require [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.content-hash.interface :as hash]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode [bytes]
  (let [envelope (codec/decode bytes)
        artifact (:publication/artifact envelope)]
    (when (and (map? envelope)
               (= #{:publication/version :publication/content-hash :publication/artifact}
                  (set (keys envelope)))
               (= 1 (:publication/version envelope))
               (= (:publication/content-hash envelope) (hash/content-hash artifact)))
      artifact)))

(defn ^{:stratum 0} encode [artifact]
  (when (codec/encode artifact)
    (codec/encode {:publication/version 1
                   :publication/content-hash (hash/content-hash artifact)
                   :publication/artifact artifact})))
