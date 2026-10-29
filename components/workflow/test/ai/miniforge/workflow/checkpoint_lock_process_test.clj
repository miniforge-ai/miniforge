;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-lock-process-test
  "The per-run checkpoint lock excludes another process, not only other
   threads: while a second process (bb) holds a run's lock file, a pin and
   the GC in this process wait, then the pin lands and the GC keeps the
   run. Skipped when bb is not on PATH."
  (:require [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private lock-holder-script
  "bb program for a second process: take the FileChannel lock on argv[0],
   print \"locked\", wait for a line on stdin, then exit, which releases
   the lock."
  (str "(let [[lock-file] *command-line-args*"
       "      ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get lock-file (make-array String 0))"
       "           (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE"
       "                                                 java.nio.file.StandardOpenOption/WRITE]))"
       "      held (.tryLock ch)]"
       "  (println (if held \"locked\" \"busy\")) (flush) (read-line))"))

(defn- ^{:stratum 0} deref-bounded [f] (deref f 10000 ::timed-out))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} another-process-holding-the-lock-makes-pin-and-gc-wait
  (if-not (fs/which "bb")
    (println "SKIP cross-process checkpoint lock test: bb is not on PATH")
    (fs/with-temp-dir [dir {:prefix "pin-lock-process-"}]
      (let [root (str dir)
            id (fixture/expired-run! root)
            lock-file (str (checkpoint-lock/lock-file root id))
            _ (fs/create-dirs (fs/parent lock-file))
            holder (bp/process ["bb" "-e" lock-holder-script lock-file]
                               {:in :pipe :out :pipe :err :inherit})
            locked (future (.readLine ^java.io.BufferedReader (io/reader (:out holder))))]
        (try
          (is (= "locked" (deref-bounded locked)) "the other process holds the lock")
          (let [pin (future (workflow/pin-checkpoint! id {:checkpoint/root root}))
                _ (Thread/sleep 200)
                gc-run (future (workflow/gc-checkpoints! {:checkpoint/root root}))]
            (Thread/sleep 300)
            (testing "both wait while the other process holds the lock"
              (is (not (realized? pin)))
              (is (not (realized? gc-run))))
            (doto ^java.io.Writer (io/writer (:in holder)) (.write "go\n") (.flush))
            (testing "then the pin lands and the GC keeps the run"
              (is (string? (deref-bounded pin)))
              (is (= [id] (get-in (deref-bounded gc-run) [:kept :pinned])))
              (is (fs/exists? (fs/path root (str id))))))
          (finally (bp/destroy holder)))))))

(comment ::checkpoint-lock-process)
