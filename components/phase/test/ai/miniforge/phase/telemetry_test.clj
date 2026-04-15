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

(ns ai.miniforge.phase.telemetry-test
  "Tests for phase telemetry event emission."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.telemetry :as telemetry]
   [ai.miniforge.event-stream.interface :as event-stream]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- make-ctx
  "Build a minimal execution context with a fresh event stream."
  []
  (let [stream (event-stream/create-event-stream)]
    {:execution/id  (random-uuid)
     :event-stream  stream}))

;------------------------------------------------------------------------------ Layer 1
;; Nil stream is a safe no-op

(deftest test-nil-stream-no-ops
  (testing "emit-phase-started! with nil stream does not throw"
    (is (nil? (telemetry/emit-phase-started! {} :verify))))

  (testing "emit-phase-completed! with nil stream does not throw"
    (is (nil? (telemetry/emit-phase-completed! {} :verify {:outcome :success}))))

  (testing "emit-agent-started! with nil stream does not throw"
    (is (nil? (telemetry/emit-agent-started! {} :verify :tester))))

  (testing "emit-agent-completed! with nil stream does not throw"
    (is (nil? (telemetry/emit-agent-completed! {} :verify :tester {:status :success}))))

  (testing "emit-milestone-started! with nil stream does not throw"
    (is (nil? (telemetry/emit-milestone-started! {} :verify))))

  (testing "emit-milestone-completed! with nil stream does not throw"
    (is (nil? (telemetry/emit-milestone-completed! {} :verify {}))))

  (testing "emit-milestone-failed! with nil stream does not throw"
    (is (nil? (telemetry/emit-milestone-failed! {} :verify {}))))

  (testing "emit-milestone-reached! with nil stream does not throw"
    (is (nil? (telemetry/emit-milestone-reached! {} :verify {})))))

;------------------------------------------------------------------------------ Layer 2
;; Milestone functions publish events

(deftest test-milestone-started-publishes
  (testing "emit-milestone-started! publishes a :phase/milestone-started event"
    (let [ctx    (make-ctx)
          stream (:event-stream ctx)]
      (telemetry/emit-milestone-started! ctx :verify)
      (let [events (event-stream/get-events stream)]
        (is (<= 1 (count events)))
        (is (= :phase/milestone-started (:event/type (last events))))))))

(deftest test-milestone-completed-publishes
  (testing "emit-milestone-completed! publishes a :phase/milestone-completed event"
    (let [ctx    (make-ctx)
          stream (:event-stream ctx)]
      (telemetry/emit-milestone-completed! ctx :verify {:artifacts ["report.edn"]})
      (let [events (event-stream/get-events stream)]
        (is (<= 1 (count events)))
        (is (= :phase/milestone-completed (:event/type (last events))))))))

(deftest test-milestone-failed-publishes
  (testing "emit-milestone-failed! publishes a :phase/milestone-failed event"
    (let [ctx    (make-ctx)
          stream (:event-stream ctx)]
      (telemetry/emit-milestone-failed! ctx :verify {:error {:message "gate failed"}})
      (let [events (event-stream/get-events stream)]
        (is (<= 1 (count events)))
        (is (= :phase/milestone-failed (:event/type (last events))))))))

(deftest test-phase-started-publishes
  (testing "emit-phase-started! publishes phase-started and milestone-started events"
    (let [ctx    (make-ctx)
          stream (:event-stream ctx)]
      (telemetry/emit-phase-started! ctx :implement)
      (let [events      (event-stream/get-events stream)
            event-types (set (map :event/type events))]
        (is (contains? event-types :workflow/phase-started))
        (is (contains? event-types :phase/milestone-started))))))

(deftest test-phase-completed-success-publishes
  (testing "emit-phase-completed! on success publishes phase-completed + milestone-completed + milestone-reached"
    (let [ctx    (make-ctx)
          stream (:event-stream ctx)]
      (telemetry/emit-phase-completed! ctx :implement {:outcome :success :duration-ms 500})
      (let [events      (event-stream/get-events stream)
            event-types (set (map :event/type events))]
        (is (contains? event-types :workflow/phase-completed))
        (is (contains? event-types :phase/milestone-completed))
        (is (contains? event-types :workflow/milestone-reached))))))

(deftest test-phase-completed-failure-publishes
  (testing "emit-phase-completed! on failure publishes phase-completed + milestone-failed"
    (let [ctx    (make-ctx)
          stream (:event-stream ctx)]
      (telemetry/emit-phase-completed! ctx :verify {:outcome :failure :error {:message "tests failed"}})
      (let [events      (event-stream/get-events stream)
            event-types (set (map :event/type events))]
        (is (contains? event-types :workflow/phase-completed))
        (is (contains? event-types :phase/milestone-failed))))))
