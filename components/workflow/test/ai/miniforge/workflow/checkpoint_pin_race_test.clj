;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-pin-race-test
  "A pin racing the checkpoint GC ends one of two ways: pinned and kept, or
   the pin refused and the run deleted. Never a successful pin on a deleted
   run. Each interleaving is forced by running the pin on another thread
   at a fixed point of the GC's delete, released and awaited with latches."
  (:require [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private day-ms (* 24 60 60 1000))

(defn- ^{:stratum 0} pin-on-another-thread!
  "Pin run `id` on its own thread and wait for it; returns what pinning
   returned."
  [root id]
  (let [done (CountDownLatch. 1)
        result (atom ::not-run)]
    (doto (Thread. (fn []
                     (reset! result (workflow/pin-checkpoint! id {:checkpoint/root root}))
                     (.countDown done)))
      (.start))
    (.await done 10 TimeUnit/SECONDS)
    @result))

(defn- ^{:stratum 0} assert-consistent
  [root id pin-result]
  (let [dir (io/file root (str id))
        present? (.isDirectory dir)
        pinned? (and present? (fs/exists? (paths/pin-marker-path root id)))
        pin-ok? (string? pin-result)]
    (is (not= ::not-run pin-result) "the pin ran at the forced point")
    (is (or (and pin-ok? pinned?) (and (not pin-ok?) (not present?)))
        (str "pin " (if pin-ok? "succeeded" "failed") ", run "
             (if present? "kept" "deleted")))))

(deftest ^{:stratum 0} a-delete-left-half-done-is-finished-or-undone
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          pinned (random-uuid)
          unpinned (random-uuid)
          aside (fn [id] (io/file root (str ".deleting-" id "-" (random-uuid))))]
      (let [pinned-aside (doto (aside pinned) (.mkdirs))]
        (spit (io/file pinned-aside "pinned") "{}"))
      (doto (aside unpinned) (.mkdirs))
      (workflow/gc-checkpoints! {:checkpoint/root root})
      (is (.isDirectory (io/file root (str pinned))) "a pinned run is moved back")
      (is (not-any? #(.startsWith (.getName ^java.io.File %) ".deleting-")
                    (.listFiles (io/file root)))
          "nothing is left aside"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} expired-run!
  "A run checkpoint under `root` last changed 90 days ago. Returns its id."
  [root]
  (let [id (random-uuid)
        dir (io/file root (str id))]
    (.mkdirs dir)
    (spit (io/file dir "manifest.edn") "{:workflow/id 1}")
    (doseq [f (reverse (file-seq dir))]
      (.setLastModified ^java.io.File f (- (System/currentTimeMillis) (* 90 day-ms))))
    id))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-pin-just-before-the-delete-keeps-the-run
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (expired-run! root)
          pin-result (atom ::not-run)
          delete-run! @#'gc/delete-run!]
      (with-redefs-fn {#'gc/delete-run! (fn [& args]
                                          (reset! pin-result (pin-on-another-thread! root id))
                                          (apply delete-run! args))}
        #(workflow/gc-checkpoints! {:checkpoint/root root}))
      (assert-consistent root id @pin-result)
      (testing "the pin succeeded, so the run is kept"
        (is (string? @pin-result))
        (is (.isDirectory (io/file root (str id))))))))

(deftest ^{:stratum 2} a-pin-after-the-run-is-moved-aside-is-refused
  (fs/with-temp-dir [root {:prefix "pin-race-"}]
    (let [root (str root)
          id (expired-run! root)
          pin-result (atom ::not-run)
          move fs/move]
      (with-redefs [fs/move (fn [& args]
                              (let [moved (apply move args)]
                                (when (= ::not-run @pin-result)
                                  (reset! pin-result (pin-on-another-thread! root id)))
                                moved))]
        (workflow/gc-checkpoints! {:checkpoint/root root}))
      (assert-consistent root id @pin-result))))

(comment ::checkpoint-pin-race)
