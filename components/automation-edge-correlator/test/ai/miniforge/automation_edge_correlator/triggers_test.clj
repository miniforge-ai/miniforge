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

(ns ai.miniforge.automation-edge-correlator.triggers-test
  "Unit tests for trigger-event classification.

   Covers every entry in the RFC §RoutingTriggerKind taxonomy table, plus
   a nil-returning case for an event-type that is not a routing trigger."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.automation-edge-correlator.triggers :as triggers]))

;------------------------------------------------------------------------------ Helpers

(defn- event
  "Minimal event-stream envelope with the given `:type` keyword."
  [t]
  {:type t :event/id (random-uuid)})

;------------------------------------------------------------------------------ Tests — positive classification

(deftest pr-merged-classifies-to-pr-merged
  (is (= :pr-merged (triggers/classify-trigger (event :pr/merged)))))

(deftest review-comments-arrived-classifies-correctly
  (is (= :review-comments-arrived
         (triggers/classify-trigger (event :pr-monitor/review-comments-arrived)))))

(deftest ci-failed-classifies-correctly
  (is (= :ci-failed
         (triggers/classify-trigger (event :pr-monitor/ci-failed)))))

(deftest standards-review-posted-classifies-to-standards-review-arrived
  (is (= :standards-review-arrived
         (triggers/classify-trigger (event :standards-review/posted)))))

(deftest workflow-completed-classifies-correctly
  (is (= :workflow-completed
         (triggers/classify-trigger (event :workflow/completed)))))

(deftest gate-passed-classifies-to-gate-fired
  (is (= :gate-fired (triggers/classify-trigger (event :gate/passed)))))

(deftest gate-failed-classifies-to-gate-fired
  (is (= :gate-fired (triggers/classify-trigger (event :gate/failed)))))

;------------------------------------------------------------------------------ Tests — nil for non-trigger events

(deftest non-trigger-event-returns-nil
  (testing "event types that are not routing triggers return nil"
    (is (nil? (triggers/classify-trigger (event :workflow/started))))
    (is (nil? (triggers/classify-trigger (event :pr/opened))))
    (is (nil? (triggers/classify-trigger (event :agent/heartbeat))))
    (is (nil? (triggers/classify-trigger (event :unknown/event-type))))))

(deftest nil-type-returns-nil
  (testing "event with nil :type does not throw; returns nil"
    (is (nil? (triggers/classify-trigger {:type nil})))))

(deftest missing-type-returns-nil
  (testing "event with no :type key does not throw; returns nil"
    (is (nil? (triggers/classify-trigger {})))))
