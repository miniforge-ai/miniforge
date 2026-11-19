;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command
  "Internal read-only CLI boundary; process and capture share a monotonic deadline."
  (:refer-clojure :exclude [run!])
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture :as capture]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} start! [argv]
  (.start (ProcessBuilder. ^java.util.List argv)))

(defn- ^{:stratum 0} capture-child! [child until]
  (let [streams (capture/drains child)]
    (try+
      (.close (.getOutputStream ^Process child))
      (capture/await! child streams until)
      (finally (capture/cleanup! child streams)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-child! [child until]
  (let [reply (capture-child! child until)]
    (if (.isInterrupted (Thread/currentThread)) (capture/failure :interrupted) reply)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run!
  "Run argv without stdin before until (System/nanoTime units). No expired
   admission or partial-output success. Cleanup may consume the shared shutdown
   grace. Cancellation restores the interrupt flag after owned-process cleanup."
  [until argv]
  (try+
    (if (deadline/expired? until)
      (capture/failure :deadline)
      (run-child! (start! argv) until))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      (capture/failure :interrupted))
    (catch Exception _ (capture/failure :command-error))))
