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

(ns ai.miniforge.workflow.runner-events-test
  "Tests for workflow lifecycle event publishing.
   Verifies that workflow-run/* entity fields are merged into lifecycle events
   so the Rust StateManager can deserialize WorkflowRun projections."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.runner-events :as events]
   [ai.miniforge.event-stream.interface :as event-stream]))

;------------------------------------------------------------------------------ Helpers

(defn- test-context
  "Minimal execution context for event publishing tests."
  []
  {:execution/id            (random-uuid)
   :execution/workflow      {:workflow/id :test-wf :workflow/version "1.0.0"}
   :execution/input         {:intent "Test task"}
   :execution/opts          {:trigger-source :api}
   :execution/status        :running
   :execution/errors        []
   :execution/metrics       {:duration-ms 100}
   :execution/current-phase :plan
   :execution/phase-index   0})

(defn- create-stream []
  (event-stream/create-event-stream {:sinks []}))

(defn- first-event [stream]
  (first (:events @stream)))

(defn- all-events [stream]
  (:events @stream))

;------------------------------------------------------------------------------ publish-workflow-started!

(deftest publish-workflow-started-includes-entity-fields-test
  (testing "publish-workflow-started! merges workflow-run/* fields"
    (let [stream (create-stream)
          ctx    (test-context)]
      (events/publish-workflow-started! stream ctx)
      (let [ev (first-event stream)]
        (is (= (:execution/id ctx) (:workflow-run/id ev)))
        (is (= "test-wf" (:workflow-run/workflow-key ev)))
        (is (= "Test task" (:workflow-run/intent ev)))
        (is (= :running (:workflow-run/status ev)))
        (is (= "init" (:workflow-run/current-phase ev)))
        (is (= :api (:workflow-run/trigger-source ev)))
        (is (inst? (:workflow-run/started-at ev)))
        (is (inst? (:workflow-run/updated-at ev)))))))

(deftest publish-workflow-started-timestamps-same-instant-test
  (testing "started-at and updated-at are bound to the same instant"
    (let [stream (create-stream)
          ctx    (test-context)]
      (events/publish-workflow-started! stream ctx)
      (let [ev (first-event stream)]
        (is (= (:workflow-run/started-at ev)
               (:workflow-run/updated-at ev)))))))

(deftest publish-workflow-started-nil-stream-is-noop-test
  (testing "nil event-stream does not throw"
    (is (nil? (events/publish-workflow-started! nil (test-context))))))

;------------------------------------------------------------------------------ publish-workflow-completed!

(deftest publish-workflow-completed-success-entity-fields-test
  (testing "publish-workflow-completed! merges workflow-run/* on success"
    (let [stream (create-stream)
          ctx    (assoc (test-context) :execution/status :completed)]
      (events/publish-workflow-completed! stream ctx)
      (let [ev (first-event stream)]
        (is (= (:execution/id ctx) (:workflow-run/id ev)))
        (is (= :completed (:workflow-run/status ev)))
        (is (= "plan" (:workflow-run/current-phase ev)))))))

(deftest publish-workflow-completed-emits-pr-created-for-owned-prs-test
  (testing "workflow completion emits canonical pr/created events for workflow-owned PRs"
    (let [stream (create-stream)
          ctx    (assoc (test-context)
                        :execution/status :completed
                        :workflow/pr-info {:pr-number 42
                                           :pr-url "https://github.com/acme/widget/pull/42"
                                           :branch "feature/widget"})]
      (events/publish-workflow-completed! stream ctx)
      (let [emitted (all-events stream)
            completion (first emitted)
            pr-created (second emitted)]
        (is (= 2 (count emitted)))
        (is (= :workflow/completed (:event/type completion)))
        (is (= :pr/created (:event/type pr-created)))
        (is (= (:execution/id ctx) (:workflow/id pr-created)))
        (is (= "acme/widget" (:pr/repo pr-created)))
        (is (= 42 (:pr/number pr-created)))
        (is (= "feature/widget" (:pr/branch pr-created)))))))

(deftest publish-workflow-completed-failed-entity-fields-test
  (testing "publish-workflow-completed! merges workflow-run/* on failure"
    (let [stream (create-stream)
          ctx    (assoc (test-context) :execution/status :failed)]
      (events/publish-workflow-completed! stream ctx)
      (let [ev (first-event stream)]
        (is (= (:execution/id ctx) (:workflow-run/id ev)))
        (is (= :failed (:workflow-run/status ev)))))))

;------------------------------------------------------------------------------ publish-phase-started!

(deftest publish-phase-started-includes-entity-fields-test
  (testing "publish-phase-started! merges workflow-run/* fields with phase as current-phase"
    (let [stream (create-stream)
          ctx    (test-context)]
      (events/publish-phase-started! stream ctx :implement)
      (let [ev (first-event stream)]
        (is (= (:execution/id ctx) (:workflow-run/id ev)))
        (is (= :running (:workflow-run/status ev)))
        (is (= "implement" (:workflow-run/current-phase ev)))))))

(deftest publish-phase-started-nil-stream-is-noop-test
  (testing "nil event-stream does not throw"
    (is (nil? (events/publish-phase-started! nil (test-context) :plan)))))

(deftest start-phase-heartbeat-websocket-stream-is-noop-test
  (testing "heartbeats require a durable stream because the scheduler derefs it"
    (is (nil? (events/start-phase-heartbeat! {:websocket :fake} (test-context) :plan
                                             {:interval-ms 10})))))

;------------------------------------------------------------------------------ publish-phase-completed!

(deftest publish-phase-completed-includes-transition-request-test
  (testing "phase completion events include transition requests when present"
    (let [stream (create-stream)
          ctx (test-context)
          phase-result (phase/request-redirect {:status :failed
                                                :error {:message "tests failed"}}
                                               :implement)]
      (events/publish-phase-completed! stream ctx :verify phase-result)
      (let [event (first-event stream)]
        (is (= :workflow/phase-completed (:event/type event)))
        (is (= :implement (get-in event [:phase/transition-request :transition/target])))))))

;------------------------------------------------------------------------------ :meta diagnostic fields
;; es/phase-completed stores data under namespaced keys:
;;   :outcome → :phase/outcome
;;   :meta    → :phase/meta   (added by this PR to core.clj)

(deftest phase-completed-failure-includes-meta-stop-reason-test
  (testing "failure events carry :phase/meta with stop-reason and num-turns from error-data"
    ;; result-boundary/error-response → response/error produces:
    ;;   {:status :error
    ;;    :error  {:message "..." :data {:stop-reason X :num-turns N}}
    ;;    :metrics {:tokens N :duration-ms 0}}
    ;; That whole map is what lands under :result in the phase-result.
    (let [stream (create-stream)
          ctx    (test-context)
          phase-result {:status   :failed
                        :success? false
                        :result   {:status  :error
                                   :error   {:message "LLM call failed"
                                             :data    {:stop-reason :max-tokens
                                                       :num-turns   12}}
                                   :metrics {:tokens 0 :duration-ms 0}}}]
      (events/publish-phase-completed! stream ctx :implement phase-result)
      (let [event (first-event stream)
            meta  (:phase/meta event)]
        (is (some? event) "event must be published — stream must be non-empty")
        (is (= :failure (:phase/outcome event)))
        (is (some? meta) ":phase/meta must be present on failure events with LLM diagnostics")
        (is (= :max-tokens (:stop-reason meta)))
        (is (= 12 (:turn-count meta)))))))

(deftest phase-completed-failure-meta-omitted-when-no-diagnostic-fields-test
  (testing "failure events without LLM diagnostic fields do not carry :phase/meta"
    (let [stream (create-stream)
          ctx    (test-context)
          phase-result {:status   :failed
                        :success? false
                        :error    {:message "network timeout"}}]
      (events/publish-phase-completed! stream ctx :implement phase-result)
      (let [event (first-event stream)]
        (is (some? event) "event must be published — stream must be non-empty")
        (is (= :failure (:phase/outcome event)))
        (is (nil? (:phase/meta event)) ":phase/meta must be absent when no diagnostic fields are present")))))

(deftest phase-completed-success-has-no-meta-test
  (testing "success events do not carry :phase/meta diagnostic fields"
    ;; Planner success shape (response/success) puts stop-reason under :metrics.
    ;; Even with diagnostics present, :phase/meta must be absent on success.
    (let [stream (create-stream)
          ctx    (test-context)
          phase-result {:status   :completed
                        :success? true
                        :result   {:status  :success
                                   :output  {}
                                   :metrics {:tokens      500
                                             :duration-ms 3000
                                             :stop-reason :end-turn
                                             :num-turns   5}}}]
      (events/publish-phase-completed! stream ctx :plan phase-result)
      (let [event (first-event stream)]
        (is (some? event) "event must be published — stream must be non-empty")
        (is (= :success (:phase/outcome event)))
        (is (nil? (:phase/meta event)) ":phase/meta must not appear on success events")))))

(deftest phase-completed-failure-partial-meta-fields-test
  (testing "partial LLM diagnostic fields (only stop-reason) yield partial :phase/meta"
    ;; When only stop-reason made it into the error data (num-turns absent),
    ;; :phase/meta should carry :stop-reason but no :turn-count.
    (let [stream (create-stream)
          ctx    (test-context)
          phase-result {:status   :failed
                        :success? false
                        :result   {:status  :error
                                   :error   {:message "LLM call failed"
                                             :data    {:stop-reason :end-turn}}
                                   :metrics {:tokens 0 :duration-ms 0}}}]
      (events/publish-phase-completed! stream ctx :review phase-result)
      (let [event (first-event stream)
            meta  (:phase/meta event)]
        (is (some? event) "event must be published — stream must be non-empty")
        (is (= :failure (:phase/outcome event)))
        (is (= :end-turn (:stop-reason meta)))
        (is (nil? (:final-message-preview meta)))
        (is (nil? (:turn-count meta)))
        (is (nil? (:tool-call-count meta)))))))
