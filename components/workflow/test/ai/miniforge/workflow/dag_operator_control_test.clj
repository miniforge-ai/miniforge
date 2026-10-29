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
(ns ai.miniforge.workflow.dag-operator-control-test
  "Operator control while the DAG executor runs. `mf workflow pause` and
   `mf workflow cancel` flip the run's control-state atom; the DAG loop
   must hold or stop launching batches when they do, and every task's
   sub-workflow must share that atom so its own runner sees the same
   flags at its phase boundaries. The control states here come from the
   event-stream constructors and are flipped with the same `pause!` /
   `resume!` / `cancel!` the operator uses."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.runner-defaults :as runner-defaults]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private root-id (random-uuid))

(def ^{:stratum 0} ^:private left-id (random-uuid))

(def ^{:stratum 0} ^:private right-id (random-uuid))

(def ^{:stratum 0} ^:private poll-ms
  "Pause-poll interval for these tests, in place of the runner's 1 s."
  5)

(def ^{:stratum 0} ^:private wait-limit-ms
  "Upper bound on any wait, so a regression fails instead of hanging."
  5000)

(def ^{:stratum 0} ^:private settle-ms
  "How long a paused run is watched for a launch that must not happen."
  150)

(defn- ^{:stratum 0} plan-task
  [id description dependencies]
  {:task/id           id
   :task/description  description
   :task/type         :implement
   :task/dependencies dependencies})

(defn- ^{:stratum 0} recording-task-fn
  "Stand-in for `execute-single-task`: records each launched task id,
   runs `on-launch` with it (where a test flips the control state, as an
   operator would while that task runs), and succeeds."
  [launched on-launch]
  (fn [task-def _context]
    (let [task-id (:task/id task-def)]
      (swap! launched conj task-id)
      (on-launch task-id)
      (dag/ok {:task-id task-id
               :artifacts []
               :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}))))

(defn- ^{:stratum 0} stopping-task-fn
  "Stand-in for `execute-single-task` where the operator cancels while
   the task runs and its sub-workflow, sharing the parent's control
   state, stops at its next phase boundary: the task comes back failed
   with the runner's stop message."
  [launched control]
  (fn [task-def _context]
    (let [task-id (:task/id task-def)]
      (swap! launched conj task-id)
      (es/cancel! control)
      (dag/err :task-execution-failed (messages/t :status/stopped-control)
               {:task-id task-id}))))

(defn- ^{:stratum 0} logged?
  [entries event]
  (boolean (some #(= event (:log/event %)) @entries)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private fan-out-plan
  "root runs alone in the first batch; left and right both depend on it,
   so they form the second batch."
  {:plan/id (random-uuid)
   :plan/name "fan-out"
   :plan/tasks [(plan-task root-id "root" [])
                (plan-task left-id "left" [root-id])
                (plan-task right-id "right" [root-id])]})

(def ^{:stratum 1} ^:private chain-plan
  "left depends on root; nothing else can run while root is in flight."
  {:plan/id (random-uuid)
   :plan/name "chain"
   :plan/tasks [(plan-task root-id "root" [])
                (plan-task left-id "left" [root-id])]})

(defn- ^{:stratum 1} await-condition
  "Poll `pred` until it holds or `wait-limit-ms` passes; returns whether it held."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) wait-limit-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep (long poll-ms)) (recur))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} cancel-after-first-batch-launches-nothing-more-test
  (doseq [[shape ->context]
          {"mf run plan.edn: control state on the context"
           (fn [logger control] {:logger logger :control-state control})
           "workflow runner: control state in :execution/opts"
           (fn [logger control] {:logger logger :execution/opts {:control-state control}})}]
    (testing shape
      (let [control (es/create-control-state)
            launched (atom [])
            [logger entries] (log/collecting-logger)
            cancel-on-root (fn [task-id] (when (= root-id task-id) (es/cancel! control)))
            result (with-redefs [dag-orch/execute-single-task
                                 (recording-task-fn launched cancel-on-root)]
                     (dag-orch/execute-plan-as-dag fan-out-plan (->context logger control)))]
        (is (= [root-id] @launched)
            "the cancel lands while root runs; left and right must never launch")
        (is (true? (:cancelled? result)))
        (is (false? (:success? result)))
        (is (= 1 (:tasks-completed result)) "root's finished work is still reported")
        (is (= 0 (:tasks-failed result)))
        (is (= 2 (:tasks-unreached result)) "the tasks never launched are unreached")
        (is (= [(keyword (str "dag-task-" root-id))] (:sub-workflow-ids result)))
        (is (= :anomalies.dashboard/stop (anomaly/subtype (:error result)))
            "same stop anomaly the runner raises at a phase boundary")
        (is (logged? entries :dag/cancelled))))))

