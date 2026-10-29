;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.scratch-gc-queue-lock-test
  "The scratch GC queue lock holds for the whole read-modify-write: another
   process cannot take it while the holder reads the queue, nor after a
   second caller in the same process tried for it. Skipped when bb is not
   on PATH."
  (:require [ai.miniforge.dag-executor.scratch-gc-queue :as sut]
            [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private lock-probe-script
  "bb program: try to lock each path in argv, then release; print
   \"busy\" when any is locked elsewhere, else \"free\"."
  (str "(println (if (every? (fn [p]"
       "  (let [ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get p (make-array String 0))"
       "            (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE"
       "                                                  java.nio.file.StandardOpenOption/WRITE]))]"
       "    (try (some? (.tryLock ch)) (finally (.close ch)))))"
       "  *command-line-args*) \"free\" \"busy\"))"))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} another-process-cannot-lock-while-the-queue-is-read
  (if-not (fs/which "bb")
    (println "SKIP cross-process scratch queue lock test: bb is not on PATH")
    (fs/with-temp-dir [dir {:prefix "scratch-queue-lock-"}]
      (let [queue (str (fs/path dir "scratch-gc-queue.edn"))
            probe #(str/trim (:out (shell/sh "bb" "-e" lock-probe-script queue (str queue ".lock"))))]
        (spit queue "[]")
        ;; The read opens and closes a descriptor on the queue file, as
        ;; every GC pass and enqueue does under the lock.
        (is (= "busy" (#'sut/with-queue-lock! queue
                       (fn []
                         (#'sut/read-gc-queue queue)
                         ;; A second caller in this process must not open
                         ;; the sidecar: closing it would drop this lock.
                         (let [other (deref (future (#'sut/with-queue-lock! queue (constantly :entered)))
                                            5000 ::timed-out)]
                           (is (= :scratch-gc-queue/locked (get-in other [:error :code]))))
                         (probe)))))))))

(comment ::scratch-gc-queue-lock)
