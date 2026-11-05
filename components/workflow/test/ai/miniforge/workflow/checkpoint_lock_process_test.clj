;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-lock-process-test
  "The per-run checkpoint lock excludes another process, not only other
   threads: while a second process (bb) holds a run's lock file, a pin and
   the GC in this process wait, then the pin lands and the GC keeps the
   run. A process on the previous version, locking the run's legacy
   lock file, keeps the GC off the run too. Skipped when bb is not on
   PATH."
  (:require [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(def ^{:stratum 0} lock-probe-script
  "bb program: try to lock each path in argv, then release; print
   \"busy\" when any is locked elsewhere, else \"free\"."
  (str "(println (if (every? (fn [p]"
       "  (let [ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get p (make-array String 0))"
       "            (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE"
       "                                                  java.nio.file.StandardOpenOption/WRITE]))]"
       "    (try (some? (.tryLock ch)) (finally (.close ch)))))"
       "  *command-line-args*) \"free\" \"busy\"))"))

(defn- ^{:stratum 0} deref-bounded [f] (deref f 10000 ::timed-out))

(defn- ^{:stratum 0} send-line! [process]
  (doto ^java.io.Writer (io/writer (:in process)) (.write "go\n") (.flush)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} probe-locks
  "What another process sees when it tries to lock `paths`: \"busy\" or
   \"free\"."
  [& paths]
  (str/trim (:out (apply bp/shell {:out :string :err :inherit}
                         "bb" "-e" lock-probe-script (map str paths)))))

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

(deftest ^{:stratum 1} an-old-process-holding-the-legacy-lock-keeps-gc-off-the-run
  ;; During an upgrade a process on the previous version pins under
  ;; <run-id>.lock, not the slot: the GC must not delete that run.
  (if-not (fs/which "bb")
    (println "SKIP cross-process checkpoint lock test: bb is not on PATH")
    (fs/with-temp-dir [dir {:prefix "pin-lock-legacy-"}]
      (fs/with-temp-dir [events-dir {:prefix "pin-lock-legacy-events-"}]
        (let [root (str dir)
              id (fixture/expired-run! root)
              legacy (str (checkpoint-lock/legacy-lock-file root id))
              gc! #(workflow/gc-checkpoints! {:checkpoint/root root :events-dir (fs/file events-dir)})
              _ (fs/create-dirs (fs/parent legacy))
              holder (bp/process ["bb" "-e" lock-holder-script legacy]
                                 {:in :pipe :out :pipe :err :inherit})]
          (try
            (is (= "locked" (deref-bounded (future (.readLine ^java.io.BufferedReader
                                                              (io/reader (:out holder)))))))
            (testing "while the old process holds the legacy lock: kept"
              (let [result (with-redefs [checkpoint-lock/lock-timeout-ms 300] (gc!))]
                (is (empty? (:deleted result)))
                (is (= 1 (count (:failures result)))))
              (is (fs/exists? (fs/path root (str id)))))
            (send-line! holder)
            (deref-bounded (future (bp/check holder)))
            (testing "once it lets go: collected"
              (is (= [id] (:deleted (gc!)))))
            (finally (bp/destroy holder))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} another-process-cannot-lock-while-the-callback-runs
  ;; POSIX record locks belong to the process: closing any descriptor of
  ;; the locked file would release the lock while the callback still runs.
  (if-not (fs/which "bb")
    (println "SKIP cross-process checkpoint lock test: bb is not on PATH")
    (fs/with-temp-dir [dir {:prefix "pin-lock-held-"}]
      (let [root (str dir)
            id (random-uuid)]
        (is (= "busy" (checkpoint-lock/with-run-lock
                       root id #(probe-locks (checkpoint-lock/lock-file root id)))))
        (is (= "free" (probe-locks (checkpoint-lock/lock-file root id))))))))

(comment ::checkpoint-lock-process)
