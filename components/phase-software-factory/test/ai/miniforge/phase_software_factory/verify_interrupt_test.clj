;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.phase-software-factory.verify-interrupt-test
  "Interrupting the thread that runs `run-tests!` must kill the test
   process tree and keep the interrupt visible to the caller. The test
   command is a shell that starts a 10-minute child and writes both pids
   to a file, so the test can check neither is left running."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase-software-factory.verify :as verify]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private pid-file-name
  "File the test command writes the child's and the shell's pids to."
  "pids")

(def ^{:stratum 0} ^:private expected-pid-count
  "The shell and its child each write a pid."
  2)

(def ^{:stratum 0} ^:private run-deadline-ms
  "`run-tests!` deadline far past the test's own waits, so only the
   interrupt can end the run."
  (* 10 60 1000))

(def ^{:stratum 0} ^:private pid-wait-ms
  "How long to wait for the shell to write both pids (it does so at once)."
  10000)

(def ^{:stratum 0} ^:private return-timeout-ms
  "How long an interrupted `run-tests!` gets to return."
  10000)

(def ^{:stratum 0} ^:private death-timeout-ms
  "How long a killed pid may take to leave the process table. A deadline,
   not a fixed sleep: the full wait is only spent when a process leaked."
  5000)

(def ^{:stratum 0} ^:private poll-interval-ms
  "Spacing of the file and liveness polls."
  50)

(defn- ^{:stratum 0} spawning-test-cmd
  "A test command that starts a 600 s child, records the child's pid and
   then its own, and waits on the child."
  [pid-file]
  (str "sleep 600 & echo $! > " pid-file "; echo $$ >> " pid-file "; wait"))

(defn- ^{:stratum 0} read-pids
  [pid-file]
  (if (.exists (io/file pid-file))
    (into [] (keep parse-long) (str/split-lines (slurp pid-file)))
    []))

(defn- ^{:stratum 0} alive?
  "True when `pid` is a running process. A killed process nobody has
   reaped yet (a zombie: its shell died first and the container's PID 1
   does not reap) still reports alive on Linux but has no command, so it
   does not count, as in verify_test's `descendant-sleeps`."
  [pid]
  (let [handle (java.lang.ProcessHandle/of pid)]
    (and (.isPresent handle)
         (let [^java.lang.ProcessHandle process (.get handle)]
           (and (.isAlive process)
                (.isPresent (.command (.info process))))))))

(defn- ^{:stratum 0} kill-pids!
  "Test cleanup, so a failing run does not leave 10-minute sleeps behind."
  [pids]
  (doseq [pid pids]
    (let [handle (java.lang.ProcessHandle/of pid)]
      (when (.isPresent handle)
        (.destroyForcibly ^java.lang.ProcessHandle (.get handle))))))

(defn- ^{:stratum 0} run-tests-outcome
  "Run `run-tests!`; return its result and the thread's interrupt flag
   right after it returned."
  [worktree-path test-cmd timeout-ms]
  (let [result (verify/run-tests! worktree-path
                                  :test-cmd test-cmd
                                  :timeout-ms timeout-ms)]
    {:result result
     :interrupted? (.isInterrupted (Thread/currentThread))}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} await-pids
  "The pids in `pid-file` once both are written, or what is there when
   `pid-wait-ms` runs out."
  [pid-file]
  (let [deadline (+ (System/currentTimeMillis) pid-wait-ms)]
    (loop []
      (let [pids (read-pids pid-file)]
        (if (or (= expected-pid-count (count pids))
                (> (System/currentTimeMillis) deadline))
          pids
          (do (Thread/sleep poll-interval-ms)
              (recur)))))))

(defn- ^{:stratum 1} survivors
  "The pids still alive when all have died or `death-timeout-ms` has
   passed, whichever is first."
  [pids]
  (let [deadline (+ (System/currentTimeMillis) death-timeout-ms)]
    (loop []
      (let [alive (filterv alive? pids)]
        (if (or (empty? alive) (> (System/currentTimeMillis) deadline))
          alive
          (do (Thread/sleep poll-interval-ms)
              (recur)))))))

(defn- ^{:stratum 1} start-run-thread
  "Run `run-tests-outcome` on a new daemon thread. Returns the thread and
   a promise that receives the outcome."
  [worktree-path test-cmd]
  (let [outcome (promise)
        thread (Thread. ^Runnable #(deliver outcome
                                            (run-tests-outcome worktree-path test-cmd
                                                               run-deadline-ms)))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread
     :outcome outcome}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} interrupted-run-kills-test-process-tree-test
  (testing "an interrupted run kills the shell and its child and keeps the interrupt flag set"
    (let [dir (str (java.nio.file.Files/createTempDirectory
                    "verify-interrupt"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
          pid-file (str (io/file dir pid-file-name))
          {:keys [thread outcome]} (start-run-thread dir (spawning-test-cmd pid-file))
          pids (await-pids pid-file)]
      (try
        (is (= expected-pid-count (count pids)) "the shell wrote both pids")
        (.interrupt ^Thread thread)
        (let [{:keys [result interrupted?]} (deref outcome return-timeout-ms nil)]
          (is (false? (:passed? result)))
          (is (true? (:interrupted? result))
              "the result says the run was interrupted")
          (is (true? interrupted?)
              "the interrupt flag is set again after the cleanup"))
        (is (= [] (survivors pids))
            "no process from the test run is left running")
        (finally
          (kill-pids! pids)
          (io/delete-file pid-file true)
          (io/delete-file dir true))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-interrupt-test)
  :leave-this-here)
