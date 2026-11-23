;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-command-integration-test
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process-shutdown :as shutdown]
            [ai.miniforge.subprocess.interface :as subprocess]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} fixture-budget-ms
  "Expire hung fixtures quickly while allowing their shell to start."
  500)

(def ^{:stratum 0 :private true} scheduling-ceiling-ms
  "Allow the command budget, both shutdown graces and CI scheduling overhead."
  4000)

(def ^{:stratum 0 :private true} success-budget-ms
  "Keep ordinary success and fault-injection tests away from incidental expiry."
  5000)

(def ^{:stratum 0 :private true} held-pipe-ceiling-ms
  "Permit bounded capture and cleanup, but not the descendant's full lifetime."
  2500)

(def ^{:stratum 0 :private true} fixture-shell
  "The POSIX shell used only by disposable process integration fixtures."
  "/bin/sh")

(def ^{:stratum 0 :private true} overflow-limit-bytes
  "Capture less than the overflow fixture emits, forcing its bounded-output path."
  8)

(def ^{:stratum 0 :private true} complete-output-script
  "Emit distinct stdout and stderr so complete capture must retain both."
  "printf ready; printf warning >&2")

(def ^{:stratum 0 :private true} marker-script
  "Record an expired command's forbidden launch in a fixture-owned directory."
  "touch \"$1\"")

(def ^{:stratum 0 :private true} overflow-script
  "Emit more bytes than the fixture capture limit without starting another child."
  "printf 01234567890123456789")

(def ^{:stratum 0 :private true} ready-script
  "Complete the process while a substituted capture future remains unfinished."
  "printf ready")

(def ^{:stratum 0 :private true} hung-script
  "Stay alive beyond the command budget unless owned-process cleanup kills it."
  "exec sleep 30")

(def ^{:stratum 0 :private true} term-resistant-script
  "Require forced termination rather than graceful SIGTERM acknowledgement."
  "trap '' TERM; while :; do :; done")

(def ^{:stratum 0 :private true} held-pipe-script
  "Hold stdout past the latency ceiling; delay parent exit until readers attach."
  "sleep 3 & printf '%s' \"$!\" > \"$1\"; printf inherited; sleep 0.1")

(def ^{:stratum 0 :private true} recorded-hang-script
  "Retain the direct PID and partial output to prove timeout kills and discards."
  "printf '%s' \"$$\" > \"$1\"; printf partial; exec sleep 30")

(defn- ^{:stratum 0} recorded-handle [pid-file]
  (let [pid (Long/parseLong (slurp (str pid-file)))
        handle (java.lang.ProcessHandle/of pid)]
    (.orElse handle nil)))

(defn- ^{:stratum 0} record-start! [started start! argv]
  (reset! started (start! argv)))

(defn- ^{:stratum 0} stalled-drain [stream options]
  (assoc (subprocess/drain-stream stream options) :future (future @(promise))))

(defn- ^{:stratum 0} interrupted-drain [stream options]
  (.interrupt (Thread/currentThread))
  (subprocess/drain-stream stream options))

(defn- ^{:stratum 0} interrupt-first-wait [waits wait! child until]
  (let [first? (empty? @waits)]
    (swap! waits conj until)
    (if first? (throw (InterruptedException.)) (wait! child until))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} shell-command [script & args]
  (into [fixture-shell "-c" script "deadline-fixture"] args))

(defn- ^{:stratum 1} pid-live? [pid-file]
  (when-let [handle (recorded-handle pid-file)]
    (.isAlive ^java.lang.ProcessHandle handle)))

