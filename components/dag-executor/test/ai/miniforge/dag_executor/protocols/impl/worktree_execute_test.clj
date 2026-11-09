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
(ns ai.miniforge.dag-executor.protocols.impl.worktree-execute-test
  "Tests for the worktree executor's command execution: pipe draining and
   the :timeout-ms deadline."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.process :as runtime-process]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.subprocess.interface :as subprocess]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private scratch-dir
  "Working directory for the shell commands below. They touch no files, so
   the JVM temp dir is enough and always exists."
  (System/getProperty "java.io.tmpdir"))

(def ^{:stratum 0} ^:private stderr-flood-bytes
  "Bytes written to stderr before the child touches stdout. Three times the
   64 KB pipe buffer on Linux and macOS, so a parent that reads stdout to
   EOF before touching stderr blocks here forever."
  (* 3 64 1024))

(def ^{:stratum 0} ^:private regression-guard-ms
  "Upper bound on the flood command. It normally finishes well under a
   second; reaching this bound means the reader deadlocked again, and the
   test fails instead of hanging the suite."
  10000)

(def ^{:stratum 0} ^:private outlives-deadline-cmd
  "Sleeps far longer than any deadline used here. The duration doubles as
   the process-table sentinel for the reap check: its fraction is this
   JVM's PID, so a sleeper from another suite running this test at the
   same time cannot match `outlives-deadline-pattern`. `sleep` takes
   decimal seconds on BSD and GNU.
   A marker in a shell comment would not do: `sh -c` may exec `sleep`
   directly and drop it from the process's arguments."
  (str "sleep 3600." (.pid (java.lang.ProcessHandle/current))))

(def ^{:stratum 0} ^:private aggressive-timeout-ms
  "Deadline handed to the executor. Short so the test is fast; the contract
   under test is `returns at the deadline`, not the production default."
  500)

(def ^{:stratum 0} ^:private pgrep-no-match-exit
  "pgrep(1) exits 1 when no process matched. Asserting this exact code,
   rather than any non-zero, keeps a missing binary or bad flag (exit 2/127)
   from passing as a reaped sleeper."
  1)

(def ^{:stratum 0} ^:private deadline-slack-ms
  "Wall-clock allowance for the timeout path: the deadline, the one-second
   graceful-shutdown wait in `destroy-process-tree!`, and JVM scheduling."
  5000)

(def ^{:stratum 0} ^:private small-capture-bytes
  "A capture limit the oversized command below passes four times over."
  1024)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} small-capture-drain
  "A drain with `small-capture-bytes` as its limit. Calls the component
   directly: the runtime-process name it stands in for is redefined."
  [stream]
  (subprocess/drain-stream stream {:max-bytes small-capture-bytes}))

(def ^{:stratum 1} ^:private oversized-cmd
  "Writes four times `small-capture-bytes` of `x` to stdout and exits 0."
  (str "head -c " (* 4 small-capture-bytes) " /dev/zero | tr '\\0' x"))

(def ^{:stratum 1} ^:private stderr-flood-cmd
  "Floods stderr past the pipe buffer, then writes `done` to stdout. Exits 0
   so the assertions isolate the deadlock from a command failure."
  (str "head -c " stderr-flood-bytes " /dev/zero | tr '\\0' x >&2; echo done"))

(def ^{:stratum 1} ^:private outlives-deadline-pattern
  "`pgrep -f -x` pattern for the sleeper's whole command line. `-x` anchors
   it, so PID 123's sentinel does not also match PID 1234's; the escaped dot
   matches only a dot."
  (str/replace outlives-deadline-cmd "." "\\."))

(deftest ^{:stratum 1} worktree-executor-execute-threads-timeout-test
  (testing "execute! forwards :timeout-ms so the deadline is honoured at the protocol boundary"
    (let [exec (worktree/->WorktreeExecutor {} scratch-dir worktree/default-max-concurrent)
          r    (proto/execute! exec "env-timeout" outlives-deadline-cmd
                               {:workdir scratch-dir :timeout-ms aggressive-timeout-ms})]
      (is (true? (:timed-out? r)))
      (is (= runtime-process/timeout-exit-code (:exit-code (:data r)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} execute-command-kills-process-tree-at-deadline-test
  (testing "a positive timeout bounds the wait, kills the tree and flags the result"
    (let [start (System/currentTimeMillis)
          r     (worktree/execute-command scratch-dir outlives-deadline-cmd {} aggressive-timeout-ms)
          took  (- (System/currentTimeMillis) start)]
      (is (true? (:timed-out? r)) ":timed-out? must sit at the top level of the result")
      (is (= runtime-process/timeout-exit-code (:exit-code (:data r))))
      (is (< took deadline-slack-ms) (str "returned after " took "ms"))
      (is (= pgrep-no-match-exit (:exit (shell/sh "pgrep" "-f" "-x" outlives-deadline-pattern)))
          "the sleeper must be reaped, not orphaned"))))

(deftest ^{:stratum 2} execute-command-drains-stderr-flood-without-deadlock-test
  (testing "a child that fills the stderr pipe before writing stdout still completes"
    (let [run (future (worktree/execute-command scratch-dir stderr-flood-cmd {}))
          r   (deref run regression-guard-ms ::hung)]
      ;; On a regression, interrupt the waiter so its InterruptedException
      ;; path kills the child instead of leaving it to outlive the suite.
      (when (= ::hung r) (future-cancel run))
      (is (not= ::hung r) "execute-command deadlocked on a stderr flood")
      (when (not= ::hung r)
        (is (result/ok? r))
        (is (= 0 (:exit-code (:data r))))
        (is (str/includes? (:stdout (:data r)) "done"))
        (is (= stderr-flood-bytes (count (:stderr (:data r))))
            "every stderr byte must be captured, not just the first pipe buffer")))))

(deftest ^{:stratum 2} execute-command-marks-output-cut-at-the-capture-limit-test
  (testing "stdout past the capture limit keeps its head and says it was cut"
    (with-redefs [runtime-process/drain-stream small-capture-drain]
      (let [r              (worktree/execute-command scratch-dir oversized-cmd {})
            stdout         (:stdout (:data r))
            [kept note]    (map #(apply str %) (split-at small-capture-bytes stdout))]
        (is (result/ok? r))
        (is (= 0 (:exit-code (:data r))) "the command ran to its own end")
        (is (= (apply str (repeat small-capture-bytes \x)) kept))
        (is (str/includes? note (str small-capture-bytes))
            "the text after the kept head is the cut note, naming the limit")))))
