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
(ns ai.miniforge.workflow.cancelled-disposition-test
  "A confirmed stop ends a run :cancelled (N2 2.2), reported by
   :workflow/cancelled alone (N3), never :failed."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.execution-transition :as transition]
   [ai.miniforge.workflow.phase-test-support :as phase-test-support]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.runner-environment :as runner-environment]
   [ai.miniforge.workflow.runner-events :as runner-events]
   [clojure.test :refer [deftest is use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private cancellation
  {:cancel/requested-by "op@example.invalid" :cancel/source :tui})

(def ^{:stratum 0} ^:private stopped-phase
  "A test phase the operator cancels while it runs; it then fails, as an
   agent call the cancel cut short does."
  :cancelled-disposition-stopped)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stopped-control-state []
  (doto (es/create-control-state) (es/cancel! cancellation)))

(defmethod ^{:stratum 1} phase/get-phase-interceptor-method stopped-phase
  [config]
  {:name ::stopped
   :config (phase/merge-with-defaults config)
   :enter (fn [context]
            (es/cancel! (get-in context [:execution/opts :control-state]) cancellation)
            (phase/enter-context context stopped-phase :workflow-tester []
                                 {:tokens 1 :iterations 1 :time-seconds 1}
                                 (System/currentTimeMillis) nil))
   :leave (fn [context]
            (-> context
                (assoc-in [:phase :name] stopped-phase)
                (assoc-in [:phase :status] :failed)
                (assoc-in [:phase :result] {:status :failed
                                            :error {:message "cut short by the cancel"}})))
   :error (fn [context _] context)})

(deftest ^{:stratum 1} a-cancelled-run-publishes-workflow-cancelled-alone
  (let [stream (es/create-event-stream {:sinks []})
        workflow-id (random-uuid)]
    (runner-events/publish-workflow-completed!
     stream {:execution/id workflow-id :execution/status :cancelled
             :execution/cancellation cancellation
             :execution/metrics {:duration-ms 12}
             :execution/current-phase :implement})
    (let [events (es/get-events stream)
          event (first events)]
      (is (= [:workflow/cancelled] (mapv :event/type events))
          "no :workflow/failed and no :pr/created for a cancelled run")
      (is (= "op@example.invalid" (:cancel/requested-by event)))
      (is (= :tui (:cancel/source event)))
      (is (= 12 (:workflow/duration-ms event)))
      (is (= :cancelled (:workflow-run/status event))))))

(deftest ^{:stratum 1} a-phase-cut-short-by-a-cancel-ends-the-run-cancelled
  (let [workflow {:workflow/id :cancelled-disposition :workflow/version "1.0.0"
                  :workflow/pipeline [{:phase stopped-phase}
                                      {:phase phase-test-support/runner-test-done}]}
        result (runner/run-pipeline workflow {:task "Test"}
                                    {:control-state (es/create-control-state)
                                     :skip-lifecycle-events true})]
    (is (= :cancelled (:execution/status result))
        "the phase's failure is the cancel's effect: the run ends cancelled, not failed")
    (is (= cancellation (:execution/cancellation result)))
    (is (some #(= :dashboard-stop (:type %)) (:execution/errors result)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-stop-turns-only-a-phase-failure-into-a-cancel
  (let [stopped {:execution/opts {:control-state (stopped-control-state)}}
        running {:execution/opts {:control-state (es/create-control-state)}}]
    (is (= :cancel (transition/stop-aware-event stopped :phase/fail)))
    (is (= :cancel (transition/stop-aware-event stopped {:type :phase/fail :phase/verdict {}})))
    (is (= :phase/succeed (transition/stop-aware-event stopped :phase/succeed)))
    (is (= :phase/fail (transition/stop-aware-event running :phase/fail)))
    (is (= :phase/fail (transition/stop-aware-event {} :phase/fail)))))

(deftest ^{:stratum 2} a-stop-does-not-rewrite-an-ended-run
  (doseq [status [:completed :completed-with-warnings :failed]]
    (let [ended {:execution/status status
                 :execution/opts {:control-state (stopped-control-state)}}]
      (is (= ended (ctx/transition-to-cancelled ended)) (str status)))))

(deftest ^{:stratum 2} a-cancelled-context-records-who-cancelled
  (let [cancelled (ctx/transition-to-cancelled
                   {:execution/status :running :execution/started-at 0
                    :execution/opts {:control-state (stopped-control-state)}})]
    (is (= :cancelled (:execution/status cancelled)))
    (is (= cancellation (:execution/cancellation cancelled)))
    (is (pos? (:execution/ended-at cancelled)))))

(phase/register-phase-defaults! stopped-phase {:phase stopped-phase :agent :workflow-tester
                                               :gates []
                                               :budget {:tokens 1 :iterations 1
                                                        :time-seconds 1}})

(use-fixtures :each
  phase-test-support/with-workflow-phase-test-support
  (fn [f]
    (with-redefs-fn {#'runner-environment/acquire-execution-environment! (fn [& _] nil)} f)))

(comment ::cancelled-disposition)
