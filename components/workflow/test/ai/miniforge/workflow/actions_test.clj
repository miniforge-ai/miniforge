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

(ns ai.miniforge.workflow.actions-test
  "Tests for the workflow named-action registry, validator, and resolver.

   Phase 2a deliverable of the FSM RFC: the action registry mirrors the
   guard registry (`workflow.guards`) so keyword-form `:actions`
   references in machine state transitions can be statically validated
   for coverage and resolved to fns at machine-compile time."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.actions :as actions]))

(use-fixtures :each
  (fn [f]
    ;; Snapshot/restore so namespace-load-time registrations from
    ;; `standard-guards-and-actions` survive across tests that need a
    ;; clean slate. See guards_test for the same pattern.
    (let [snap (actions/snapshot-registry)]
      (actions/clear-registry!)
      (try (f) (finally (actions/restore-registry! snap))))))

(defn- bump-counter [state _event]
  (update-in state [:context :redirect-count] (fnil inc 0)))

;------------------------------------------------------------------------------ Registry

(deftest register-and-lookup-test
  (testing "register-action! makes the fn retrievable by keyword"
    (actions/register-action! :redirect/inc-count bump-counter)
    (is (= bump-counter (actions/lookup-action :redirect/inc-count)))
    (is (contains? (actions/registered-keys) :redirect/inc-count))))

(deftest unregister-test
  (testing "unregister-action! removes a previously-registered entry"
    (actions/register-action! :redirect/inc-count bump-counter)
    (actions/unregister-action! :redirect/inc-count)
    (is (nil? (actions/lookup-action :redirect/inc-count)))))

(deftest register-rejects-non-keyword-test
  (testing "register-action! refuses non-keyword keys — programmer-error
            guard per standards rule 005"
    (is (thrown? IllegalArgumentException
                 (actions/register-action! "redirect/inc-count" bump-counter)))
    (is (thrown? IllegalArgumentException
                 (actions/register-action! nil bump-counter)))))

(deftest register-rejects-non-ifn-value-test
  (testing "register-action! refuses non-IFn values — programmer-error
            guard per standards rule 005"
    (is (thrown? IllegalArgumentException
                 (actions/register-action! :redirect/inc-count 42)))
    (is (thrown? IllegalArgumentException
                 (actions/register-action! :redirect/inc-count "string")))
    (is (thrown? IllegalArgumentException
                 (actions/register-action! :redirect/inc-count nil)))))

;------------------------------------------------------------------------------ Reference walker

(deftest references-empty-when-no-actions-test
  (testing "states with only bare-keyword transitions have zero action refs"
    (is (= [] (actions/action-references {})))
    (is (= [] (actions/action-references {:s {:on {:e :other-state}}})))))

(deftest references-extracts-from-single-map-test
  (testing "single-map transition's :actions vector surfaces keywords"
    (let [states {:phase-0-review
                  {:on {:phase/fail {:target :failed
                                     :actions [:redirect/inc-count]}}}}]
      (is (= [:redirect/inc-count] (actions/action-references states))))))

(deftest references-extracts-from-ordered-vector-test
  (testing "ordered guarded-array: every map-form :actions vector is surfaced"
    (let [states {:phase-0-review
                  {:on {:phase/fail
                        [{:target :failed}
                         {:target :implement
                          :actions [:redirect/inc-count :review/record-fingerprint]}
                         {:target :failed}]}}}]
      (is (= [:redirect/inc-count :review/record-fingerprint]
             (actions/action-references states))))))

(deftest references-dedupes-across-states-test
  (testing "same action referenced from two transitions surfaces once"
    (let [states {:s1 {:on {:e [{:target :a :actions [:redirect/inc-count]}]}}
                  :s2 {:on {:e [{:target :a :actions [:redirect/inc-count]}]}}}]
      (is (= [:redirect/inc-count] (actions/action-references states))))))

(deftest references-with-locations-test
  (testing "each reference carries its source {:state :event :action}"
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed
                                      :actions [:redirect/inc-count]}]}}}
          refs   (actions/action-references-with-locations states)]
      (is (= 1 (count refs)))
      (is (= {:state :phase-0-review
              :event :phase/fail
              :action :redirect/inc-count}
             (first refs))))))

