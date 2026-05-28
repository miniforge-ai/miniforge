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
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase-software-factory.review]
   [ai.miniforge.phase-software-factory.implement]))

(def phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

;; ============================================================================
;; leave-review status tests
;; ============================================================================

(def ^:private default-review-issues
  [{:severity :blocking
    :description "Missing require"}])

(def ^:private first-review-iteration
  1)

(def ^:private next-repair-attempt
  2)

(def ^:private review-repair-budget
  4)

(def ^:private default-review-issue-summary
  (:description (first default-review-issues)))

(defn simulate-leave-review-context
  "Simulate the leave-review logic and return the full context."
  ([decision iterations max-iterations]
   (simulate-leave-review-context
    decision iterations max-iterations {:review/issues default-review-issues}))
  ([decision iterations max-iterations review-output]
   (let [result {:output (merge {:review/decision decision} review-output)
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
     result-ctx)))

(defn simulate-leave-review
  "Simulate the leave-review logic for a given decision and iteration state.
   Returns the phase map after leave-review processing."
  ([decision iterations max-iterations]
   (simulate-leave-review decision iterations max-iterations {:review/issues default-review-issues}))
  ([decision iterations max-iterations review-output]
   (:phase (simulate-leave-review-context decision iterations max-iterations review-output))))

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
      (is (= default-review-issues
             (:review-feedback phase))
          "Should contain the review issues"))))

(deftest changes-requested-stores-repair-handoff
  (testing "changes-requested stores a typed repair handoff"
    (let [result-ctx (simulate-leave-review-context
                      :changes-requested first-review-iteration review-repair-budget)
          handoff (get-in result-ctx [:phase :phase/handoff])
          finding (first (get-in handoff [:frame/body :repair/findings]))]
      (is (= :repair-request (:frame/kind handoff)))
      (is (= :review (:transition/from handoff)))
      (is (= :implement (:transition/to handoff)))
      (is (= next-repair-attempt (:phase/attempt handoff)))
      (is (= next-repair-attempt (get-in handoff [:frame/body :repair/attempt])))
      (is (not (contains? (:frame/body handoff) :repair/raw-feedback)))
      (is (= [handoff] (:execution/phase-handoffs result-ctx)))
      (is (= default-review-issue-summary (:finding/summary finding))))))

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

(deftest rejected-without-actionable-feedback-fails-closed
  (testing "operational reviewer rejection with no repair feedback does not redirect"
    (let [phase (simulate-leave-review :rejected 1 4 {})]
      (is (phase/failed? phase))
      (is (not (phase/redirect-requested? phase))
          "No redirect when reviewer produced no actionable feedback for implement")
      (is (nil? (:review-feedback phase))))))

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

(deftest implement-task-includes-phase-handoff-from-context
  (testing "build-implement-task passes phase handoff through to task"
    (let [build-implement-task #'ai.miniforge.phase-software-factory.implement/build-implement-task
          phase-handoff {:frame/kind :repair-request
                         :transition/from :review
                         :transition/to :implement
                         :frame/body {:repair/findings
                                      [{:finding/summary "GROUP 3 missing"}]}}
          ctx {:execution/worktree-path "/tmp/test-worktree"
               :execution/input {:description "Build widget"}
               :execution/phase-results {:plan {:result {:output nil}}
                                         :review {:phase/handoff phase-handoff
                                                  :result {:output {:review/decision :changes-requested}}}}}
          {:keys [task]} (build-implement-task ctx)]
      (is (= phase-handoff (:task/phase-handoff task))))))

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

(def ^:private first-iteration
  "Iteration counter at the very first review."
  1)

(def ^:private second-iteration
  "Iteration counter when the first repair has already happened."
  2)

(def ^:private default-max-iterations
  "Iteration cap that lets the test reach the second review without
   bumping into the budget — only stagnation should short-circuit."
  4)

(def ^:private mock-token-count    100)
(def ^:private mock-duration-ms    5000)
(def ^:private mock-elapsed-ms     1000)
(def ^:private one-fingerprint     1)
(def ^:private two-fingerprints    2)

