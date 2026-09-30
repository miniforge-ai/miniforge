;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.boundary.codec
  "Java streaming boundary; the publication entry point converts failures to data."
  (:require [ai.miniforge.artifact.messages :as msg])
  (:import [java.io ByteArrayOutputStream IOException OutputStream]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bounded-output [^ByteArrayOutputStream buffer limit]
  ;; Java's void OutputStream callback cannot return an anomaly. Abort the codec
  ;; here; the publication entry boundary converts IOException to invalid-input.
  (proxy [OutputStream] []
    (write
      ([value]
       (if (< (.size buffer) limit)
         (.write buffer (int value))
         (throw (IOException. (msg/t :publication/not-portable)))))
      ([bytes offset length]
       (if (<= (+ (.size buffer) length) limit)
         (.write buffer bytes offset length)
         (throw (IOException. (msg/t :publication/not-portable))))))))
