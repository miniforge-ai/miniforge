;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-pin-race-test
  "A pin racing the checkpoint GC ends pinned and kept, or refused with the
   run deleted; never pinned and deleted. Latches force each interleaving."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pin-on-another-thread!
  "Pin run `id` on its own thread and wait for it; returns what pinning
   returned."
  [root id]
  (let [result (future (workflow/pin-checkpoint! id {:checkpoint/root root}))]
    (deref result 10000 ::timed-out)))

(defn- ^{:stratum 0} assert-consistent
  [root id pin-result]
  (let [present? (.isDirectory (io/file root (str id)))
        pinned? (and present? (fs/exists? (paths/pin-marker-path root id)))
        pin-ok? (string? pin-result)]
    (is (or (and pin-ok? pinned?) (and (not pin-ok?) (not present?)))
        (str "pin " (if pin-ok? "succeeded" "failed") ", run "
             (if present? "kept" "deleted")))))

(deftest ^{:stratum 0} a-run-the-gc-is-deciding-on-stays-held
  ;; The event cleanup's retention hold probes the run while the GC is
  ;; deciding whether to delete it (a pin landed after it classified the
  ;; run). Whatever the GC does next between the hold's probes, a run that
  ;; ends up kept must never have been reported unheld.
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [events-dir {:prefix "pin-race-events-"}]
       (let [id (fixture/expired-run! root)
             at-check (CountDownLatch. 1)
             go-on (CountDownLatch. 1)
             decided (CountDownLatch. 1)
             delete-run! @#'gc/delete-run!
             pinned-dir? @#'gc/pinned-dir?
             present? store/checkpoint-present?
             move fs/move]
         (with-redefs-fn
           {#'gc/delete-run! (fn [& args]
                               (workflow/pin-checkpoint! id {:checkpoint/root root})
                               (apply delete-run! args))
            #'gc/pinned-dir? (fn [dir]
                               (.countDown at-check)
                               (.await go-on 10 TimeUnit/SECONDS)
                               (pinned-dir? dir))
            #'fs/move (let [moves (atom 0)]
                        ;; A GC that moves the run aside to decide moves it
                        ;; back once it sees the pin: the second move.
                        (fn [& args]
                          (let [moved (apply move args)]
                            (when (= 2 (swap! moves inc))
                              (.countDown decided))
                            moved)))
            #'store/checkpoint-present? (fn [& args]
                                          (let [found (apply present? args)]
                                            ;; Let the GC finish its decision between
                                            ;; this probe and the next one.
                                            (.countDown go-on)
                                            (.await decided 2 TimeUnit/SECONDS)
                                            found))}
           (fn []
             (let [gc-run (future (workflow/gc-checkpoints! {:checkpoint/root root}))]
               (.await at-check 10 TimeUnit/SECONDS)
               (is (true? (es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir))))
               (.countDown go-on)
               (let [result (deref gc-run 10000 ::timed-out)]
                 (is (= [id] (get-in result [:kept :pinned]))))))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-pin-just-before-the-delete-keeps-the-run
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (fixture/expired-run! root)
          pin-result (atom ::not-run)
          delete-run! @#'gc/delete-run!]
      (with-redefs-fn {#'gc/delete-run! (fn [& args]
                                          (reset! pin-result (pin-on-another-thread! root id))
                                          (apply delete-run! args))}
        #(workflow/gc-checkpoints! {:checkpoint/root root}))
      (assert-consistent root id @pin-result)
      (is (string? @pin-result))
      (is (.isDirectory (io/file root (str id)))))))

(deftest ^{:stratum 1} a-pin-just-after-the-delete-is-refused
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (fixture/expired-run! root)
          pin-result (atom ::not-run)
          delete-run! @#'gc/delete-run!]
      (with-redefs-fn {#'gc/delete-run! (fn [& args]
                                          (let [deleted (apply delete-run! args)]
                                            (reset! pin-result (pin-on-another-thread! root id))
                                            deleted))}
        #(workflow/gc-checkpoints! {:checkpoint/root root}))
      (assert-consistent root id @pin-result)
      (is (not (string? @pin-result))))))

(deftest ^{:stratum 1} gc-waits-for-a-pin-mid-write-and-then-keeps-the-run
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (fixture/expired-run! root)
          inside (CountDownLatch. 1)
          release (CountDownLatch. 1)
          write spit]
      (with-redefs [spit (fn [f & args]
                           (when (str/ends-with? (str f) "pinned.tmp")
                             (.countDown inside)
                             (.await release 10 TimeUnit/SECONDS))
                           (apply write f args))]
        (let [pin (future (workflow/pin-checkpoint! id {:checkpoint/root root}))
              _ (.await inside 10 TimeUnit/SECONDS)
              gc-run (future (workflow/gc-checkpoints! {:checkpoint/root root}))]
          (Thread/sleep 300)
          (testing "the GC waits while the pin holds the run's lock"
            (is (not (realized? gc-run))))
          (.countDown release)
          (let [pinned @pin
                result (deref gc-run 10000 ::timed-out)]
            (assert-consistent root id pinned)
            (testing "then sees the pin and keeps the run"
              (is (string? pinned))
              (is (empty? (:deleted result)))
              (is (= [id] (get-in result [:kept :pinned]))))))))))

(comment ::checkpoint-pin-race)
