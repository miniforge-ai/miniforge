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

(ns ai.miniforge.phase-software-factory.review-repair-loop-test
  "Tests for the review → implement repair loop.

   Validates that when a reviewer returns :changes-requested, the execution
   engine redirects back to :implement (not re-running :review in place)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-software-factory.review]
   [ai.miniforge.phase-software-factory.implement]))

;; ============================================================================
;; leave-review status tests
;; ============================================================================

(defn simulate-leave-review
  "Simulate the leave-review logic for a given decision and iteration state.
   Returns the phase map after leave-review processing."
  [decision iterations max-iterations]
  (let [result {:output {:review/decision decision
                         :review/issues [{:severity :blocking
                                          :description "Missing require"}]}
                :metrics {:tokens 100 :duration-ms 5000}}
        ctx {:phase {:started-at (- (System/currentTimeMillis) 1000)
                     :iterations iterations
                     :budget {:iterations max-iterations}
                     :result result}
             :phase-config {:phase :review}
             :execution/phase-results {}
             :execution/input {:description "test task"}
             :execution/metrics {}}
        ;; Call leave-review via the interceptor
        interceptor (phase/get-phase-interceptor {:phase :review})
        leave-fn (:leave interceptor)
        result-ctx (leave-fn ctx)]
    (:phase result-ctx)))

;; ============================================================================
;; Core behavior tests
;; ============================================================================

(deftest changes-requested-sets-failed-status
  (testing "changes-requested sets :failed status (not :retrying)"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (phase/failed? phase)
          "Status should be :failed so execution can follow the transition request")
      (is (not (phase/retrying? phase))
          "Should NOT be retrying (would cause review to re-run in place)"))))

(deftest changes-requested-within-budget-redirects-to-implement
  (testing "changes-requested within budget requests redirect to :implement"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (= :implement (phase/transition-target phase))
          "Should redirect to implement for repair")
      (is (phase/failed? phase)
          "Must be failed for the transition request to be honored by execution"))))

(deftest changes-requested-over-budget-no-redirect
  (testing "changes-requested at max iterations fails without redirect"
    (let [phase (simulate-leave-review :changes-requested 4 4)]
      (is (phase/failed? phase)
          "Should be failed")
      (is (not (phase/redirect-requested? phase))
          "Should NOT redirect — iteration budget exhausted"))))

(deftest changes-requested-stores-review-feedback
  (testing "changes-requested stores review issues as feedback"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (some? (:review-feedback phase))
          "Review feedback should be stored for implement to consume")
      (is (= [{:severity :blocking :description "Missing require"}]
             (:review-feedback phase))
          "Should contain the review issues"))))

(deftest rejected-redirects-to-implement-like-changes-requested
  (testing "iter-23 regression: :rejected decision must trigger redirect, not :completed"
    (let [phase (simulate-leave-review :rejected 1 4)]
      (is (phase/failed? phase)
          "Rejected must set :failed — falling through to :completed lets an empty-diff PR open")
      (is (= :implement (phase/transition-target phase))
          "Rejected within budget must redirect to implement like :changes-requested"))))

(deftest rejected-over-budget-no-redirect
  (testing ":rejected at max iterations fails terminally (no redirect)"
    (let [phase (simulate-leave-review :rejected 4 4)]
      (is (phase/failed? phase))
      (is (not (phase/redirect-requested? phase))))))

(deftest approved-sets-completed-status
  (testing "approved review sets :completed status"
    (let [phase (simulate-leave-review :approved 1 4)]
      (is (phase/succeeded? phase)
          "Approved review should complete normally")
      (is (not (phase/redirect-requested? phase))
          "No redirect needed for approved review"))))

(deftest iteration-counter-incremented-on-redirect
  (testing "iteration counter increments when redirecting"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (= 2 (:iterations phase))
          "Iterations should increment from 1 to 2"))))

;; ============================================================================
;; Execution engine integration: failed + transition request triggers redirect
;; ============================================================================

(deftest failed-with-transition-request-not-confused-with-retrying
  (testing "execution engine distinguishes failed+transition-request from retrying"
    (let [phase-result (phase/request-redirect {:status :failed} :implement)]
      ;; The key invariant: failed? is true, retrying? is false
      (is (phase/failed? phase-result)
          "Should be detected as failed")
      (is (not (phase/retrying? phase-result))
          "Should NOT be detected as retrying")
      ;; This means execution will hit the redirect branch,
      ;; not the retrying branch (which would stay at current index)
      )))

(deftest retrying-status-stays-at-current-phase
  (testing "retrying status would stay at current phase (the old broken behavior)"
    (let [phase-result {:status :retrying}]
      (is (phase/retrying? phase-result)
          "retrying? returns true for :retrying status")
      ;; This is why the old code was broken: :retrying caused review
      ;; to re-run itself instead of redirecting to implement
      )))

;; ============================================================================
;; Build-implement-task includes review feedback from context
;; ============================================================================

