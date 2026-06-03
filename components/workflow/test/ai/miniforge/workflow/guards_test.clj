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

(ns ai.miniforge.workflow.guards-test
  "Tests for the workflow named-guard registry and validator.

   This is the Phase 1 deliverable of the FSM RFC at
   `docs/architecture/workflow-fsm-guarded-transitions.md`. It exercises:

   1. The registry CRUD surface (register / lookup / unregister / clear).
   2. The reference walker against the three transition shapes
      clj-statecharts accepts (bare keyword, single map, ordered vector).
   3. The validator's two outcomes: nil on clean coverage, a structured
      error map on dangling references.
   4. End-to-end wiring through `validate-execution-machine` so a
      workflow with a dangling guard fails validation at compile time."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.guards :as guards]
   [ai.miniforge.workflow.fsm :as workflow-fsm]))

;------------------------------------------------------------------------------ Fixture
;; Every test starts with an empty registry to keep cases independent.

(use-fixtures :each
  (fn [f]
    (guards/clear-registry!)
    (try (f) (finally (guards/clear-registry!)))))

;------------------------------------------------------------------------------ Helpers

(defn- always-true [_state _event] true)
(defn- always-false [_state _event] false)

;------------------------------------------------------------------------------ Registry

(deftest register-and-lookup-test
  (testing "register-guard! makes the predicate retrievable by keyword"
    (guards/register-guard! :budget/redirects-available? always-true)
    (is (= always-true (guards/lookup-guard :budget/redirects-available?))
        "lookup returns the exact registered fn")
    (is (contains? (guards/registered-keys) :budget/redirects-available?)
        "registered-keys includes the new entry")))

(deftest unregister-test
  (testing "unregister-guard! removes a previously-registered entry"
    (guards/register-guard! :verdict/terminal? always-true)
    (guards/unregister-guard! :verdict/terminal?)
    (is (nil? (guards/lookup-guard :verdict/terminal?))
        "lookup returns nil after unregister")
    (is (not (contains? (guards/registered-keys) :verdict/terminal?))
        "registered-keys no longer includes it")))

(deftest register-rejects-non-keyword-test
  (testing "register-guard! refuses non-keyword keys — protects against typos"
    (is (thrown? Exception (guards/register-guard! "verdict/terminal?" always-true)))
    (is (thrown? Exception (guards/register-guard! nil always-true)))))

(deftest register-rejects-non-fn-value-test
  (testing "register-guard! refuses non-fn values — protects against misuse"
    (is (thrown? Exception (guards/register-guard! :verdict/terminal? :not-a-fn)))
    (is (thrown? Exception (guards/register-guard! :verdict/terminal? nil)))))

;------------------------------------------------------------------------------ Reference walker

(deftest guard-references-empty-when-no-states-use-guards-test
  (testing "Phase 1 baseline: a state map with only bare-keyword transitions
            has zero guard references — the new code is no-op until phases
            opt in."
    (is (= [] (guards/guard-references {})))
    (is (= [] (guards/guard-references {:phase-0-review
                                        {:on {:phase/succeed :phase-1-implement
                                              :phase/fail :failed}}})))))

(deftest guard-references-extracts-from-single-map-test
  (testing "single-map transition with a :guard keyword is surfaced"
    (let [states {:phase-0-review
                  {:on {:phase/fail {:target :failed
                                     :guard :verdict/terminal?}}}}]
      (is (= [:verdict/terminal?] (guards/guard-references states))))))

(deftest guard-references-extracts-from-ordered-vector-test
  (testing "ordered guarded-array transition: every map-form :guard is
            surfaced, fall-through default (no :guard) is ignored"
    (let [states {:phase-0-review
                  {:on {:phase/fail
                        [{:target :failed   :guard :verdict/terminal?}
                         {:target :failed   :guard :budget/redirects-spent?}
                         {:target :implement :guard :config/on-fail-set?
                          :actions [:redirect/inc-count]}
                         {:target :failed}]}}}]
      (is (= [:budget/redirects-spent?
              :config/on-fail-set?
              :verdict/terminal?]
             (guards/guard-references states))
          "all three references; order is deterministic (sorted)"))))

(deftest guard-references-dedupes-across-states-test
  (testing "the same guard referenced from two states surfaces once"
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :failed}]}}
                  :phase-2-review-redux
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :failed}]}}}]
      (is (= [:verdict/terminal?] (guards/guard-references states))))))

(deftest guard-references-with-locations-test
  (testing "each reference carries its source state + event for actionable errors"
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :failed}]}}}
          refs   (guards/guard-references-with-locations states)]
      (is (= 1 (count refs)))
      (is (= {:state :phase-0-review
              :event :phase/fail
              :guard :verdict/terminal?}
             (first refs))))))

;------------------------------------------------------------------------------ Validator

(deftest validate-returns-nil-for-clean-coverage-test
  (testing "every :guard reference resolves → validator returns nil"
    (guards/register-guard! :verdict/terminal? always-true)
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :failed}]}}}]
      (is (nil? (guards/validate-guard-references states))))))

(deftest validate-returns-nil-when-no-guards-at-all-test
  (testing "no guards anywhere → validator returns nil (the Phase 1 baseline)"
    (let [states {:phase-0-plan {:on {:phase/succeed :phase-1-implement}}}]
      (is (nil? (guards/validate-guard-references states))))))

(deftest validate-flags-single-dangling-reference-test
  (testing "one unresolved :guard keyword → error with the offending ref"
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :failed}]}}}
          result (guards/validate-guard-references states)]
      (is (= :dangling-guard-references (:error result)))
      (is (= 1 (count (:references result))))
      (is (= {:state :phase-0-review
              :event :phase/fail
              :guard :verdict/terminal?}
             (first (:references result))))
      (is (string? (:message result))
          ":message is the localized error string"))))

(deftest validate-flags-multiple-dangling-references-test
  (testing "two unresolved refs → both surfaced in :references"
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :implement :guard :budget/available?}
                                     {:target :failed}]}}}
          result (guards/validate-guard-references states)]
      (is (= :dangling-guard-references (:error result)))
      (is (= #{:verdict/terminal? :budget/available?}
             (set (map :guard (:references result))))))))

(deftest validate-distinguishes-registered-from-dangling-test
  (testing "one ref registered + one dangling → only the dangling one is flagged"
    (guards/register-guard! :verdict/terminal? always-true)
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                                     {:target :implement :guard :budget/available?}
                                     {:target :failed}]}}}
          result (guards/validate-guard-references states)]
      (is (= 1 (count (:references result))))
      (is (= :budget/available? (:guard (first (:references result))))))))

;------------------------------------------------------------------------------ Integration through validate-execution-machine
;;
;; Production code does not yet ship workflows that reference guards by
;; keyword (Phase 1 lays foundation only). Construct a synthetic pipeline
;; here that DOES, to prove the end-to-end wiring catches dangling refs at
;; workflow validation time.

(deftest validate-execution-machine-passes-on-no-guards-test
  (testing "the current production-shape workflow (no guard refs) validates
            cleanly — Phase 1 must be behavior-neutral"
    (let [workflow {:workflow/id :plain
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :implement}
                                        {:phase :done}]}
          result (workflow-fsm/validate-execution-machine workflow)]
      (is (:valid? result))
      (is (empty? (:errors result))
          "no :dangling-guard-references entry in :errors when no guards present"))))