(defn- run-leave-review
  "Run leave-review against a custom ctx and return the resulting full ctx."
  [{:keys [issues iterations max-iterations prior-fingerprints]
    :or   {iterations          first-iteration
           max-iterations      default-max-iterations
           prior-fingerprints  []}}]
  (let [result {:output  {:review/decision :changes-requested
                          :review/issues issues}
                :metrics {:tokens mock-token-count :duration-ms mock-duration-ms}}
        ctx {:phase {:started-at (- (System/currentTimeMillis) mock-elapsed-ms)
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
          first-pass-ctx  (run-leave-review {:issues issues
                                             :iterations first-iteration})
          first-fp        (peek (get-in first-pass-ctx [:execution :review-fingerprints]))
          stagnated-ctx   (run-leave-review {:issues issues
                                             :iterations second-iteration
                                             :prior-fingerprints [first-fp]})
          phase           (:phase stagnated-ctx)]
      (is (true? (:stagnated? phase))
          "phase tagged stagnated when current fingerprint matches prior")
      (is (not (phase/redirect-requested? phase))
          "no redirect to :implement on stagnation — repair loop is the burn we are stopping")
      (is (anomaly/anomaly? (:error phase))
          ":phase :error is a canonical anomaly map (W2 convergence)")
      (is (= :exhausted (get-in phase [:error :anomaly/type]))
          "stagnation is an :exhausted type (review repair budget spent without progress)")
      (is (= :anomalies.review/stagnation
             (get-in phase [:error :anomaly/subtype]))
          ":anomalies.review/stagnation preserved as :anomaly/subtype")
      (is (some? (get-in phase [:error :anomaly/message]))
          ":anomaly/message set so display/diagnostic consumers don't show blank")
      (is (>= (count (get-in phase [:error :anomaly/data :review/fingerprint-history]))
              two-fingerprints)
          "fingerprint history carries the chain that proved stagnation"))))

(deftest non-stagnant-progress-still-redirects-test
  (testing "fingerprint changed between iterations ⇒ ordinary repair redirect"
    (let [first-fp     (run-leave-review {:issues [blocking-issue]
                                          :iterations first-iteration})
          first-print  (peek (get-in first-fp [:execution :review-fingerprints]))
          progressed   (run-leave-review {:issues [different-blocking-issue]
                                          :iterations second-iteration
                                          :prior-fingerprints [first-print]})
          phase        (:phase progressed)]
      (is (not (:stagnated? phase))
          "different fingerprint ⇒ not stagnated")
      (is (= :implement (phase/transition-target phase))
          "ordinary repair: redirect to implement")
      (is (nil? (get-in phase [:error :anomaly/subtype]))
          "no stagnation anomaly when progress is detected"))))

(deftest first-iteration-never-stagnates-test
  (testing "no prior fingerprint history ⇒ first review must not short-circuit"
    (let [phase (:phase (run-leave-review {:issues [blocking-issue]
                                           :iterations first-iteration
                                           :prior-fingerprints []}))]
      (is (not (:stagnated? phase))
          "first review iteration is never stagnation")
      (is (= :implement (phase/transition-target phase))
          "first :changes-requested still redirects normally"))))

(deftest fingerprint-recorded-on-every-review-test
  (testing "every review iteration appends its fingerprint to the execution history"
    (let [final-ctx (run-leave-review {:issues [blocking-issue]
                                       :iterations first-iteration})]
      (is (= one-fingerprint (count (get-in final-ctx [:execution :review-fingerprints])))
          "first iteration appends one fingerprint"))
    (let [seed-fp   [[:blocking "src/seed.clj" 1 "seed-description"]]
          final-ctx (run-leave-review {:issues [different-blocking-issue]
                                       :iterations second-iteration
                                       :prior-fingerprints [seed-fp]})]
      (is (= two-fingerprints (count (get-in final-ctx [:execution :review-fingerprints])))
          "second iteration appends without dropping prior history"))))

;;----------------------------------------------------------------------------- Convergence cap (:needs-decomposition)

(def ^:private third-iteration
  "Iteration counter when two repair cycles have already happened (the
   reviewer is now on its third rejection in a row) — the default cap."
  3)

