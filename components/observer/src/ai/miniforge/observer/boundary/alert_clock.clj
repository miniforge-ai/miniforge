;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.boundary.alert-clock
  "Legacy timestamp coercion, retaining the wall-clock fallback."
  (:require [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} event-ms [event]
  (try+
    (let [ts (:event/timestamp event)]
      (cond
        (instance? java.util.Date ts) (.getTime ^java.util.Date ts)
        (number? ts) (long ts)
        (string? ts) (.toEpochMilli (java.time.Instant/parse ts))
        :else (System/currentTimeMillis)))
    (catch Exception _ (System/currentTimeMillis))))

(comment ::legacy-alert-clock)
