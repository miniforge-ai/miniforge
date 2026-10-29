;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-pin-publication-support
  (:require [ai.miniforge.workflow.checkpoint-store-paths :as paths])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} wait-seconds 10)

(def ^{:stratum 0} wait-ms 10000)

(defn ^{:stratum 0} latch [] (CountDownLatch. 1))

(defn ^{:stratum 0} before-gc-lock! [lock! entered root id f]
  (.countDown ^CountDownLatch entered)
  (lock! root id f))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} await! [^CountDownLatch signal]
  (.await signal wait-seconds TimeUnit/SECONDS))

(defn ^{:stratum 1} before-pin-temporary! [create! inside release options]
  ;; Pause before reservation changes the directory's mtime and GC sees it as fresh.
  (when (= paths/pin-marker-temp-prefix (:prefix options))
    (.countDown ^CountDownLatch inside)
    (.await ^CountDownLatch release wait-seconds TimeUnit/SECONDS))
  (create! options))

(comment ::pin-publication-interleaving)
