;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture
  "Owned process capture and cleanup for a shared monotonic deadline."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process])
  (:import [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [reason]
  {:exit process/timeout-exit-code :out "" :err "" :reason reason})

(defn ^{:stratum 0} drains [^Process child]
  (let [options {:on-overflow (partial process/destroy-process-tree! child)}]
    [(process/drain-stream (.getInputStream child) options)
     (process/drain-stream (.getErrorStream child) options)]))

(defn- ^{:stratum 0} output-finished? [until {worker :future}]
  (let [wait-ms (quot (deadline/remaining-ns until) deadline/nanos-per-ms)]
    (not= ::pending (deref worker wait-ms ::pending))))

(defn- ^{:stratum 0} completed [^Process child [stdout stderr]]
  (let [exit (.exitValue child)
        out (process/drained-text stdout)
        err (process/drained-text stderr)]
    {:exit exit :out out :err err}))

(defn ^{:stratum 0} cleanup! [^Process child capture]
  (process/destroy-process-tree! child)
  (.waitFor child process/graceful-shutdown-ms TimeUnit/MILLISECONDS)
  (run! process/cancel-drain! capture))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} await! [^Process child capture until]
  (let [exited? (.waitFor child (deadline/remaining-ns until) TimeUnit/NANOSECONDS)
        captured? (and exited? (every? (partial output-finished? until) capture))]
    (cond
      (deadline/expired? until) (failure :deadline)
      (some process/overflowed? capture) (failure :output-limit)
      (not captured?) (failure :incomplete-output)
      :else (completed child capture))))
