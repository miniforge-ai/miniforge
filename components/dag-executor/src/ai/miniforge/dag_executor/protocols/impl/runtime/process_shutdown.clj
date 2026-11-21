;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.process-shutdown
  "Defer cancellation until the owned process tree has received forced termination."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [slingshot.slingshot :refer [try+]])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} targets [^Process child]
  (with-open [descendants (.descendants (.toHandle child))]
    (conj (vec (reverse (iterator-seq (.iterator descendants)))) (.toHandle child))))

(defn- ^{:stratum 0} signal! [force? handles]
  (doseq [^ProcessHandle handle handles :when (.isAlive handle)]
    (if force? (.destroyForcibly handle) (.destroy handle))))

(defn- ^{:stratum 0} wait-once! [^Process child until]
  (.waitFor child (deadline/remaining-ns until) TimeUnit/NANOSECONDS))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} await-exit! [child until interrupted]
  (loop []
    (when-not (deadline/expired? until)
      (let [outcome (try+ (wait-once! child until)
                         (catch InterruptedException _ ::interrupted))]
        (when (= ::interrupted outcome)
          (vreset! interrupted true)
          (recur))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stop!
  "Terminate the snapshotted descendants and direct child, leaf first. Graceful
   and forced waits each have a fixed deadline; interrupts never reset either."
  [child grace-ms]
  (let [handles (targets child)
        graceful-until (deadline/after-ms grace-ms)
        forced-until (+ graceful-until (* grace-ms deadline/nanos-per-ms))
        interrupted (volatile! (Thread/interrupted))]
    (try+
      (signal! false handles)
      (await-exit! child graceful-until interrupted)
      (signal! true handles)
      (await-exit! child forced-until interrupted)
      (finally (when @interrupted (.interrupt (Thread/currentThread)))))))

(comment
  (let [child (.start (ProcessBuilder. ["java" "-version"]))
        grace-ms (.toMillis (java.time.Duration/ofSeconds 1))]
    (stop! child grace-ms)))
