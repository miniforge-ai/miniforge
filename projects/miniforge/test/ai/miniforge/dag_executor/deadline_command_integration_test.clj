;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-command-integration-test
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [ai.miniforge.subprocess.interface :as subprocess]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} fixture-budget-ms 500)

(def ^{:stratum 0 :private true} scheduling-ceiling-ms 4000)

(def ^{:stratum 0 :private true} success-budget-ms 5000)

(defn- ^{:stratum 0} shell-command [script & args]
  (into ["/bin/sh" "-c" script "deadline-fixture"] args))

(defn- ^{:stratum 0} pid-live? [pid-file]
  (let [pid (Long/parseLong (slurp (str pid-file)))
        handle (java.lang.ProcessHandle/of pid)]
    (and (.isPresent handle) (.isAlive ^java.lang.ProcessHandle (.get handle)))))

(defn- ^{:stratum 0} record-start! [started start! argv]
  (reset! started (start! argv)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-complete-output-before-the-deadline
  (let [until (deadline/after-ms success-budget-ms)
        reply (command/run! until (shell-command "printf ready; printf warning >&2"))]
    (is (= {:exit 0 :out "ready" :err "warning"} reply))))

(deftest ^{:stratum 1 :integration true} test-expired-command-is-never-started
  (fs/with-temp-dir [directory {}]
    (let [marker (str (fs/path directory "unexpected"))]
      (is (= :deadline (:reason (command/run! (System/nanoTime)
                                             (shell-command "touch \"$1\"" marker)))))
      (is (not (fs/exists? marker))))))

(deftest ^{:stratum 1} test-overflow-is-not-returned-as-complete-output
  (with-redefs [process/drain-stream
                (fn [stream options]
                  (subprocess/drain-stream stream (assoc options :max-bytes 8)))]
    (let [reply (command/run! (deadline/after-ms success-budget-ms)
                              (shell-command "printf 01234567890123456789"))]
      (is (not (zero? (:exit reply))))
      (is (= :output-limit (:reason reply)))
      (is (= "" (:out reply))))))

(deftest ^{:stratum 1 :integration true} test-hung-command-is-killed-and-partial-output-is-dropped
  (fs/with-temp-dir [directory {}]
    (let [pid-file (fs/path directory "pid")
          argv (shell-command "printf '%s' \"$$\" > \"$1\"; printf partial; exec sleep 30" (str pid-file))
          started (System/nanoTime)
          reply (command/run! (deadline/after-ms fixture-budget-ms) argv)
          elapsed-ms (/ (- (System/nanoTime) started) deadline/nanos-per-ms)]
      (is (= :deadline (:reason reply)))
      (is (= "" (:out reply)))
      (is (< elapsed-ms scheduling-ceiling-ms))
      (is (fs/exists? pid-file))
      (is (not (pid-live? pid-file))))))

(deftest ^{:stratum 1 :integration true} test-interruption-kills-the-owned-command-and-preserves-the-flag
  (let [argv (shell-command "exec sleep 30")
        started (atom nil)
        start! (partial record-start! started @#'command/start!)]
    (with-redefs [process/drain-stream
                  (fn [stream options]
                    (.interrupt (Thread/currentThread))
                    (subprocess/drain-stream stream options))]
      (try
        (let [reply (with-redefs-fn {#'command/start! start!}
                      #(command/run! (deadline/after-ms success-budget-ms) argv))
              interrupted? (Thread/interrupted)]
          (is (= :interrupted (:reason reply)))
          (is interrupted?)
          (is (not (.isAlive ^Process @started))))
        (finally (Thread/interrupted))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-command-integration-test))
