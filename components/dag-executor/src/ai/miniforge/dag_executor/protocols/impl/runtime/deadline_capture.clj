;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture
  "Owned process capture and cleanup for a shared monotonic deadline."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} failure-exit-codes
  "Timeout, conventional SIGINT, capture-limit and launch-failure result codes."
  {:deadline process/timeout-exit-code
   :incomplete-output process/timeout-exit-code
   :interrupted 130
   :output-limit process/output-limit-exit-code
   :command-error 1})

(defn ^{:stratum 0} drains [^Process child]
  (let [stop! (partial process/destroy-process-tree! child)
        options {:on-overflow stop!}]
    [(process/drain-stream (.getInputStream child) options)
     (process/drain-stream (.getErrorStream child) options)]))

(defn- ^{:stratum 0} output-finished? [until {worker :future}]
  (let [wait-ms (deadline/ceil-ms (deadline/remaining-ns until))]
    (not= ::pending (deref worker wait-ms ::pending))))

(defn- ^{:stratum 0} command-result [exit out err]
  {:exit exit :out out :err err})

(defn ^{:stratum 0} cleanup! [^Process child capture]
  (try+
    (process/destroy-process-tree! child)
    (finally (run! process/cancel-drain! capture))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} completed [^Process child [stdout stderr]]
  (let [exit (.exitValue child)
        out (process/drained-text stdout)
        err (process/drained-text stderr)]
    (command-result exit out err)))

(defn ^{:stratum 1} failure [reason]
  (let [exit (get failure-exit-codes reason)]
    (assoc (command-result exit "" "") :reason reason)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} await! [^Process child capture until]
  (let [exited? (.waitFor child (deadline/remaining-ns until) TimeUnit/NANOSECONDS)
        captured? (and exited? (every? (partial output-finished? until) capture))]
    (cond
      (deadline/expired? until) (failure :deadline)
      (some process/overflowed? capture) (failure :output-limit)
      (not captured?) (failure :incomplete-output)
      :else (completed child capture))))
