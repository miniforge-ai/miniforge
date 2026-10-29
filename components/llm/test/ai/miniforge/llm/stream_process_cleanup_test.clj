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
(ns ai.miniforge.llm.stream-process-cleanup-test
  "`stream-exec-fn` must not leave the agent CLI, or anything it spawned,
   running after an abnormal exit: the calling thread interrupted (the
   implement watchdog's kill), a timeout, a network drop, or a process
   that closes stdout and never exits. Each test runs a real shell that
   starts a 10-minute child and reports both pids, then checks that
   neither pid is alive afterwards."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private spawning-shell-cmd
  "A shell that starts a long-lived child, prints both pids, then waits.
   The trailing `wait` keeps the shell itself alive (a shell may exec a
   lone trailing command in its own place, leaving no child to leak).
   600 s outlives any test, so a pid still alive at the end was leaked."
  ["sh" "-c" "sleep 600 & echo \"child $!\"; echo \"shell $$\"; wait"])

(def ^{:stratum 0} ^:private stdout-closing-shell-cmd
  "Like `spawning-shell-cmd`, but the shell then closes stdout and stderr
   and keeps running: the stream reaches EOF while the process never
   exits. The child writes to /dev/null so it does not hold the pipe."
  ["sh" "-c" (str "sleep 600 >/dev/null 2>&1 & echo \"child $!\"; "
                  "echo \"shell $$\"; exec >&- 2>&-; wait")])

(def ^{:stratum 0} ^:private pid-line-pattern
  "Matches the `child <pid>` / `shell <pid>` lines the commands print."
  #"(child|shell) (\d+)")

(def ^{:stratum 0} ^:private reported-pid-count
  "Both the shell and its child report a pid."
  2)

(def ^{:stratum 0} ^:private pid-report-timeout-ms
  "How long to wait for the shell to print both pids (it does so at once)."
  10000)

(def ^{:stratum 0} ^:private return-timeout-ms
  "How long `stream-exec-fn` gets to return once interrupted, timed out,
   or cut by a network drop. A call stuck past this is a hang."
  10000)

(def ^{:stratum 0} ^:private death-timeout-ms
  "How long a killed pid may take to leave the process table. A deadline,
   not a fixed sleep: a loaded runner can be slow to reap, and the full
   wait is only spent when a process really leaked."
  5000)

(def ^{:stratum 0} ^:private poll-interval-ms
  "Spacing of the liveness checks inside `death-timeout-ms`."
  50)

(def ^{:stratum 0} ^:private short-max-total-ms
  "Hard limit for the timeout test: long enough for the pids to print,
   short enough to keep the test fast."
  1500)

(def ^{:stratum 0} ^:private short-join-timeout-ms
  "Stands in for the 10-minute `process-join-timeout-ms` so the wait for
   a process that closed stdout gives up quickly."
  500)

(def ^{:stratum 0} ^:private drop-probe-interval-ms
  "Network-monitor probe spacing for the drop test. Three failed probes
   fire the drop at ~600 ms, well after the pids are printed."
  200)

(def ^{:stratum 0} ^:private drop-failure-threshold
  "Consecutive failed probes that declare the drop."
  3)

(def ^{:stratum 0} ^:private drop-test-max-total-ms
  "Bounds the drop test when the drop does not end the stream (a child
   left holding stdout keeps it open until this limit)."
  5000)

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

(defn- ^{:stratum 0} stream-outcome
  "Run `stream-exec-fn`, returning {:result r} or, when it throws,
   {:thrown e :interrupted? <the thread's interrupt flag after the throw>}."
  [cmd on-line opts]
  (try
    {:result (impl/stream-exec-fn cmd on-line opts)}
    (catch Throwable e
      {:thrown e
       :interrupted? (.isInterrupted (Thread/currentThread))})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} record-pid!
  "Store the pid from a `child`/`shell` line; deliver `reported` once
   both are in."
  [pids reported line]
  (when-let [[_ role pid] (re-matches pid-line-pattern line)]
    (when (= reported-pid-count (count (swap! pids assoc role (parse-long pid))))
      (deliver reported true))))

(defn- ^{:stratum 1} survivors
  "The pids in `pids` still alive when all have died or
   `death-timeout-ms` has passed, whichever is first."
  [pids]
  (let [deadline (+ (System/currentTimeMillis) death-timeout-ms)]
    (loop []
      (let [alive (filterv alive? pids)]
        (if (or (empty? alive) (> (System/currentTimeMillis) deadline))
          alive
          (do (Thread/sleep poll-interval-ms)
              (recur)))))))

(defn- ^{:stratum 1} start-stream-thread
  "Run `stream-outcome` on a new thread, so a hung call fails the test
   instead of blocking it. Returns the thread and a promise that receives
   the outcome."
  [cmd on-line opts]
  (let [outcome (promise)
        thread (Thread. ^Runnable #(deliver outcome (stream-outcome cmd on-line opts)))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread
     :outcome outcome}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} interrupt-kills-process-tree-test
  (testing "interrupting the calling thread kills the CLI and its children, then rethrows with the flag set"
    (let [pids (atom {})
          reported (promise)
          {:keys [thread outcome]} (start-stream-thread
                                    spawning-shell-cmd
                                    (partial record-pid! pids reported)
                                    {:progress-monitor (pm/create-progress-monitor {})})]
      (try
        (is (true? (deref reported pid-report-timeout-ms false))
            "the shell printed both pids")
        (.interrupt ^Thread thread)
        (let [{:keys [thrown interrupted?]} (deref outcome return-timeout-ms nil)]
          (is (instance? InterruptedException thrown)
              "the call returns promptly and the interrupt still reaches the caller")
          (is (true? interrupted?)
              "the interrupt flag is set again after the cleanup"))
        (is (= [] (survivors (vals @pids)))
            "no process from the tree is left running")
        (finally
          (kill-pids! (vals @pids)))))))

(deftest ^{:stratum 2} timeout-kills-process-tree-test
  (testing "a hard timeout returns promptly and kills the shell's child too, not only the shell"
    (let [pids (atom {})
          {:keys [outcome]} (start-stream-thread
                             spawning-shell-cmd
                             (partial record-pid! pids (promise))
                             {:progress-monitor (pm/create-progress-monitor
                                                 {:max-total-ms short-max-total-ms})})]
      (try
        (let [{:keys [result]} (deref outcome return-timeout-ms nil)]
          (is (= reported-pid-count (count @pids)))
          (is (some? (:timeout result)) "the call returned with the timeout"))
        (is (= [] (survivors (vals @pids)))
            "no process from the tree is left running")
        (finally
          (kill-pids! (vals @pids)))))))

(deftest ^{:stratum 2} network-drop-kills-process-tree-test
  (testing "a network drop kills the whole tree, so no child keeps the stream open"
    (let [pids (atom {})
          {:keys [outcome]} (start-stream-thread
                             spawning-shell-cmd
                             (partial record-pid! pids (promise))
                             {:progress-monitor (pm/create-progress-monitor
                                                 {:max-total-ms drop-test-max-total-ms})
                              :backend-key :claude
                              :network-monitor-opts {:probe-interval-ms drop-probe-interval-ms
                                                     :failure-threshold drop-failure-threshold
                                                     :probe-fn (constantly false)}})]
      (try
        (let [{:keys [result]} (deref outcome return-timeout-ms nil)]
          (is (= reported-pid-count (count @pids)))
          (is (= :network-drop (get-in result [:timeout :type]))
              "the call returned with the network-drop timeout"))
        (is (= [] (survivors (vals @pids)))
            "no process from the tree is left running")
        (finally
          (kill-pids! (vals @pids)))))))

(deftest ^{:stratum 2} process-alive-after-eof-is-killed-test
  (testing "a process that closes stdout but does not exit is killed when the join wait runs out"
    (let [pids (atom {})]
      (try
        (with-redefs-fn {#'impl/process-join-timeout-ms (constantly short-join-timeout-ms)}
          (fn []
            (let [{:keys [outcome]} (start-stream-thread
                                     stdout-closing-shell-cmd
                                     (partial record-pid! pids (promise))
                                     {:progress-monitor (pm/create-progress-monitor {})})
                  {:keys [result]} (deref outcome return-timeout-ms nil)]
              (is (= reported-pid-count (count @pids)))
              (is (= -1 (:exit result))
                  "the call returned, reporting a process that had not exited"))))
        (is (= [] (survivors (vals @pids)))
            "no process from the tree is left running")
        (finally
          (kill-pids! (vals @pids)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.stream-process-cleanup-test)
  :leave-this-here)
