;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.edn-file
  "Size-bounded UTF-8 file input for the canonical EDN codec."
  (:require [ai.miniforge.evidence-bundle.edn-codec :as codec]
            [clojure.java.io :as io])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} read-text [file]
  (with-open [stream (io/input-stream (io/file file))]
    (let [bytes (.readNBytes stream (inc codec/maximum-bytes))]
      (when (<= (alength bytes) codec/maximum-bytes)
        (str (.decode (.newDecoder StandardCharsets/UTF_8) (ByteBuffer/wrap bytes)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-with-exception-handling [file]
  ;; No slingshot dependency; expected I/O and decoding failures are absent input.
  (try
    (codec/decode-with-exception-handling (read-text file))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _ nil)))

(comment
  (read-with-exception-handling "/missing/evidence.edn"))
