;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.native-probe
  "Bound the lifetime of trusted local read-only probes."
  (:require [babashka.process :as process]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io InputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} timeout-ms
  "Storage qualification must finish or refuse; never wait indefinitely for a helper."
  5000)

(def ^{:stratum 0 :private true} output-limit
  "Accept bounded native storage metadata, not an unbounded command stream."
  65536)

(defn- ^{:stratum 0} stop! [child]
  (when-let [^Process worker (:proc child)]
    (when (.isAlive worker) (.destroyForcibly worker))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-utf8 [^InputStream input]
  (with-open [stream input]
    (let [bytes (.readNBytes stream (inc output-limit))]
      (when (<= (alength bytes) output-limit)
        (String. bytes StandardCharsets/UTF_8)))))

(defn- ^{:stratum 1} remaining-ms [start]
  (max 0 (- timeout-ms (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) start)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-output [arguments input]
  (let [start (System/nanoTime)
        child (process/process arguments {:in input :out :stream :err :discard})
        captured (future (read-utf8 (:out child)))]
    (try+
      (let [result (deref child (remaining-ms start) nil)]
        (when (= 0 (:exit result))
          (deref captured (remaining-ms start) nil)))
      (finally
        (future-cancel captured)
        (stop! child)))))
