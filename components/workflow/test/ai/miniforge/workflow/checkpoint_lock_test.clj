;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-lock-test
  "A run's checkpoint lock held past the timeout makes the GC report a
   failure for that run and leave it alone. Locks use fixed slot files,
   none per run; the GC removes the previous design's per-run lock files
   of runs that are gone."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} a-lock-held-too-long-is-a-reported-failure
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (fixture/expired-run! root)
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

(defn- ^{:stratum 0} gc! [root events-dir]
  (workflow/gc-checkpoints! {:checkpoint/root root :events-dir (fs/file events-dir)}))

(deftest ^{:stratum 0} locks-leave-no-file-per-run
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (let [root (str root)]
      (dotimes [_ 5] (checkpoint-lock/with-run-lock root (random-uuid) (constantly nil)))
      (is (every? #(str/starts-with? (str (fs/file-name %)) "slot-")
                  (fs/list-dir (fs/path root ".locks")))))))

(deftest ^{:stratum 0} a-symlinked-lock-file-or-directory-is-refused
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (fs/with-temp-dir [outside {:prefix "pin-lock-outside-"}]
      (let [root (str root)
            target (fs/path outside "target")
            id (random-uuid)]
        (spit (str target) "keep")
        (testing "a slot file that is a link: refused, the target untouched"
          (fs/create-dirs (fs/path root ".locks"))
          (fs/create-sym-link (checkpoint-lock/lock-file root id) target)
          (let [result (checkpoint-lock/with-run-lock root id (constantly :entered))]
            (is (anomaly/anomaly? result))
            (is (= "keep" (slurp (str target))))))
        (testing "a lock directory that is a link: refused, nothing made there"
          (fs/delete-tree (fs/path root ".locks"))
          (fs/create-sym-link (fs/path root ".locks") outside)
          (let [other (random-uuid)
                result (checkpoint-lock/with-run-lock root other (constantly :entered))]
            (is (anomaly/anomaly? result))
            (is (not (fs/exists? (fs/path outside (checkpoint-lock/slot-name other)))))))))))

(deftest ^{:stratum 0} an-uncreatable-lock-file-fails-at-once
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (let [root (str root)
          locks (fs/path root ".locks")]
      (fs/create-dirs locks)
      (fs/set-posix-file-permissions locks "r-x------")
      (try
        (when-not (fs/writable? locks)
          (let [started (System/nanoTime)
                result (checkpoint-lock/with-run-lock root (random-uuid) (constantly :entered))
                elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
            (is (anomaly/anomaly? result))
            (is (= :unavailable (:anomaly/type result)))
            (is (< elapsed-ms 2000) (str "took " elapsed-ms " ms, not the lock timeout"))))
        (finally (fs/set-posix-file-permissions locks "rwx------"))))))

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

(deftest ^{:stratum 0} two-paths-to-one-root-share-one-lock
  ;; A symlink to the checkpoint root is a second path to the same runs. A
  ;; caller through it must wait for the holder and then get the lock, not
  ;; collide with it on the shared lock file and fail.
  (fs/with-temp-dir [dir {:prefix "pin-lock-"}]
    (let [root (str (fs/create-dirs (fs/path dir "checkpoints")))
          alias (str (fs/create-sym-link (fs/path dir "alias") (fs/path root)))
          id (random-uuid)
          held (CountDownLatch. 1)
          release (CountDownLatch. 1)
          holder (future (checkpoint-lock/with-run-lock root id
                           (fn [] (.countDown held) (.await release 10 TimeUnit/SECONDS))))]
      (.await held 10 TimeUnit/SECONDS)
      (let [through-alias (future (checkpoint-lock/with-run-lock alias id (constantly :entered)))]
        (Thread/sleep 200)
        (.countDown release)
        @holder
        (is (= :entered (deref through-alias 10000 ::timed-out)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-legacy-lock-file-goes-with-the-deleted-run
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (fs/with-temp-dir [events-dir {:prefix "pin-lock-events-"}]
      (let [root (str root)
            id (fixture/expired-run! root)
            legacy (checkpoint-lock/legacy-lock-file root id)]
        (fs/create-dirs (fs/parent legacy))
        (spit (str legacy) "")
        (is (= [id] (:deleted (gc! root events-dir))))
        (is (not (fs/exists? legacy)))))))

(deftest ^{:stratum 1} legacy-lock-files-of-runs-already-gone-are-pruned
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (fs/with-temp-dir [events-dir {:prefix "pin-lock-events-"}]
      (let [root (str root)
            gone (random-uuid)
            kept (random-uuid)]
        (fs/create-dirs (fs/path root ".locks"))
        (doseq [id [gone kept]] (spit (str (checkpoint-lock/legacy-lock-file root id)) ""))
        (fs/create-dirs (fs/path root (str kept)))
        (spit (str (fs/path root (str kept) "manifest.edn")) "{:workflow/id 1}")
        (let [result (gc! root events-dir)]
          (is (= 1 (:locks-pruned result)))
          (is (not (fs/exists? (checkpoint-lock/legacy-lock-file root gone))))
          (is (fs/exists? (checkpoint-lock/legacy-lock-file root kept))))))))

(deftest ^{:stratum 1} only-lock-files-really-removed-are-counted
  (fs/with-temp-dir [root {:prefix "pin-lock-"}]
    (fs/with-temp-dir [events-dir {:prefix "pin-lock-events-"}]
      (let [root (str root)]
        (fs/create-dirs (fs/path root ".locks"))
        (spit (str (checkpoint-lock/legacy-lock-file root (random-uuid))) "")
        (with-redefs [checkpoint-lock/remove-legacy-lock-file! (constantly false)]
          (is (zero? (:locks-pruned (gc! root events-dir)))))))))

(comment ::checkpoint-lock)
