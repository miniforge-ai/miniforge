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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-merge :as dag-merge]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.dag-sub-workflow :as dag-sub-workflow]
   [ai.miniforge.workflow.execution :as exec]
   [ai.miniforge.workflow.execution-step :as execution-step]
   [ai.miniforge.workflow.dag-merge-attempt :as dag-merge-attempt]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
   [ai.miniforge.workflow.runner-environment :as env]
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

(deftest ^{:stratum 0} a-stop-delivered-during-teardown-does-not-interrupt-afterwards
  ;; The stop's callback is already running when the scope ends; it reaches
  ;; the interrupt only after teardown, and must find the scope disarmed.
  (let [cs        (es/create-control-state)
        entered   (promise)
        proceed   (promise)
        stopper   (atom nil)
        interrupt! @#'stop-signal/interrupt!]
    (with-redefs-fn {#'stop-signal/interrupt! (fn [hook]
                                                (deliver entered true)
                                                (deref proceed 5000 nil)
                                                (interrupt! hook))}
      #(stop-signal/interrupting-on-stop
        cs (fn []
             (reset! stopper (future (es/cancel! cs)))
             (deref entered 5000 nil))))
    (deliver proceed true)
    (deref @stopper 5000 nil)
    (let [interrupted? (Thread/interrupted)]
      (is (not interrupted?)))))

(deftest ^{:stratum 0} a-stop-during-coordination-is-left-to-the-next-boundary
  ;; The stop's callback is still in flight when coordination ends; the
  ;; scope stays disarmed, so it cannot interrupt the work after it.
  (let [cs          (es/create-control-state)
        entered     (promise)
        proceed     (promise)
        stopper     (atom nil)
        interrupted (atom nil)
        interrupt!  @#'stop-signal/interrupt!]
    (with-redefs-fn {#'stop-signal/interrupt! (fn [hook]
                                                (deliver entered true)
                                                (deref proceed 5000 nil)
                                                (interrupt! hook))}
      #(stop-signal/interrupting-on-stop
        cs (fn []
             (stop-signal/coordinating
              (fn []
                (reset! stopper (future (es/cancel! cs)))
                (deref entered 5000 nil)))
             (deliver proceed true)
             (deref @stopper 5000 nil)
             (reset! interrupted (Thread/interrupted)))))
    (is (false? @interrupted))))

