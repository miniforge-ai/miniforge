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
(ns ai.miniforge.llm.capture-process-cleanup-test
  "The non-streaming CLI path (`default-exec-fn`) must not leave the
   agent CLI, or anything it spawned, running when the calling thread is
   interrupted or the call times out. The fake CLI is a shell that starts
   a 10-minute child and writes both pids to a file in a temp dir."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private pid-file-name
  "File the fake CLI writes the child's pid and then its own pid to."
  "pids")

(def ^{:stratum 0} ^:private expected-pid-count
  "The shell and its child each write a pid."
  2)

(def ^{:stratum 0} ^:private short-timeout-ms
  "Capture timeout for the timeout test: long enough for the pids to be
   written, short enough to keep the test fast."
  1000)

(def ^{:stratum 0} ^:private pid-wait-ms
  "How long to wait for the fake CLI to write both pids (it does so at once)."
  10000)

(def ^{:stratum 0} ^:private return-timeout-ms
  "How long an interrupted `default-exec-fn` gets to return."
  10000)

(def ^{:stratum 0} ^:private death-timeout-ms
  "How long a killed pid may take to leave the process table. A deadline,
   not a fixed sleep: the full wait is only spent when a process leaked."
  5000)

(def ^{:stratum 0} ^:private poll-interval-ms
  "Spacing of the file and liveness polls."
  50)

(defn- ^{:stratum 0} fake-cli
  "A command that starts a 600 s child, records the child's pid and then
   its own in `pid-file`, and waits on the child."
  [pid-file]
  ["sh" "-c" (str "sleep 600 & echo $! > " pid-file "; echo $$ >> " pid-file "; wait")])

(defn- ^{:stratum 0} temp-dir!
  []
  (str (java.nio.file.Files/createTempDirectory
        "llm-capture-cleanup"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} read-pids
  [pid-file]
  (if (.exists (io/file pid-file))
    (into [] (keep parse-long) (str/split-lines (slurp pid-file)))
    []))

(defn- ^{:stratum 0} alive?
  "True when `pid` is a running process. A killed process nobody has
   reaped yet (a zombie) still reports alive on Linux but has no
   command, so it does not count."
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

(defn- ^{:stratum 0} delete-dir!
  [dir]
  (doseq [^java.io.File f (reverse (file-seq (io/file dir)))]
    (.delete f)))

(defn- ^{:stratum 0} exec-outcome
  "Run `default-exec-fn`; return {:result r} or, when it throws,
   {:thrown e :interrupted? <the thread's interrupt flag after the throw>}."
  [cmd opts]
  (try
    {:result (impl/default-exec-fn cmd opts)}
    (catch Throwable e
      {:thrown e
       :interrupted? (.isInterrupted (Thread/currentThread))})))

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

(defn- ^{:stratum 1} start-exec-thread
  "Run `exec-outcome` on a new daemon thread. Returns the thread and a
   promise that receives the outcome."
  [cmd opts]
  (let [outcome (promise)
        thread (Thread. ^Runnable #(deliver outcome (exec-outcome cmd opts)))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread
     :outcome outcome}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} interrupt-kills-capture-process-tree-test
  (testing "interrupting the caller kills the CLI and its child, then rethrows with the flag set"
    (let [dir (temp-dir!)
          pid-file (str (io/file dir pid-file-name))
          {:keys [thread outcome]} (start-exec-thread (fake-cli pid-file) {:workdir dir})
          pids (await-pids pid-file)]
      (try
        (is (= expected-pid-count (count pids)) "the fake CLI wrote both pids")
        (.interrupt ^Thread thread)
        (let [{:keys [thrown interrupted?]} (deref outcome return-timeout-ms nil)]
          (is (instance? InterruptedException thrown)
              "the interrupt still reaches the caller")
          (is (true? interrupted?)
              "the interrupt flag is set again after the cleanup"))
        (is (= [] (survivors pids)) "no process from the tree is left running")
        (finally
          (kill-pids! pids)
          (delete-dir! dir))))))

(deftest ^{:stratum 2} timeout-kills-capture-process-tree-test
  (testing "a capture timeout kills the CLI's child too, not only the CLI"
    (let [dir (temp-dir!)
          pid-file (str (io/file dir pid-file-name))
          capture (#'impl/start-capture-process! (fake-cli pid-file) {:workdir dir})
          pids (await-pids pid-file)]
      (try
        (is (= expected-pid-count (count pids)) "the fake CLI wrote both pids")
        (is (not (zero? (:exit (#'impl/stop-capture-process! capture short-timeout-ms))))
            "the timed-out call reports a failed exit")
        (is (= [] (survivors pids)) "no process from the tree is left running")
        (finally
          (kill-pids! pids)
          (delete-dir! dir))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.capture-process-cleanup-test)
  :leave-this-here)
