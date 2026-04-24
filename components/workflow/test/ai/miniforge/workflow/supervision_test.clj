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

(ns ai.miniforge.workflow.supervision-test
  "Tests for the per-run workflow supervision machine."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.supervision :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(def ^:private expected-states
  #{:nominal :warning :paused-by-supervisor :awaiting-operator :halted})

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest supervision-states-match-spec
  (testing "given the supervision machine → the expected states are present"
    (is (= expected-states sut/supervision-states))
    (is (= #{:halted} sut/terminal-states))))

(deftest valid-supervision-transitions-are-recognized
  (testing "given nominal supervision → warning, pause, escalate, and execution failure are valid"
    (is (sut/valid-transition? :nominal :warning-detected))
    (is (sut/valid-transition? :nominal :operator-paused))
    (is (sut/valid-transition? :nominal :operator-escalated))
    (is (sut/valid-transition? :nominal :execution-failed)))

  (testing "given paused supervision → resume and escalate are valid"
    (is (sut/valid-transition? :paused-by-supervisor :operator-resumed))
    (is (sut/valid-transition? :paused-by-supervisor :operator-escalated))))

(deftest invalid-supervision-transitions-are-rejected
  (testing "given an invalid event from nominal → transition returns a failure result"
    (let [result (sut/transition :nominal :operator-resumed)]
      (is (false? (:success? result)))
      (is (= :invalid-transition (:error result)))))

  (testing "given a terminal state → no further transitions are allowed"
    (let [result (sut/transition :halted :operator-cleared)]
      (is (false? (:success? result)))
      (is (= :terminal-state (:error result))))))

(deftest supervision-lifecycle-covers-live-governance-path
  (testing "given a warning, escalation, and operator clear → the machine returns to nominal"
    (let [warning (sut/transition :nominal :warning-detected)
          escalated (sut/transition (:state warning) :operator-escalated)
          cleared (sut/transition (:state escalated) :operator-cleared)]
      (is (:success? warning))
      (is (= :warning (:state warning)))
      (is (:success? escalated))
      (is (= :awaiting-operator (:state escalated)))
      (is (:success? cleared))
      (is (= :nominal (:state cleared)))))

  (testing "given a completed execution → the supervision machine halts"
    (let [halted (sut/transition :nominal :execution-completed)]
      (is (:success? halted))
      (is (= :halted (:state halted)))
      (is (sut/terminal-state? (:state halted))))))

(deftest compiled-supervision-fsm-matches-helper-api
  (testing "given the compiled FSM path → transitions land in the same states"
    (let [s0 (sut/initialize)
          s1 (sut/transition-fsm s0 :warning-detected)
          s2 (sut/transition-fsm s1 :execution-cancelled)]
      (is (= :nominal (sut/current-state s0)))
      (is (= :warning (sut/current-state s1)))
      (is (= :halted (sut/current-state s2)))
      (is (true? (sut/is-final? s2))))))