(deftest implement-task-includes-review-feedback-from-context
  (testing "build-implement-task passes review-feedback through to task"
    ;; The implement phase reads review feedback from execution/phase-results
    ;; (survives :phase clearing between phases) and includes it as :task/review-feedback
    (let [build-implement-task #'ai.miniforge.phase-software-factory.implement/build-implement-task
          ctx {:execution/worktree-path "/tmp/test-worktree"
               :execution/input {:description "Build widget"}
               :execution/phase-results {:plan {:result {:output nil}}
                                         :review {:result {:output {:review/decision :changes-requested
                                                                     :review/feedback [{:severity :blocking
                                                                                         :description "Missing require"}]}}}}}
          {:keys [task]} (build-implement-task ctx)]
      (is (= [{:severity :blocking :description "Missing require"}]
             (:task/review-feedback task))
          "Task should include review feedback from context"))))

(deftest implement-task-omits-review-feedback-when-absent
  (testing "build-implement-task works normally without review feedback"
    (let [build-implement-task #'ai.miniforge.phase-software-factory.implement/build-implement-task
          ctx {:execution/worktree-path "/tmp/test-worktree"
               :execution/input {:description "Build widget"}
               :execution/phase-results {:plan {:result {:output nil}}}}
          {:keys [task]} (build-implement-task ctx)]
      (is (nil? (:task/review-feedback task))
          "Task should not have review feedback when none in context"))))

;; ============================================================================
;; Stagnation detector
;;
;; The review→implement loop must terminate with :anomalies.review/stagnation
;; when the reviewer's actionable-issue fingerprint is identical to the prior
;; iteration's. Catching it here saves the next implement+verify+review cycle.

(def ^:private blocking-issue
  {:severity :blocking :file "src/foo.clj" :line 12 :description "Bad"})

(def ^:private different-blocking-issue
  {:severity :blocking :file "src/bar.clj" :line 4 :description "Worse"})

(defn- run-leave-review
  "Run leave-review against a custom ctx and return the resulting full ctx."
  [{:keys [issues iterations max-iterations prior-fingerprints]
    :or   {iterations 1 max-iterations 4 prior-fingerprints []}}]
  (let [result {:output  {:review/decision :changes-requested
                          :review/issues issues}
                :metrics {:tokens 100 :duration-ms 5000}}
        ctx {:phase {:started-at (- (System/currentTimeMillis) 1000)
                     :iterations iterations
                     :budget {:iterations max-iterations}
                     :result result}
             :phase-config {:phase :review}
             :execution {:review-fingerprints prior-fingerprints}
             :execution/phase-results {}
             :execution/input {:description "test task"}
             :execution/metrics {}}
        interceptor (phase/get-phase-interceptor {:phase :review})]
    ((:leave interceptor) ctx)))

(deftest stagnation-terminates-instead-of-redirecting-test
  (testing "two consecutive identical fingerprints ⇒ no redirect, anomaly attached"
    (let [issues          [blocking-issue]
          first-pass-ctx  (run-leave-review {:issues issues :iterations 1})
          first-fp        (peek (get-in first-pass-ctx [:execution :review-fingerprints]))
          stagnated-ctx   (run-leave-review {:issues issues
                                             :iterations 2
                                             :prior-fingerprints [first-fp]})
          phase           (:phase stagnated-ctx)]
      (is (true? (:stagnated? phase))
          "phase tagged stagnated when current fingerprint matches prior")
      (is (not (phase/redirect-requested? phase))
          "no redirect to :implement on stagnation — repair loop is the burn we are stopping")
      (is (= :anomalies.review/stagnation
             (get-in phase [:error :anomaly/category]))
          ":anomalies.review/stagnation anomaly attached to :phase :error")
      (is (>= (count (get-in phase [:error :review/fingerprint-history])) 2)
          "fingerprint history carries the chain that proved stagnation"))))

(deftest non-stagnant-progress-still-redirects-test
  (testing "fingerprint changed between iterations ⇒ ordinary repair redirect"
    (let [first-fp     (run-leave-review {:issues [blocking-issue]
                                          :iterations 1})
          first-print  (peek (get-in first-fp [:execution :review-fingerprints]))
          progressed   (run-leave-review {:issues [different-blocking-issue]
                                          :iterations 2
                                          :prior-fingerprints [first-print]})
          phase        (:phase progressed)]
      (is (not (:stagnated? phase))
          "different fingerprint ⇒ not stagnated")
      (is (= :implement (phase/transition-target phase))
          "ordinary repair: redirect to implement")
      (is (nil? (get-in phase [:error :anomaly/category]))
          "no stagnation anomaly when progress is detected"))))

(deftest first-iteration-never-stagnates-test
  (testing "no prior fingerprint history ⇒ first review must not short-circuit"
    (let [phase (:phase (run-leave-review {:issues [blocking-issue]
                                           :iterations 1
                                           :prior-fingerprints []}))]
      (is (not (:stagnated? phase))
          "first review iteration is never stagnation")
      (is (= :implement (phase/transition-target phase))
          "first :changes-requested still redirects normally"))))

(deftest fingerprint-recorded-on-every-review-test
  (testing "every review iteration appends its fingerprint to the execution history"
    (let [final-ctx (run-leave-review {:issues [blocking-issue] :iterations 1})]
      (is (= 1 (count (get-in final-ctx [:execution :review-fingerprints])))
          "first iteration appends one fingerprint"))
    (let [seed-fp [[:blocking "src/seed.clj" 1 (hash "seed")]]
          final-ctx (run-leave-review {:issues [different-blocking-issue]
                                       :iterations 2
                                       :prior-fingerprints [seed-fp]})]
      (is (= 2 (count (get-in final-ctx [:execution :review-fingerprints])))
          "second iteration appends without dropping prior history"))))
