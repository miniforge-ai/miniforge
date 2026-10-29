;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-lock-test
  "A run's checkpoint lock held past the timeout makes the GC report a
   failure for that run and leave it alone."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
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

(deftest ^{:stratum 0} a-run-started-pinned-pins-under-the-lock
  ;; The marker a pinned run's first checkpoint writes goes through the
  ;; locked pin: with the lock held elsewhere, the pin times out and the
  ;; checkpoint write reports it.
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (let [root (str root)
          ctx (context/create-context {:workflow/id :pin-lock :workflow/version "1.0.0"
                                       :workflow/pipeline [{:phase :plan}]}
                                      {:task "Test"}
                                      {:checkpoint/root root :checkpoint/pin? true})
          id (:execution/id ctx)
          held (CountDownLatch. 1)
          release (CountDownLatch. 1)
          holder (future (checkpoint-lock/with-run-lock root id
                           (fn [] (.countDown held) (.await release 10 TimeUnit/SECONDS))))]
      (.await held 10 TimeUnit/SECONDS)
      (let [written (with-redefs [checkpoint-lock/lock-timeout-ms 200]
                      (store/persist-execution-state! ctx))]
        (.countDown release)
        @holder
        (is (anomaly/anomaly? written))
        (is (not (fs/exists? (io/file root (str id) "pinned"))))))))

(deftest ^{:stratum 0} lock-io-failures-are-anomalies-and-callback-errors-propagate
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (let [root (str root)
          id (random-uuid)
          called? (atom false)]
      ;; .locks as a plain file: the lock directory cannot be made.
      (spit (io/file root ".locks") "")
      (testing "acquisition I/O failure"
        (let [result (checkpoint-lock/with-run-lock root id #(reset! called? true))]
          (is (anomaly/anomaly? result))
          (is (= :unavailable (:anomaly/type result)))
          (is (false? @called?)))))
    (fs/with-temp-dir [root {:prefix "pin-lock-"}]
      (testing "an exception from the callback still propagates"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                              (checkpoint-lock/with-run-lock (str root) (random-uuid)
                                #(throw (ex-info "boom" {})))))))))

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

(deftest ^{:stratum 1} lock-files-stay-after-the-run-is-deleted
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (let [root (str root)
          id (expired-run! root)]
      (is (= [id] (:deleted (workflow/gc-checkpoints! {:checkpoint/root root}))))
      (is (fs/exists? (checkpoint-lock/lock-file root id))
          "unlinking it would let a new caller lock a fresh file while a waiter holds the old one"))))

(comment ::checkpoint-lock)
