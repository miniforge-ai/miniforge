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

(ns ai.miniforge.workflow.replay-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.workflow.replay :as replay]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def workflow-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890")

(def sample-events
  [{:log/id (random-uuid)
    :log/timestamp #inst "2026-01-24T10:00:00.000-00:00"
    :log/level :info
    :log/category :workflow
    :log/event :workflow/started
    :data {:workflow-id workflow-id
           :spec-title "Test Workflow"}}
   {:log/id (random-uuid)
    :log/timestamp #inst "2026-01-24T10:00:01.000-00:00"
    :log/level :info
    :log/category :workflow
    :log/event :workflow/phase-started
    :data {:workflow-id workflow-id
           :phase :plan}}
   {:log/id (random-uuid)
    :log/timestamp #inst "2026-01-24T10:00:10.000-00:00"
    :log/level :info
    :log/category :workflow
    :log/event :workflow/phase-completed
    :data {:workflow-id workflow-id
           :phase :plan}}
   {:log/id (random-uuid)
    :log/timestamp #inst "2026-01-24T10:00:11.000-00:00"
    :log/level :info
    :log/category :workflow
    :log/event :workflow/completed
    :data {:workflow-id workflow-id}}])

;------------------------------------------------------------------------------ Layer 1
;; Event filtering tests

(deftest filter-events-by-workflow-test
  (testing "filters events for specific workflow"
    (let [other-workflow-id (random-uuid)
          mixed-events (concat
                        sample-events
                        [{:log/event :workflow/started
                          :data {:workflow-id other-workflow-id}}])
          filtered (replay/filter-events-by-workflow mixed-events workflow-id)]
      (is (= 4 (count filtered)))
      (is (every? #(= workflow-id (get-in % [:data :workflow-id])) filtered))))

  (testing "returns empty for non-matching workflow"
    (let [filtered (replay/filter-events-by-workflow sample-events (random-uuid))]
      (is (empty? filtered)))))

(deftest filter-events-by-time-range-test
  (testing "filters events within time range"
    (let [start #inst "2026-01-24T10:00:00.500-00:00"
          end #inst "2026-01-24T10:00:10.500-00:00"
          filtered (replay/filter-events-by-time-range sample-events start end)]
      (is (= 2 (count filtered)))))

  (testing "no start time includes all before end"
    (let [end #inst "2026-01-24T10:00:05.000-00:00"
          filtered (replay/filter-events-by-time-range sample-events nil end)]
      (is (= 2 (count filtered)))))

  (testing "no end time includes all after start"
    (let [start #inst "2026-01-24T10:00:09.000-00:00"
          filtered (replay/filter-events-by-time-range sample-events start nil)]
      (is (= 2 (count filtered))))))

(deftest sort-events-test
  (testing "sorts events chronologically"
    (let [reversed (reverse sample-events)
          sorted (replay/sort-events-by-timestamp reversed)]
      (is (= sample-events sorted)))))

;------------------------------------------------------------------------------ Layer 2
;; State reconstruction tests

(deftest replay-event-test
  (testing "workflow/started sets initial state"
    (let [event (first sample-events)
          state (replay/replay-event {} event)]
      (is (= workflow-id (:workflow/id state)))
      (is (= :executing (:workflow/status state)))
      (is (some? (:workflow/started-at state)))))

  (testing "workflow/phase-started updates phase status"
    (let [initial-state {:workflow/id workflow-id}
          event (second sample-events)
          state (replay/replay-event initial-state event)]
      (is (= :plan (:workflow/current-phase state)))
      (is (= :executing (get-in state [:workflow/phases :plan :status])))))

  (testing "workflow/completed sets final status"
    (let [initial-state {:workflow/id workflow-id}
          event (last sample-events)
          state (replay/replay-event initial-state event)]
      (is (= :completed (:workflow/status state)))
      (is (some? (:workflow/completed-at state)))))

  (testing "unknown event doesn't change state"
    (let [initial-state {:workflow/id workflow-id :workflow/status :executing}
          unknown-event {:log/event :unknown/event :data {}}
          state (replay/replay-event initial-state unknown-event)]
      (is (= initial-state state)))))

(deftest replay-events-test
  (testing "replays all events to reconstruct state"
    (let [result (replay/replay-events {} sample-events)]
      (is (= workflow-id (:workflow/id result)))
      (is (= :completed (:workflow/status result)))
      (is (= :plan (:workflow/current-phase result)))
      (is (= :completed (get-in result [:workflow/phases :plan :status])))))

  (testing "event order matters for state reconstruction"
    (let [reversed (reverse sample-events)
          result-forward (replay/replay-events {} sample-events)
          result-reverse (replay/replay-events {} reversed)]
      ;; Forward replay should end with :completed status
      (is (= :completed (:workflow/status result-forward)))
      ;; Reverse replay will have wrong order, different state
      ;; This proves that event order is significant
      (is (not= result-forward result-reverse)))))

;------------------------------------------------------------------------------ Layer 3
;; Replay execution tests

(deftest replay-workflow-test
  (testing "replays workflow from events"
    (let [result (replay/replay-workflow sample-events :workflow-id workflow-id)]
      (is (map? (:state result)))
      (is (= 4 (:events-applied result)))
      (is (= :completed (:final-status result)))))

  (testing "partial replay up to timestamp"
    (let [until #inst "2026-01-24T10:00:05.000-00:00"
          result (replay/replay-workflow sample-events
                                         :workflow-id workflow-id
                                         :until until)]
      (is (= 2 (:events-applied result)))
      ;; Should only have started and phase-started events
      (is (= :executing (:final-status result)))))

  (testing "empty events returns empty state"
    (let [result (replay/replay-workflow [])]
      (is (= {} (:state result)))
      (is (= 0 (:events-applied result))))))

(deftest verify-determinism-test
  (testing "same events produce deterministic state"
    (let [expected-state {:workflow/id workflow-id
                          :workflow/status :completed
                          :workflow/current-phase :plan}
          result (replay/verify-determinism sample-events expected-state
                                            :workflow-id workflow-id)]
      (is (:deterministic? result))
      (is (empty? (:differences result)))))

  (testing "detects non-deterministic state"
    (let [expected-state {:workflow/id workflow-id
                          :workflow/status :failed  ; Wrong status
                          :workflow/current-phase :plan}
          result (replay/verify-determinism sample-events expected-state
                                            :workflow-id workflow-id)]
      (is (not (:deterministic? result)))
      (is (seq (:differences result)))
      (is (some #(= :workflow/status (:key %)) (:differences result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.workflow.replay-test)

  :leave-this-here)
