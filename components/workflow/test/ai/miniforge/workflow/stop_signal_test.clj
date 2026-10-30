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
(ns ai.miniforge.workflow.stop-signal-test
  "Recovery scenario C4: a cancel reaches work in flight. The work is a
   real subprocess the waiting thread kills when interrupted, the contract
   both LLM paths keep for the agent CLI."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
   [ai.miniforge.workflow.runner-iteration :as runner-iteration]
   [ai.miniforge.workflow.stop-signal :as stop-signal]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-subprocess!
  "Start a 60 s subprocess, record it in `procs`, and wait on it; on an
   interrupt, kill it and rethrow."
  [procs]
  (let [p (.start (ProcessBuilder. ^java.util.List ["sleep" "60"]))]
    (swap! procs conj p)
    (try
      (.waitFor p)
      :finished
      (catch InterruptedException e
        (.destroyForcibly p)
        (throw e)))))

(defn- ^{:stratum 0} dead-within? [^Process p ms]
  (.waitFor p ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn- ^{:stratum 0} cancel-after! [control-state ms]
  (future (Thread/sleep ms) (es/cancel! control-state)))

(deftest ^{:stratum 0} on-stop-fires-once-test
  (let [cs    (es/create-control-state)
        calls (atom 0)
        remove-hook (stop-signal/on-stop! cs #(swap! calls inc))]
    (es/pause! cs)
    (is (zero? @calls) "a pause is not a stop")
    (es/cancel! cs)
    (es/cancel! cs)
    (is (= 1 @calls))
    (remove-hook)
    (testing "already stopped: fires at once"
      (let [now (atom 0)]
        ((stop-signal/on-stop! cs #(swap! now inc)))
        (is (= 1 @now))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} cancel-interrupts-the-running-phase-test
  (let [cs     (es/create-control-state)
        procs  (atom [])
        _      (cancel-after! cs 300)
        thrown (try (stop-signal/interrupting-on-stop cs #(run-subprocess! procs))
                    (catch InterruptedException _ :interrupted))]
    (is (= :interrupted thrown) "the phase's wait is interrupted")
    (is (dead-within? (first @procs) 5000) "its subprocess is killed")
    (is (not (Thread/interrupted)) "the interrupt it caused is cleared afterwards")))

(deftest ^{:stratum 1} cancel-reaches-in-flight-dag-tasks-test
  (testing "a DAG batch's tasks run sub-workflows that share the run's control
            state, so a cancel interrupts each task's running phase; the
            batch returns promptly with each task's own result"
    (let [cs      (es/create-control-state)
          procs   (atom [])
          started (System/currentTimeMillis)
          _       (cancel-after! cs 300)
          task    (fn [_task ctx]
                    (try (stop-signal/interrupting-on-stop (:control-state ctx)
                                                           #(run-subprocess! procs))
                         (catch InterruptedException _
                           (dag/err :task-execution-failed "stopped" {}))))
          results (dag-orch/execute-tasks-batch {:a {} :b {}} task {:control-state cs})]
      (is (< (- (System/currentTimeMillis) started) 10000) "the batch returns promptly")
      (is (every? dag/err? (vals results)))
      (is (every? #(dead-within? % 5000) @procs) "every task's subprocess is killed"))))

(deftest ^{:stratum 1} cancel-reaches-the-runner-iteration-phase-test
  (testing "the runner's phase step is interrupted by a cancel, and the
            checkpoint after it runs with the interrupt cleared"
    (let [cs         (es/create-control-state)
          procs      (atom [])
          checkpoint (atom nil)
          _          (cancel-after! cs 300)]
      (with-redefs-fn {#'ai.miniforge.workflow.runner-iteration/execute-supervised
                       (fn [_ context _ _]
                         (try (run-subprocess! procs) context
                              (catch InterruptedException _ (assoc context ::interrupted true))))
                       #'runner-checkpoint/checkpoint!
                       (fn [ctx] (reset! checkpoint (.isInterrupted (Thread/currentThread))) ctx)}
        #(let [result (runner-iteration/execute-single-iteration [] {} {} 0 cs)]
           (is (::interrupted result))
           (is (dead-within? (first @procs) 5000))
           (is (false? @checkpoint)))))))