(deftest ^{:stratum 0} stop-caused-failures-at-phase-boundaries-test
  (let [checkpointed (atom false)
        context      (ctx/create-context {:workflow/id :stop-test :workflow/version "1.0.0"
                                          :workflow/pipeline [{:phase :plan}]}
                                         {:task "Test"} {})
        iterate-with (fn [cs step]
                       (with-redefs-fn {#'monitoring/check-workflow-supervision (constantly {:status :continue})
                                        #'exec/execute-phase-step step
                                        #'env/persist-workspace-at-phase-boundary! (fn [_ _] nil)
                                        #'runner-checkpoint/checkpoint! (fn [c] (reset! checkpointed true) c)}
                         #(runner-iteration/execute-single-iteration [] context {} 0 cs)))]
    (testing "an interruption escaping the phase step (a blocking callback) is the stop, checkpointed"
      (let [cs (es/create-control-state)
            result (iterate-with cs (fn [_ _ & _] (es/cancel! cs) (throw (InterruptedException. "callback"))))]
        (is (some #{:dashboard-stop} (map :type (:execution/errors result))))
        (is (true? @checkpointed))))
    (testing "a cancel landing after the phase completed the run does not undo it"
      (let [cs (es/create-control-state)
            result (iterate-with cs (fn [_ c & _] (es/cancel! cs) (assoc c :execution/status :completed)))]
        (is (= :completed (:execution/status result)))
        (is (not-any? #{:dashboard-stop} (map :type (:execution/errors result))))))))

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
            batch, waited on inside the parent phase's own scope, returns
            promptly with each task's own result"
    (let [cs      (es/create-control-state)
          procs   (atom [])
          started (System/currentTimeMillis)
          _       (cancel-after! cs 300)
          task    (fn [_task ctx]
                    (try (stop-signal/interrupting-on-stop (:control-state ctx)
                                                           #(run-subprocess! procs))
                         (catch InterruptedException _
                           (dag/err :task-execution-failed "stopped" {}))))
          results (stop-signal/interrupting-on-stop
                   cs #(dag-orch/execute-tasks-batch {:a {} :b {}} task {:control-state cs}))]
      (is (< (- (System/currentTimeMillis) started) 10000) "the batch returns promptly")
      (is (= #{:a :b} (set (keys results))) "the parent collected every task's result")
      (is (every? dag/err? (vals results)))
      (is (every? #(dead-within? % 5000) @procs) "every task's subprocess is killed"))))

(deftest ^{:stratum 1} cancel-reaches-the-runner-iteration-phase-test
  (testing "the runner's phase step is interrupted by a cancel; workspace
            persistence and the checkpoint after it run with the interrupt
            cleared, and the run is recorded as stopped"
    (let [cs         (es/create-control-state)
          procs      (atom [])
          persisted  (atom nil)
          checkpoint (atom nil)
          context    (ctx/create-context {:workflow/id :stop-test :workflow/version "1.0.0"
                                          :workflow/pipeline [{:phase :plan}]}
                                         {:task "Test"} {})
          _          (cancel-after! cs 300)]
      (with-redefs-fn {#'monitoring/check-workflow-supervision (constantly {:status :continue})
                       #'exec/execute-phase-step
                       (fn [_ context & _]
                         ;; Re-sets the flag it caught, as some phases do.
                         (try (run-subprocess! procs) context
                              (catch InterruptedException _
                                (.interrupt (Thread/currentThread))
                                (assoc context ::interrupted true))))
                       #'env/persist-workspace-at-phase-boundary!
                       (fn [_ _] (reset! persisted (.isInterrupted (Thread/currentThread))))
                       #'runner-checkpoint/checkpoint!
                       (fn [ctx] (reset! checkpoint (.isInterrupted (Thread/currentThread))) ctx)}
        #(let [result (runner-iteration/execute-single-iteration [] context {} 0 cs)]
           (is (::interrupted result))
           (is (dead-within? (first @procs) 5000))
           (is (false? @persisted))
           (is (false? @checkpoint))
           (is (some #{:dashboard-stop} (map :type (:execution/errors result)))))))))

(deftest ^{:stratum 1} a-cancel-during-the-dag-pause-wait-returns-stopped-test
  (let [cs (es/create-control-state)
        _  (es/pause! cs)
        _  (cancel-after! cs 300)
        logger (log/create-logger {:min-level :error :output :human})]
    (is (true? (stop-signal/interrupting-on-stop
                cs #(#'dag-orch/await-launch-clearance! cs logger))))
    (is (not (Thread/interrupted)))))

(deftest ^{:stratum 1} a-cancel-during-a-parent-merge-stops-the-task-test
  (let [cs (es/create-control-state)
        _  (cancel-after! cs 300)]
    (with-redefs [dag-merge/merge-parent-branches! (fn [_ _] (Thread/sleep 10000) nil)]
      (let [opts (dag-sub-workflow/task-sub-opts {:control-state cs}
                                                 {:task/id :c :task/deps [:a :b]})]
        (is (= :anomalies/dag-merge-stopped (get-in opts [:dag/merge-anomaly :anomaly/category])))
        (is (not (Thread/interrupted)))))))

(deftest ^{:stratum 1} cancel-at-callback-cooldown-and-resolution-boundaries-test
  (testing "an interrupted boundary callback refuses the boundary (the context
            keeps its results) and keeps the interrupt"
    (let [receipt (#'execution-step/invoke (fn [& _] (throw (InterruptedException. "cancelled"))))
          interrupted? (Thread/interrupted)]
      (is (anomaly/anomaly? receipt))
      (is interrupted?)))
  (testing "a cancel's interrupt ends a rate-limit cooldown instead of escaping"
    (let [cs (es/create-control-state)
          _  (cancel-after! cs 200)
          started (System/currentTimeMillis)]
      (stop-signal/interrupting-on-stop cs #(#'dag-orch/stop-aware-sleep cs 10000))
      (is (< (- (System/currentTimeMillis) started) 5000))
      (is (not (Thread/interrupted)))))
  (testing "the merge-resolution agent is not called once the run is stopped"
    (let [attempts (atom 0)
          cs (atom {:stopped false})
          {edit :agent-edit-fn} (dag-merge-attempt/derive-resolution-overrides
                                 {:control-state cs
                                  :dag/resolution-overrides {:agent-edit-fn (fn [& _] (swap! attempts inc))}})]
      (edit "/tmp/wt" {} 0)
      (swap! cs assoc :stopped true)
      (edit "/tmp/wt" {} 1)
      (is (= 1 @attempts)))))
