;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.native-exit-wait
  "Defer interruption while awaiting one snapshotted process, without renewing time."
  (:require [slingshot.slingshot :refer [try+]])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent Future TimeUnit TimeoutException ExecutionException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} wait-native! [^Future completed until]
  (.get completed (max 0 (- until (System/nanoTime))) TimeUnit/NANOSECONDS))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} step! [completed until interrupted]
  (try+
    (when (< (System/nanoTime) until) (wait-native! completed until))
    false
    (catch InterruptedException _
      (vreset! interrupted true)
      ::retry)
    (catch TimeoutException _ false)
    (catch ExecutionException _ false)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} await! [^ProcessHandle handle until interrupted]
  (let [completed (.onExit handle)]
    (try+
      (loop []
        (if (= ::retry (step! completed until interrupted))
          (recur)
          (not (.isAlive handle))))
      (finally (.cancel completed false)))))

(comment
  (step! (java.util.concurrent.CompletableFuture/completedFuture nil)
         (System/nanoTime) (volatile! false)))
