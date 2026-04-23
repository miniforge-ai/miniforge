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
