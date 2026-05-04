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

(ns ai.miniforge.workflow.anomaly.transition-status-test
  "Coverage for `state/transition-status-anomaly` and its deprecated
   throwing sibling `state/transition-status`. The FSM rejection path
   becomes an `:invalid-input` anomaly; the throwing variant continues
   to surface a slingshot `:anomalies.workflow/invalid-transition` for
   legacy try+ callers."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.state :as state])
  (:import (clojure.lang ExceptionInfo)))

(def sample-workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/phases [{:phase/id :start}]})

(defn- pending-state []
  (state/create-execution-state sample-workflow {:input "x"}))

;------------------------------------------------------------------------------ Happy path

(deftest transition-status-anomaly-returns-state-on-valid-event
  (testing "valid :start event from :pending advances to :running"
    (let [s (pending-state)
          result (state/transition-status-anomaly s :start)]
      (is (not (anomaly/anomaly? result)))
      (is (= :running (:execution/status result))))))

;------------------------------------------------------------------------------ Failure path

(deftest transition-status-anomaly-returns-anomaly-on-invalid-event
  (testing "invalid event yields :invalid-input anomaly with FSM diagnostics"
    (let [s (pending-state)
          result (state/transition-status-anomaly s :complete)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (let [data (:anomaly/data result)]
        (is (= :pending (:current-status data)))
        (is (= :complete (:event data)))
        (is (some? (:error data)))
        (is (some? (:fsm-message data)))))))

(deftest transition-status-anomaly-leaves-state-untouched-on-rejection
  (testing "rejected transition does not mutate execution status"
    (let [s (pending-state)]
      (state/transition-status-anomaly s :complete)
      (is (= :pending (:execution/status s))
          "anomaly path is functionally pure — no FSM transition recorded"))))

;------------------------------------------------------------------------------ Throwing-variant compat

(deftest transition-status-still-returns-on-valid-event
  (testing "deprecated throwing variant still returns updated state on success"
    (let [s (pending-state)
          result (state/transition-status s :start)]
      (is (= :running (:execution/status result))))))

(deftest transition-status-still-throws-on-invalid-event
  (testing "deprecated throwing variant still throws ExceptionInfo on FSM rejection"
    (let [s (pending-state)]
      (is (thrown-with-msg? ExceptionInfo #"Invalid state transition"
            (state/transition-status s :complete))))))

(deftest transition-status-thrown-ex-data-preserves-shape
  (testing "ex-data preserves the legacy slingshot anomaly shape"
    (let [s (pending-state)]
      (try
        (state/transition-status s :complete)
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies.workflow/invalid-transition (:anomaly/category data)))
            (is (= :pending (:current-status data)))
            (is (= :complete (:event data)))
            (is (some? (:error data)))
            (is (some? (:message data)))))))))
