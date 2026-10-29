;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-lock-test
  "A run's checkpoint lock held past the timeout makes the GC report a
   failure for that run and leave it alone."
  (:require [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} expired-run! [root]
  (let [id (random-uuid)
        dir (io/file root (str id))]
    (.mkdirs dir)
    (spit (io/file dir "manifest.edn") "{:workflow/id 1}")
    (doseq [f (reverse (file-seq dir))]
      (.setLastModified ^java.io.File f (- (System/currentTimeMillis) (* 90 24 60 60 1000))))
    id))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-lock-held-too-long-is-a-reported-failure
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (expired-run! root)
          held (CountDownLatch. 1)
          release (CountDownLatch. 1)
          holder (future (checkpoint-lock/with-run-lock root id
                           (fn [] (.countDown held) (.await release 10 TimeUnit/SECONDS))))]
      (.await held 10 TimeUnit/SECONDS)
      (let [result (with-redefs [checkpoint-lock/lock-timeout-ms 200]
                     (workflow/gc-checkpoints! {:checkpoint/root root}))]
        (.countDown release)
        @holder
        (is (= 1 (count (:failures result))))
        (is (empty? (:deleted result)))
        (is (.isDirectory (io/file root (str id))))))))

(comment ::checkpoint-lock)