(def ^:private prior-distinct-fingerprints
  "Two distinct prior fingerprints — stagnation must NOT fire (it requires
   IDENTICAL adjacent fingerprints), so the convergence cap is what should
   trigger when the third review also rejects with yet a different blocker."
  [{:review/fingerprint :fp-1}
   {:review/fingerprint :fp-2}])

(def ^:private yet-another-blocking-issue
  {:severity :blocking :file "src/baz.clj" :line 7 :description "And worse"})

(def ^:private default-convergence-cap
  "Default `max-no-progress-attempts` — matches `default-max-no-progress-attempts`
   in review.clj. Locks the magic number to the shipped value so test math stays
   honest if either side drifts."
  3)

(def ^:private cycles-at-cap
  "Number of completed review cycles that should trip the convergence cap.
   Equals the cap itself: cycle 1 (no prior) + cycle 2 (one prior) + cycle 3
   (two prior) = cap-3 reached on the third review."
  default-convergence-cap)

(deftest needs-decomposition-fires-after-n-non-stagnated-rejections-test
  (testing "3 consecutive non-stagnated review rejections (different blockers
            each pass) trip :needs-decomposition — the implementer is fixing
            prior findings but new legitimate ones keep surfacing, signal the
            task needs splitting rather than burning more redirect budget.

            Note: the cap keys off the TASK-LEVEL fingerprint history
            (`(inc (count prior-fingerprints))`), not `:phase :iterations`.
            The phase-local counter resets per phase entry and the shipped
            review `:budget :iterations` is 2, so the cap MUST count cycles
            across re-entries or it would be unreachable in production."
    (let [final-ctx (run-leave-review {:issues             [yet-another-blocking-issue]
                                       :iterations         third-iteration
                                       :prior-fingerprints prior-distinct-fingerprints})
          phase (:phase final-ctx)]
      (is (true? (:needs-decomposition? phase))
          "phase tagged :needs-decomposition?")
      (is (= :anomalies.review/needs-decomposition
             (get-in phase [:error :anomaly/category]))
          "convergence-cap anomaly attached")
      (is (= cycles-at-cap (get-in phase [:error :review/cycle-count]))
          "task-level cycle count carried in the anomaly")
      (is (not (phase/redirect-requested? phase))
          "no redirect-to-implement on :needs-decomposition — repair budget conserved"))))

(deftest needs-decomposition-yields-to-stagnation-test
  (testing "when the new fingerprint matches the immediate prior one, stagnation
            wins over :needs-decomposition (identical-loop is the sharper signal).
            Seed with two prior fingerprints so the task-level cycle count would
            otherwise hit the cap — proves stagnation pre-empts."
    (let [seed-issue {:severity :blocking :description "same"}
          seed-ctx   (run-leave-review {:issues [seed-issue] :iterations first-iteration})
          seed-fp    (peek (get-in seed-ctx [:execution :review-fingerprints]))
          ;; Pad to 2 distinct priors so cycle count would tip the cap if
          ;; stagnation didn't fire first. Last prior must equal current fp
          ;; for stagnation to trigger.
          padded-priors [{:review/fingerprint :prelude} seed-fp]
          final-ctx  (run-leave-review {:issues             [seed-issue]
                                        :iterations         third-iteration
                                        :prior-fingerprints padded-priors})
          phase (:phase final-ctx)]
      (is (true? (:stagnated? phase)) "stagnation fires, not :needs-decomposition")
      (is (nil? (:needs-decomposition? phase))))))

(deftest needs-decomposition-does-not-fire-below-cap-test
  (testing "two cycles total (one prior + this review) — below the default
            cap of 3 — still redirect normally"
    (let [final-ctx (run-leave-review {:issues             [different-blocking-issue]
                                       :iterations         second-iteration
                                       :prior-fingerprints [{:review/fingerprint :only}]})
          phase (:phase final-ctx)]
      (is (nil? (:needs-decomposition? phase)) "below cap, no decomposition signal")
      (is (phase/redirect-requested? phase) "still redirects on a normal repair cycle"))))
