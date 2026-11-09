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
  "Recovery scenario C4: a cancel reaches work in flight without
   interrupting any thread. Waits end on the stop, and the runner records
   the stop of a phase the cancel cut short."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.dag-loop :as dag-loop]
   [ai.miniforge.workflow.execution :as exec]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
   [ai.miniforge.workflow.runner-defaults :as runner-defaults]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-events :as runner-events]
   [ai.miniforge.workflow.runner-iteration :as runner-iteration]
   [ai.miniforge.workflow.runner-phase-step :as runner-phase-step]
   [ai.miniforge.workflow.stop-signal :as stop-signal]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} cancel-delay-ms
  "When a test's cancel lands, after the wait it ends has started."
  200)

(def ^{:stratum 0 :private true} long-wait-ms
  "A wait the cancel must cut short."
  10000)

(def ^{:stratum 0 :private true} prompt-ms
  "Upper bound for a wait that a cancel ended."
  5000)

(defn- ^{:stratum 0} cancel-after! [control-state ms]
  (future (Thread/sleep (long ms)) (es/cancel! control-state)))

(defn- ^{:stratum 0} elapsed-ms [started]
  (- (System/currentTimeMillis) started))

(deftest ^{:stratum 0} a-stop-during-the-phase-is-recorded-before-the-checkpoint-test
  (let [checkpointed (atom nil)
        context      (ctx/create-context {:workflow/id :stop-test :workflow/version "1.0.0"
                                          :workflow/pipeline [{:phase :plan}]}
                                         {:task "Test"} {})
        iterate-with (fn [cs step]
                       (with-redefs-fn {#'monitoring/check-workflow-supervision (constantly {:status :continue})
                                        #'exec/execute-phase-step step
                                        #'env/persist-workspace-at-phase-boundary! (fn [_ _] nil)
                                        #'runner-checkpoint/checkpoint! (fn [c] (reset! checkpointed c) c)}
                         #(runner-iteration/execute-single-iteration [] context {} 0 cs)))
        stop-types   #(set (map :type (:execution/errors %)))]
    (testing "the cancel ended the phase's LLM call and the phase failed: the
              stop is recorded, and the checkpoint has it"
      (let [cs (es/create-control-state)
            result (iterate-with cs (fn [_ c & _] (es/cancel! cs) (ctx/transition-to-failed c)))]
        (is (contains? (stop-types result) :dashboard-stop))
        (is (contains? (stop-types @checkpointed) :dashboard-stop))))
    (testing "a stop the phase already recorded (a DAG cancelled between batches) is recorded once"
      (let [cs (es/create-control-state)
            result (iterate-with cs (fn [_ c & _]
                                      (es/cancel! cs)
                                      (-> (ctx/transition-to-failed c)
                                          (update :execution/errors conj {:type :dashboard-stop}))))]
        (is (= 1 (count (filter #{:dashboard-stop} (map :type (:execution/errors result))))))))
    (testing "a cancel landing after the phase completed the run does not undo it"
      (let [cs (es/create-control-state)
            result (iterate-with cs (fn [_ c & _] (es/cancel! cs) (assoc c :execution/status :completed)))]
        (is (= :completed (:execution/status result)))
        (is (not (contains? (stop-types result) :dashboard-stop)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} sleep-ends-on-a-stop-test
  (testing "a cancel during the sleep ends it"
    (let [cs (es/create-control-state)
          _  (cancel-after! cs cancel-delay-ms)
          started (System/currentTimeMillis)]
      (is (true? (stop-signal/sleep! cs long-wait-ms)))
      (is (< (elapsed-ms started) prompt-ms))))
  (testing "a run already stopped does not sleep"
    (let [started (System/currentTimeMillis)]
      (is (true? (stop-signal/sleep! (doto (es/create-control-state) es/cancel!) long-wait-ms)))
      (is (< (elapsed-ms started) cancel-delay-ms))))
  (testing "a pause is not a stop, and an unstopped sleep runs to the end"
    (is (false? (stop-signal/sleep! (doto (es/create-control-state) es/pause!) 1)))
    (is (false? (stop-signal/sleep! (es/create-control-state) cancel-delay-ms))))
  (testing "no control state: a plain sleep"
    (is (false? (stop-signal/sleep! nil 1)))))

(deftest ^{:stratum 1} a-cancel-during-the-retry-backoff-ends-it-test
  (testing "the phase step returned a retry; the cancel lands during the
            backoff that follows"
    (let [cs      (es/create-control-state)
          retry   {:execution/current-phase :implement
                   :execution/phase-results {:implement {:status :retrying}}
                   :phase {:iterations 2}}
          started (System/currentTimeMillis)
          result  (with-redefs-fn {#'exec/execute-phase-step (fn [_ c & _] (cancel-after! cs cancel-delay-ms) c)
                                   #'env/persist-workspace-at-phase-boundary! (fn [_ _] nil)
                                   #'env/track-unpersisted (fn [_ stepped _] stepped)
                                   #'runner-defaults/backoff-base-ms (constantly long-wait-ms)
                                   #'runner-events/check-backend-health-at-boundary! (constantly nil)}
                    #(runner-phase-step/execute-phase [] retry {} cs))]
      (is (< (elapsed-ms started) prompt-ms) "the 20 s backoff ended on the cancel")
      (is (= :retrying (get-in result [:execution/phase-results :implement :status]))))))

(deftest ^{:stratum 1} a-cancel-during-the-dag-pause-wait-returns-stopped-test
  (let [cs      (doto (es/create-control-state) es/pause!)
        _       (cancel-after! cs cancel-delay-ms)
        logger  (log/create-logger {:min-level :error :output :human})
        started (System/currentTimeMillis)]
    (with-redefs-fn {#'runner-defaults/pause-poll-interval-ms (constantly long-wait-ms)}
      #(is (true? (#'dag-loop/await-launch-clearance! cs logger))))
    (is (< (elapsed-ms started) prompt-ms) "a 10 s pause poll ended on the cancel")))
