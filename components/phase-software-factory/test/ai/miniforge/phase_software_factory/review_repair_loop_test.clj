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
   [ai.miniforge.phase-software-factory.review :as review]
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

(deftest changes-requested-within-budget-emits-repair-requested-verdict
  (testing "changes-requested within budget sets :phase/verdict :repair-requested
            on the phase result so the FSM's verdict-driven guarded
            :phase/fail array can route via :on-fail (Phase 2b)"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (= :repair-requested (:verdict phase))
          ":verdict is exposed on the phase map for callers/tests")
      (is (= :repair-requested (get-in phase [:result :output :phase/verdict]))
          ":phase/verdict on the phase result is what determine-phase-event reads")
      (is (phase/failed? phase)
          "Status stays :failed; the FSM's guarded array picks the on-fail target"))))

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

(deftest rejected-emits-repair-requested-verdict-like-changes-requested
  (testing "iter-23 regression guard (Phase 2b shape): :rejected must set
            :failed AND set verdict :repair-requested (NOT silently fall
            through to :completed and let an empty-diff PR open). The
            FSM's guarded array dispatches to on-fail :implement from
            there."
    (let [phase (simulate-leave-review :rejected 1 4)]
      (is (phase/failed? phase)
          "rejected must be :failed — falling through to :completed lets an empty-diff PR open")
      (is (= :repair-requested (:verdict phase))
          "verdict drives the FSM's on-fail dispatch"))))

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