;------------------------------------------------------------------------------ Validator

(deftest validate-returns-nil-for-clean-coverage-test
  (testing "every :actions ref resolves → validator returns nil"
    (actions/register-action! :redirect/inc-count bump-counter)
    (let [states {:s {:on {:e [{:target :a :actions [:redirect/inc-count]}]}}}]
      (is (nil? (actions/validate-action-references states))))))

(deftest validate-returns-nil-when-no-actions-test
  (testing "no actions anywhere → validator returns nil"
    (is (nil? (actions/validate-action-references
                {:s {:on {:e :other}}})))))

(deftest validate-flags-single-dangling-reference-test
  (testing "one unresolved :actions keyword → error with the offending ref"
    (let [states {:phase-0-review
                  {:on {:phase/fail [{:target :failed
                                      :actions [:redirect/inc-count]}]}}}
          result (actions/validate-action-references states)]
      (is (= :dangling-action-references (:error result)))
      (is (= 1 (count (:references result))))
      (is (= :redirect/inc-count (:action (first (:references result)))))
      (is (string? (:message result))))))

(deftest validate-flags-multiple-dangling-references-test
  (testing "two unresolved refs → both surfaced"
    (let [states {:s {:on {:e [{:target :a
                                 :actions [:redirect/inc-count :review/log]}]}}}
          result (actions/validate-action-references states)]
      (is (= :dangling-action-references (:error result)))
      (is (= #{:redirect/inc-count :review/log}
             (set (map :action (:references result))))))))

(deftest validate-distinguishes-registered-from-dangling-test
  (testing "one ref registered + one dangling → only the dangling is flagged"
    (actions/register-action! :redirect/inc-count bump-counter)
    (let [states {:s {:on {:e [{:target :a
                                 :actions [:redirect/inc-count :review/log]}]}}}
          result (actions/validate-action-references states)]
      (is (= 1 (count (:references result))))
      (is (= :review/log (:action (first (:references result))))))))

;------------------------------------------------------------------------------ Resolver

(deftest resolve-substitutes-registered-fns-test
  (testing "resolve-action-keywords replaces keyword refs with fns"
    (actions/register-action! :redirect/inc-count bump-counter)
    (let [states  {:phase-0-review
                   {:on {:phase/fail [{:target :failed
                                       :actions [:redirect/inc-count]}]}}}
          resolved (actions/resolve-action-keywords states)
          entry    (-> resolved :phase-0-review :on :phase/fail first)]
      (is (= bump-counter (-> entry :actions first))
          "keyword replaced by the registered fn"))))

(deftest resolve-leaves-non-keyword-actions-untouched-test
  (testing "actions that are already fns pass through resolution"
    (let [states  {:s {:on {:e [{:target :a :actions [bump-counter]}]}}}
          resolved (actions/resolve-action-keywords states)
          entry   (-> resolved :s :on :e first)]
      (is (= bump-counter (-> entry :actions first))))))

(deftest resolve-throws-on-unregistered-keyword-test
  (testing "an unregistered keyword that survived validation throws
            IllegalStateException at resolve time — programmer-error per
            standards rule 005, because the validator should have caught
            it before this step"
    (let [states {:s {:on {:e [{:target :a :actions [:not/registered]}]}}}]
      (is (thrown? IllegalStateException
                   (actions/resolve-action-keywords states))))))

(deftest resolve-handles-bare-keyword-transitions-test
  (testing "transition that is just a keyword (target state-id, no actions)
            passes through resolution unchanged"
    (let [states {:s {:on {:e :other-state}}}]
      (is (= states (actions/resolve-action-keywords states))))))
