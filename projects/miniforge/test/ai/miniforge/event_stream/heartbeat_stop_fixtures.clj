;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-stop-fixtures
  (:require [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} wait-ms 5000)

(defn ^{:stratum 0} signals []
  (into {} (map (fn [key] [key (CountDownLatch. 1)])) [:entered :release :interrupted]))

(defn ^{:stratum 0} block! [{:keys [entered release interrupted]} _event]
  (.countDown ^CountDownLatch entered)
  (try+ (.await ^CountDownLatch release)
    (catch InterruptedException _ (.countDown ^CountDownLatch interrupted))))

(defn ^{:stratum 0} release! [signals]
  (.countDown ^CountDownLatch (:release signals)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} await! [signal]
  (.await ^CountDownLatch signal wait-ms TimeUnit/MILLISECONDS))

(defn ^{:stratum 1} stopped [pending] (deref pending wait-ms ::timed-out))

(comment ::bounded-interruptible-listener)
