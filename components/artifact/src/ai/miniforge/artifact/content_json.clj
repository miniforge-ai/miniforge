;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.content-json
  "Lossless JSON encoding through the existing bounded Transit wire contract."
  (:require [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.artifact.publication-boundary :as boundary])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} encode [value]
  (if-let [bytes (codec/encode value)]
    (String. ^bytes bytes StandardCharsets/UTF_8)
    (boundary/failure :invalid-input :content/encoding-invalid nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} encode-with-exception-handling [value]
  ;; No slingshot dependency; this is the public serialization boundary.
  (try (encode value)
       (catch InterruptedException interrupted
         (.interrupt (Thread/currentThread))
         (throw interrupted))
       (catch Exception _ (boundary/failure :invalid-input :content/encoding-invalid nil))))

(comment
  (encode-with-exception-handling {:event/id (random-uuid)}))