(deftest stagnation-emits-stagnated-verdict-test
  (testing "Phase 2b: two consecutive identical fingerprints ⇒ verdict
            :stagnated. No call to request-redirect — the FSM's
            :verdict/terminal? guard routes :phase/fail straight to
            :failed, bypassing on-fail. Anomaly stays on :phase :error
            for the evidence bundle."
    (let [issues          [blocking-issue]
          first-pass-ctx  (run-leave-review {:issues issues
                                             :iterations first-iteration})
          first-fp        (peek (get-in first-pass-ctx [:execution :review-fingerprints]))
          stagnated-ctx   (run-leave-review {:issues issues
                                             :iterations second-iteration
                                             :prior-fingerprints [first-fp]})
          phase           (:phase stagnated-ctx)]
      (is (= :stagnated (:verdict phase))
          "phase verdict is :stagnated when current fp matches prior")
      (is (= :stagnated (get-in phase [:result :output :phase/verdict]))
          ":phase/verdict on the result is what determine-phase-event reads")
      (is (not (phase/redirect-requested? phase))
          "Phase 2b: review.clj never calls request-redirect anymore — the
           FSM owns routing, so no redirect is ever attached to the result")
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

(deftest non-stagnant-progress-emits-repair-requested-test
  (testing "Phase 2b: fingerprint changed between iterations ⇒ verdict
            :repair-requested. FSM routes to on-fail :implement via the
            third branch of the guarded array."
    (let [first-fp     (run-leave-review {:issues [blocking-issue]
                                          :iterations first-iteration})
          first-print  (peek (get-in first-fp [:execution :review-fingerprints]))
          progressed   (run-leave-review {:issues [different-blocking-issue]
                                          :iterations second-iteration
                                          :prior-fingerprints [first-print]})
          phase        (:phase progressed)]
      (is (= :repair-requested (:verdict phase))
          "different fingerprint ⇒ verdict :repair-requested (FSM redirects)")
      (is (nil? (get-in phase [:error :anomaly/subtype]))
          "no stagnation anomaly when progress is detected"))))

(deftest first-iteration-never-stagnates-test
  (testing "Phase 2b: no prior fingerprint history ⇒ first review must
            not short-circuit. Verdict is :repair-requested (within
            budget, has feedback) and the FSM dispatches to implement
            via on-fail."
    (let [phase (:phase (run-leave-review {:issues [blocking-issue]
                                           :iterations first-iteration
                                           :prior-fingerprints []}))]
      (is (not= :stagnated (:verdict phase))
          "first review iteration cannot be :stagnated")
      (is (= :repair-requested (:verdict phase))
          "first :changes-requested produces :repair-requested verdict"))))

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
  (testing "Phase 2b: 3 consecutive non-stagnated review rejections trip
            verdict :needs-decomposition. FSM's :verdict/terminal? guard
            routes to terminal :failed rather than on-fail redirect.

            Cap keys off task-level fingerprint history (`(inc (count
            prior))`), not phase-local :iterations — see the convergence
            cap PR #1011 commit message for why the latter is unreachable
            under shipped config."
    (let [final-ctx (run-leave-review {:issues             [yet-another-blocking-issue]
                                       :iterations         third-iteration
                                       :prior-fingerprints prior-distinct-fingerprints})
          phase (:phase final-ctx)]
      (is (= :needs-decomposition (:verdict phase))
          "verdict :needs-decomposition surfaces on the phase map")
      (is (= :needs-decomposition (get-in phase [:result :output :phase/verdict]))
          ":phase/verdict on the result is what determine-phase-event reads")
      (is (= :anomalies.review/needs-decomposition
             (get-in phase [:error :anomaly/category]))
          "convergence-cap anomaly attached")
      (is (= cycles-at-cap (get-in phase [:error :review/cycle-count]))
          "task-level cycle count carried in the anomaly"))))

(deftest needs-decomposition-yields-to-stagnation-test
  (testing "when the new fingerprint matches the immediate prior one,
            stagnation wins over :needs-decomposition (identical-loop is
            the sharper signal). Seed with two prior fingerprints so the
            task-level cycle count would otherwise hit the cap — proves
            stagnation pre-empts."
    (let [seed-issue {:severity :blocking :description "same"}
          seed-ctx   (run-leave-review {:issues [seed-issue] :iterations first-iteration})
          seed-fp    (peek (get-in seed-ctx [:execution :review-fingerprints]))
          padded-priors [{:review/fingerprint :prelude} seed-fp]
          final-ctx  (run-leave-review {:issues             [seed-issue]
                                        :iterations         third-iteration
                                        :prior-fingerprints padded-priors})
          phase (:phase final-ctx)]
      (is (= :stagnated (:verdict phase))
          "stagnation pre-empts :needs-decomposition in compute-verdict"))))

(deftest needs-decomposition-does-not-fire-below-cap-test
  (testing "Phase 2b: two cycles total (one prior + this review) — below
            the default cap of 3 — emit :repair-requested verdict so the
            FSM redirects via on-fail rather than terminating."
    (let [final-ctx (run-leave-review {:issues             [different-blocking-issue]
                                       :iterations         second-iteration
                                       :prior-fingerprints [{:review/fingerprint :only}]})
          phase (:phase final-ctx)]
      (is (not= :needs-decomposition (:verdict phase))
          "below cap, no decomposition verdict")
      (is (= :repair-requested (:verdict phase))
          "below cap with progress + actionable feedback = :repair-requested"))))

;; ============================================================================
;; Backend-timeout verdict — companion to PR-A (#1058)
;;
;; PR-A wires the reviewer agent to take a `:reviewer/backend-timeout`
;; error exit when the LLM call itself stream-idle'd (or stagnation /
;; hard-limit / generic timeout). PR-B's `compute-verdict` arm here
;; translates that error code into a `:review/backend-timeout` verdict
;; — terminal in the FSM's `terminal-verdicts` set — so an infra
;; timeout no longer cascade-fails the workflow through the
;; `:exhausted` / on-fail-implement path that would just hit the same
;; dead provider on the next pass.
;; ============================================================================

(defn- simulate-leave-review-backend-timeout-context
  "Simulate the leave-review logic when the reviewer agent returned a
   `:reviewer/backend-timeout` error result (the PR-A exit shape).

   The result mirrors what `artifact/timeout-only-error-result` wires
   through `result-boundary/error-response`: `:status :error` at the
   top of the result, the error data carries `:code :reviewer/backend-
   timeout`, no `:output` artifact."
  [iterations max-iterations]
  (let [result {:status :error
                :error {:message "Adaptive timeout: No stream output for 360000ms (type: stream-idle, elapsed: 360087ms)"
                        :data {:code :reviewer/backend-timeout
                               :blocking-issues ["Adaptive timeout: No stream output for 360000ms (type: stream-idle, elapsed: 360087ms)"]}}
                :metrics {:tokens 9 :duration-ms 360087}}
        ctx {:phase {:started-at (- (System/currentTimeMillis) 360087)
                     :iterations iterations
                     :budget {:iterations max-iterations}
                     :result result}
             :phase-config {:phase :review}
             :execution/phase-results {}
             :execution/input {:description "test task"}
             :execution/metrics {}}
        interceptor (phase/get-phase-interceptor {:phase :review})
        leave-fn (:leave interceptor)]
    (leave-fn ctx)))

(deftest backend-timeout-result-emits-review-backend-timeout-verdict
  ;; 2026-06-05 dogfood (workflow adhoc-944448986) repro: reviewer LLM
  ;; stream-idle'd at 360s; PR-A makes the reviewer return the
  ;; backend-timeout error code instead of synthesizing a false
  ;; `:rejected`. PR-B converts that error code into the verdict the
  ;; FSM reads to take the terminal branch.
  (testing "reviewer-agent :reviewer/backend-timeout error → :review/backend-timeout verdict"
    (let [final-ctx (simulate-leave-review-backend-timeout-context 1 2)
          phase (:phase final-ctx)]
      (is (= :review/backend-timeout (:verdict phase))
          ":verdict slot exposes the canonical backend-timeout signal")
      (is (= :review/backend-timeout
             (get-in phase [:result :output :phase/verdict]))
          "FSM-readable :phase/verdict carries the same verdict
           — `determine-phase-event` plumbs this into the
           `:verdict/terminal?` guard")))

  (testing "backend-timeout verdict takes priority over the within-budget repair branch
            — the LLM call NEVER produced a real verdict, so there's
            nothing to repair; redirecting to implement would just hit
            the same dead provider on the next review pass"
    (let [final-ctx (simulate-leave-review-backend-timeout-context 1 4)
          phase (:phase final-ctx)]
      (is (= :review/backend-timeout (:verdict phase))
          "even with iterations < max, backend-timeout pre-empts :repair-requested")
      (is (not= :repair-requested (:verdict phase))))))

(deftest backend-timeout-verdict-is-in-the-canonical-verdict-set
  (testing "compute-verdict's verdict tag set declares :review/backend-timeout
            — the FSM's terminal-verdicts wiring + `:verdict/terminal?`
            guard look up the verdict by exact keyword match, so the
            tag-set must enumerate it. Coverage here pins the public
            surface so a rename can't silently slip past."
    ;; Read the private var via the var; the resolved value is the set.
    (let [verdicts @#'review/verdicts]
      (is (contains? verdicts :review/backend-timeout))
      (is (contains? verdicts :approved))
      (is (contains? verdicts :exhausted))
      (is (contains? verdicts :stagnated))
      (is (contains? verdicts :needs-decomposition))
      (is (contains? verdicts :repair-requested)))))
