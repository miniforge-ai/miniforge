;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command
  "Internal CLI transport; process, input and capture share a monotonic deadline."
  (:refer-clojure :exclude [run!])
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture :as capture]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-exchange :as exchange]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} start! [argv]
  (.start (ProcessBuilder. ^java.util.List argv)))

(defn- ^{:stratum 0} run-child! [child until payload]
  (let [reply (exchange/run! child until payload)]
    (if (.isInterrupted (Thread/currentThread)) (capture/failure :interrupted) reply)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run!
  "Run argv with optional stdin bytes before until (System/nanoTime units).
   No cancelled/expired admission or partial input/output success. Cleanup may
   consume the shared shutdown grace. Interrupts survive owned-process cleanup."
  ([until argv] (run! until argv nil))
  ([until argv payload]
   (try+
     (cond
       (.isInterrupted (Thread/currentThread)) (capture/failure :interrupted)
       (deadline/expired? until) (capture/failure :deadline)
       :else (run-child! (start! argv) until payload))
     (catch InterruptedException _
       (.interrupt (Thread/currentThread))
       (capture/failure :interrupted))
     (catch java.io.IOException _ (capture/failure :command-io-error))
     (catch Exception _ (capture/failure :command-error)))))

(comment
  (run! (System/nanoTime) []))