(deftest ^{:stratum 2} cancel-that-stops-the-in-flight-chain-is-reported-as-a-cancel-test
  (testing "root's sub-workflow stops on the cancel and fails, which fails
            left by dependency, so no task is left ready. The run was still
            cut short by the operator and must say so."
    (let [control (es/create-control-state)
          launched (atom [])
          [logger _] (log/collecting-logger)
          result (with-redefs [dag-orch/execute-single-task (stopping-task-fn launched control)]
                   (dag-orch/execute-plan-as-dag chain-plan {:logger logger :control-state control}))]
      (is (= [root-id] @launched))
      (is (true? (:cancelled? result)))
      (is (false? (:success? result)))
      (is (= 0 (:tasks-completed result)))
      (is (= 2 (:tasks-failed result))
          "root failed on the stop; left is failed by dependency, as in any run")
      (is (= :anomalies.dashboard/stop (anomaly/subtype (:error result)))))))

(deftest ^{:stratum 2} cancel-after-every-task-completed-reports-the-completed-run-test
  (testing "a cancel that lands while the last batch runs cuts nothing short:
            every task finished, so the result is the ordinary completed one"
    (let [control (es/create-control-state)
          launched (atom [])
          [logger _] (log/collecting-logger)
          cancel-on-right (fn [task-id] (when (= right-id task-id) (es/cancel! control)))
          result (with-redefs [dag-orch/execute-single-task
                               (recording-task-fn launched cancel-on-right)]
                   (dag-orch/execute-plan-as-dag fan-out-plan {:logger logger :control-state control}))]
      (is (= #{root-id left-id right-id} (set @launched)))
      (is (true? (:success? result)))
      (is (not (:cancelled? result)))
      (is (= 3 (:tasks-completed result))))))

(deftest ^{:stratum 2} cancel-before-start-launches-nothing-test
  (let [control (es/create-control-state)
        launched (atom [])
        [logger _] (log/collecting-logger)
        _ (es/cancel! control)
        result (with-redefs [dag-orch/execute-single-task
                             (recording-task-fn launched (constantly nil))]
                 (dag-orch/execute-plan-as-dag fan-out-plan {:logger logger :control-state control}))]
    (is (empty? @launched))
    (is (true? (:cancelled? result)))
    (is (= 0 (:tasks-completed result)))
    (is (= 3 (:tasks-unreached result)))))

(deftest ^{:stratum 2} pause-holds-next-batch-until-resume-test
  (let [control (es/create-control-state)
        launched (atom [])
        [logger entries] (log/collecting-logger)
        pause-on-root (fn [task-id] (when (= root-id task-id) (es/pause! control)))]
    (with-redefs [dag-orch/execute-single-task (recording-task-fn launched pause-on-root)
                  runner-defaults/pause-poll-interval-ms (constantly poll-ms)]
      (let [run (future (dag-orch/execute-plan-as-dag fan-out-plan
                                                      {:logger logger :control-state control}))]
        (await-condition #(or (logged? entries :dag/paused) (< 1 (count @launched))))
        (is (logged? entries :dag/paused) "the loop holds before the second batch")
        (Thread/sleep (long settle-ms))
        (is (= [root-id] @launched) "nothing launches while the run is paused")
        (es/resume! control)
        (let [result (deref run wait-limit-ms ::timed-out)]
          (is (not= ::timed-out result) "resume releases the held batch")
          (is (true? (:success? result)))
          (is (= 3 (:tasks-completed result)))
          (is (= #{root-id left-id right-id} (set @launched))))))))

(deftest ^{:stratum 2} cancel-while-paused-ends-the-wait-test
  (let [control (es/create-control-state)
        launched (atom [])
        [logger entries] (log/collecting-logger)
        pause-on-root (fn [task-id] (when (= root-id task-id) (es/pause! control)))]
    (with-redefs [dag-orch/execute-single-task (recording-task-fn launched pause-on-root)
                  runner-defaults/pause-poll-interval-ms (constantly poll-ms)]
      (let [run (future (dag-orch/execute-plan-as-dag fan-out-plan
                                                      {:logger logger :control-state control}))]
        (await-condition #(or (logged? entries :dag/paused) (< 1 (count @launched))))
        (es/cancel! control)
        (let [result (deref run wait-limit-ms ::timed-out)]
          (is (not= ::timed-out result) "a cancel during a pause must not wait for a resume")
          (is (true? (:cancelled? result)))
          (is (= [root-id] @launched))
          (is (= 2 (:tasks-unreached result))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-operator-control-test)

  :leave-this-here)
