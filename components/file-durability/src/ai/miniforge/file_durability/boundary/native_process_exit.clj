;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.native-process-exit
  "Confirm exit of an owned process and its known descendant snapshot, not containment."
  (:require [ai.miniforge.file-durability.boundary.native-exit-wait :as wait]
            [slingshot.slingshot :refer [try+]])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} targets [^Process child]
  (with-open [descendants (.descendants (.toHandle child))]
    (conj (vec (reverse (iterator-seq (.iterator descendants)))) (.toHandle child))))

(defn- ^{:stratum 0} signal! [^ProcessHandle handle]
  (when (.isAlive handle) (.destroyForcibly handle)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stop-target! [until interrupted handle]
  (try+
    (signal! handle)
    (wait/await! handle until interrupted)
    (catch InterruptedException _
      (vreset! interrupted true)
      false)
    (catch Exception _ false)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stop!
  "Force the captured targets and acknowledge only their observed native death.
   One caller-owned shutdown budget spans all waits and repeated interruptions.
   A descendant snapshot cannot contain hostile forking; callers need isolation for that."
  [child timeout-ms]
  (let [until (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS timeout-ms))
        interrupted (volatile! (Thread/interrupted))]
    (try+
      (let [handles (targets child)]
        (every? true? (mapv (partial stop-target! until interrupted) handles)))
      (catch InterruptedException _
        (vreset! interrupted true)
        false)
      (catch Exception _ false)
      (finally (when @interrupted (.interrupt (Thread/currentThread)))))))

(comment
  (let [child (.start (ProcessBuilder. ["/bin/sleep" "0.01"]))]
    (try
      (stop! child 1000)
      (finally (.destroyForcibly child)))))
