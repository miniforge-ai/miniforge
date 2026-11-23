;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.resource-cleanup.interrupt-cleanup-test
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interruptible-release! [released]
  (.await (java.util.concurrent.CountDownLatch. 0))
  (reset! released true))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} existing-interrupt-does-not-prevent-release
  (let [released (atom false)]
    (try+
      (.interrupt (Thread/currentThread))
      (cleanup/finish! (constantly nil) (partial interruptible-release! released))
      (let [flagged? (.isInterrupted (Thread/currentThread))]
        (is @released)
        (is flagged?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} failure-cleanup-completes-before-restoring-interrupt
  (let [released (atom false)
        original (InterruptedException. "fixture original interruption")]
    (try+
      (.interrupt (Thread/currentThread))
      (let [caught (try+ (cleanup/after-failure! original (partial interruptible-release! released))
                     (catch Object _ (:throwable &throw-context)))
            flagged? (.isInterrupted (Thread/currentThread))]
        (is @released)
        (is (identical? original caught))
        (is flagged?))
      (finally (Thread/interrupted)))))

(comment ::interrupt-safe-resource-release)