(defn- ^{:stratum 1} kill-recorded-fixture! [pid-file]
  (when (fs/exists? pid-file)
    (when-let [handle (recorded-handle pid-file)]
      (.destroyForcibly ^java.lang.ProcessHandle handle))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-complete-output-before-the-deadline
  (let [until (deadline/after-ms success-budget-ms)
        reply (command/run! until (shell-command complete-output-script))]
    (is (= {:exit 0
            :out "ready"
            :err "warning"} reply))))

(deftest ^{:stratum 2 :integration true} test-expired-command-is-never-started
  (fs/with-temp-dir [directory {}]
    (let [marker (str (fs/path directory "unexpected"))]
      (is (= :deadline (:reason (command/run! (System/nanoTime)
                                             (shell-command marker-script marker)))))
      (is (not (fs/exists? marker))))))

(deftest ^{:stratum 2} test-overflow-is-not-returned-as-complete-output
  (with-redefs [process/drain-stream
                (fn [stream options]
                  (subprocess/drain-stream stream (assoc options :max-bytes overflow-limit-bytes)))]
    (let [reply (command/run! (deadline/after-ms success-budget-ms)
                              (shell-command overflow-script))]
      (is (not (zero? (:exit reply))))
      (is (= :output-limit (:reason reply)))
      (is (= "" (:out reply))))))

(deftest ^{:stratum 2} test-output-capture-shares-the-command-deadline
  (with-redefs [process/drain-stream stalled-drain]
    (let [reply (command/run! (deadline/after-ms fixture-budget-ms) (shell-command ready-script))]
      (is (= :deadline (:reason reply)))
      (is (= "" (:out reply))))))

(deftest ^{:stratum 2 :integration true} test-interruption-kills-the-owned-command-and-preserves-the-flag
  (let [argv (shell-command hung-script)
        started (atom nil)
        start! (partial record-start! started @#'command/start!)]
    (with-redefs [process/drain-stream interrupted-drain]
      (try
        (let [reply (with-redefs-fn {#'command/start! start!}
                      #(command/run! (deadline/after-ms success-budget-ms) argv))
              interrupted? (Thread/interrupted)]
          (is (= :interrupted (:reason reply)))
          (is interrupted?)
          (is (not (.isAlive ^Process @started))))
        (finally (Thread/interrupted))))))

(deftest ^{:stratum 2 :integration true} test-cancellation-during-grace-still-forces-termination
  (let [started (atom nil)
        waits (atom [])
        start! (partial record-start! started @#'command/start!)
        wait! (partial interrupt-first-wait waits @#'shutdown/wait-once!)
        overrides {#'command/start! start!
                   #'shutdown/wait-once! wait!}
        argv (shell-command term-resistant-script)]
    (try
      (let [reply (with-redefs-fn overrides #(command/run! (deadline/after-ms fixture-budget-ms) argv))
            interrupted? (Thread/interrupted)]
        (is (= :interrupted (:reason reply)))
        (is interrupted?)
        (is (not (.isAlive ^Process @started)))
        (is (= (first @waits) (second @waits))))
      (finally
        (Thread/interrupted)
        (.destroyForcibly ^Process @started)))))

(deftest ^{:stratum 2 :integration true} test-real-descendant-held-pipe-cannot-extend-capture
  (fs/with-temp-dir [directory {}]
    (let [pid-file (fs/path directory "descendant")
          argv (shell-command held-pipe-script (str pid-file))
          started (System/nanoTime)]
      (try
        (let [reply (command/run! (deadline/after-ms fixture-budget-ms) argv)
              elapsed-ms (/ (- (System/nanoTime) started) deadline/nanos-per-ms)]
          (is (= :deadline (:reason reply)))
          (is (= "" (:out reply)))
          (is (< elapsed-ms held-pipe-ceiling-ms)))
        (finally (kill-recorded-fixture! pid-file))))))

(deftest ^{:stratum 2 :integration true} test-hung-command-is-killed-and-partial-output-is-dropped
  (fs/with-temp-dir [directory {}]
    (let [pid-file (fs/path directory "pid")
          argv (shell-command recorded-hang-script (str pid-file))
          started (System/nanoTime)
          reply (command/run! (deadline/after-ms fixture-budget-ms) argv)
          elapsed-ms (/ (- (System/nanoTime) started) deadline/nanos-per-ms)]
      (is (= :deadline (:reason reply)))
      (is (= "" (:out reply)))
      (is (< elapsed-ms scheduling-ceiling-ms))
      (is (fs/exists? pid-file))
      (is (not (pid-live? pid-file))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-command-integration-test))
