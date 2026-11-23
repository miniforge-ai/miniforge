;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.directory-pin-scan
  "Bound descriptor-backed witness lookup by caller-owned fuel and deadline."
  (:import [java.nio.file Path]
           [java.util Iterator]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} admitted? [remaining until]
  (and (pos? remaining)
       (not (.isInterrupted (Thread/currentThread)))
       (< (System/nanoTime) until)))

(defn- ^{:stratum 0} next-name [^Iterator entries]
  (.getFileName ^Path (.next entries)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} contains-witness? [^Iterable stream name limit until]
  (let [^Iterator entries (.iterator stream)]
    (loop [remaining limit]
      (cond
        (not (admitted? remaining until)) false
        (not (.hasNext entries)) false
        (= name (next-name entries)) (admitted? remaining until)
        :else (recur (dec remaining))))))

(comment (admitted? 1 (System/nanoTime)))
