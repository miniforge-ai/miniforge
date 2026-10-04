;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.critical
  "Preserve fatal and interruption causes across storage and delivery boundaries.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} cause [throwable]
  (loop [current throwable seen #{}]
    (cond
      (nil? current) nil
      (contains? seen current) nil
      (or (instance? Error current) (instance? InterruptedException current)) current
      :else (recur (.getCause ^Throwable current) (conj seen current)))))

(defn ^{:stratum 0} propagate! [throwable]
  (when (instance? InterruptedException throwable) (.interrupt (Thread/currentThread)))
  (throw throwable))

(comment
  (cause nil))
