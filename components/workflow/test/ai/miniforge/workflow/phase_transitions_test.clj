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

(ns ai.miniforge.workflow.phase-transitions-test
  "Comprehensive coverage of the phase-transition decision + actuator
   surface — the seam where the runner picks an FSM event from a
   phase-result and applies it to the execution machine.

   Until this namespace, both `determine-phase-event` and
   `apply-phase-transition` had zero direct test coverage. Bugs in
   either (the wrong event for a redirect, a silent state-no-op,
   an unguarded cycle) only surfaced at dogfood time and cost hours
   to diagnose. Each branch of each function now has its own pin so
   the next regression flips the test red instead of silently
   propagating bad state down the pipeline."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.workflow.execution :as exec]
   [ai.miniforge.workflow.fsm :as workflow-fsm]
   ;; loaded for its eager guard/action registration (infra-retry guards)
   [ai.miniforge.workflow.standard-guards-and-actions]
   ;; loaded so the :review-approved gate is registered for the gate-validation tests
   [ai.miniforge.gate.policy]))

;; Phase 4b removed `exec/redirect-target` and the
;; `determine-phase-event-failure-with-redirect-target` test (which
;; pinned the per-target `workflow.redirect/redirect-to-X` event
;; keyword that the channel-A path used to emit). With handle-error
;; now emitting `:phase/verdict :repair-requested` instead of
;; `phase/request-redirect`, no production path produces a
;; redirect-target marker — the FSM's guarded `:phase/fail` array
;; reads the verdict instead. See
;; `determine-phase-event-verdict-failure-emits-map-event` below
;; for the superseding assertion.

;; -------------------------------------------------------------------------- determine-phase-event
;;
;; Branches in priority order. Test in priority order so a regression
;; that swaps two adjacent branches surfaces clearly.

(deftest determine-phase-event-retrying-wins-over-success
  (testing "retrying status produces :phase/retry"
    (is (= :phase/retry
           (exec/determine-phase-event {} {:status :retrying})))))

(deftest determine-phase-event-already-done
  (testing ":already-implemented and :already-satisfied both produce :phase/already-done"
    (is (= :phase/already-done
           (exec/determine-phase-event {} {:status :already-implemented})))
    (is (= :phase/already-done
           (exec/determine-phase-event {} {:status :already-satisfied})))))

(deftest determine-phase-event-success
  (testing "a :completed result with no transition request produces :phase/succeed"
    (is (= :phase/succeed
           (exec/determine-phase-event {} {:status :completed})))))

(deftest determine-phase-event-failure-without-redirect-target
  (testing "a failed result with NO redirect target produces :phase/fail"
    (is (= :phase/fail
           (exec/determine-phase-event {} {:status :failed})))))

(deftest determine-phase-event-verdict-failure-emits-map-event
  (testing "Phase 4a (superseding the deleted :phase/terminal-fail
            workaround): a failed phase that attached a `:phase/verdict`
            produces a MAP event `{:type :phase/fail :phase/verdict v}`
            so the FSM's guarded-array `:verdict/terminal?` guard can
            read the verdict directly.

            Production shape: leave-* fns assoc the verdict at
            `[:phase :result :output :phase/verdict]` on the ctx; the
            extracted phase-result (= the `:phase` map) carries it at
            `[:result :output :phase/verdict]`. Use that shape here so
            a regression in the extraction path surfaces as a test
            failure rather than as silently-dropped FSM events
            (which is what bit us between Phase 2b and Phase 4a)."
    (let [shape (fn [verdict]
                  {:status :failed
                   :result {:status :failed
                            :output {:phase/verdict verdict}}})]
      (is (= {:type :phase/fail :phase/verdict :stagnated}
             (exec/determine-phase-event {} (shape :stagnated))))
      (is (= {:type :phase/fail :phase/verdict :needs-decomposition}
             (exec/determine-phase-event {} (shape :needs-decomposition))))
      (is (= {:type :phase/fail :phase/verdict :verify/timeout}
             (exec/determine-phase-event {} (shape :verify/timeout)))))))

(deftest determine-phase-event-catchall-defaults-to-succeed
  (testing "an unrecognised status falls through to :phase/succeed (catch-all branch)"
    ;; This branch is the safety net for results whose status doesn't
    ;; match any of the predicates. Pin it explicitly — silent change
    ;; in the catch-all has caused real dogfood incidents before.
    (is (= :phase/succeed
           (exec/determine-phase-event {} {:status :unknown-status})))
    (is (= :phase/succeed
           (exec/determine-phase-event {} {}))
        "empty result also catch-alls to :phase/succeed — preserves the runner-test fixture path")))

;; -------------------------------------------------------------------------- apply-phase-transition
;;
;; Three branches: redirect-cycle limit hit, valid state-changing transition,
;; invalid no-op transition. Each tested with a real compiled execution
;; machine so the FSM behaviour matches production.

(defn- minimal-workflow []
  {:workflow/id      :transitions-test
   :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :plan}
                       {:phase :implement}]})

(defn- ctx-at-plan-active
  "Build a minimal execution context whose FSM is parked at plan-active
   so transitions have somewhere meaningful to go."
  []
  (let [wf      (minimal-workflow)
        machine (workflow-fsm/compile-execution-machine wf)
        state   (->> (workflow-fsm/initialize-execution machine)
                     (workflow-fsm/start-execution machine))]
    {:execution/fsm-machine  machine
     :execution/fsm-state    state
     :execution/redirect-count 0
     :execution/errors       []
     :execution/response-chain {:operation :transitions-test
                                :succeeded? true
                                :response-chain []}}))

(defn- always-fail
  "Stub for the transition-to-failed-fn parameter — flags the ctx as
   :failed via a sentinel key the assertions check for."
  [ctx]
  (assoc ctx :execution/status :failed
             :test/transition-to-failed-called? true))

;; Phase 4b removed `apply-phase-transition-redirect-over-budget-
;; transitions-to-failed`. The runner's parallel `is-redirect?` check
;; that the test pinned was deleted — the FSM's
;; `:budget/redirects-spent?` guard on the guarded `:phase/fail` array
;; enforces the same `max-redirects` ceiling, and the verdict-routes
;; integration tests in fsm_test.clj exercise it end-to-end with the
;; real compiled machine.

(deftest apply-phase-transition-state-changing-event-returns-next-ctx
  (testing "a valid event that moves the FSM forward returns the advanced ctx (no failure routing)"
    (let [ctx (ctx-at-plan-active)
          prior-state (:execution/fsm-state ctx)
          out (exec/apply-phase-transition ctx :phase/succeed [] identity always-fail)]
      (is (nil? (:test/transition-to-failed-called? out))
          "happy-path transition must NOT call transition-to-failed-fn")
      (is (not= prior-state (:execution/fsm-state out))
          ":execution/fsm-state must move forward"))))

(deftest apply-phase-transition-retry-event-tolerates-no-state-change
  (testing ":phase/retry is the one event allowed to leave the FSM at the same state"
    ;; The current FSM doesn't define :phase/retry transitions at every
    ;; state. apply-phase-transition specially allows :phase/retry to
    ;; pass through even when the state doesn't change — without this,
    ;; the retry path would be misclassified as an invalid transition
    ;; and flip the workflow to :failed.
    (let [ctx (ctx-at-plan-active)
          prior-state (:execution/fsm-state ctx)
          out (exec/apply-phase-transition ctx :phase/retry [] identity always-fail)]
      (is (nil? (:test/transition-to-failed-called? out))
          ":phase/retry must NEVER route to transition-to-failed-fn even if state is unchanged")
      (is (= prior-state (:execution/fsm-state out))
          ":phase/retry preserves the FSM state (no spurious advance)"))))

(deftest apply-phase-transition-invalid-event-without-state-change-fails-loud
  (testing "an undefined event that doesn't move the FSM is recognised as invalid"
    (let [ctx (ctx-at-plan-active)
          out (exec/apply-phase-transition ctx :phase/no-such-event [] identity always-fail)]
      (is (true? (:test/transition-to-failed-called? out))
          "undefined event with no state change MUST route through transition-to-failed-fn — silently swallowing the event was the regression we're guarding")
      (is (some #(= :invalid-transition (:type %)) (:execution/errors out))
          ":invalid-transition error must land in :execution/errors"))))

;; -------------------------------------------------------------------------- max-redirects guard semantics

(deftest max-redirects-is-finite-and-positive
  ;; This is the safety constant that prevents an infinite redirect loop.
  ;; Keep it numeric-pinned so a refactor to `nil` or a string doesn't
  ;; silently disable the guard.
  (is (pos-int? exec/max-redirects)
      "max-redirects must be a positive integer")
  (is (<= exec/max-redirects 100)
      "max-redirects must stay small enough that an unhappy loop ends fast"))

;; -------------------------------------------------------------------------- end-to-end determine→apply

;; Phase 4b removed `determine-then-apply-redirect-chain-fails-at-max-budget`.
;; The runner's parallel max-budget check is gone; the same end-to-end
;; assertion (over-budget redirect → terminal :failed) now lives in
;; fsm_test.clj's per-phase verdict-routes integration tests, where it
;; runs against the real compiled FSM with the guarded `:phase/fail`
;; array's `:budget/redirects-spent?` guard doing the work.

(deftest determine-then-apply-success-path-advances-fsm
  (testing "running determine then apply on a clean success result advances the FSM"
    (let [result {:status :completed}
          event (exec/determine-phase-event {} result)
          ctx (ctx-at-plan-active)
          prior-state (:execution/fsm-state ctx)
          out (exec/apply-phase-transition ctx event [] identity always-fail)]
      (is (= :phase/succeed event)
          "sanity: determine-phase-event picks :phase/succeed on :completed")
      (is (nil? (:test/transition-to-failed-called? out))
          "happy path must not flag the ctx as failed")
      (is (not= prior-state (:execution/fsm-state out))
          "happy path must move the FSM forward"))))

;; -------------------------------------------------------------------------- infra-retry (Fable §2.4 PR-B)
;;
;; A transient infrastructure verdict retries the SAME phase against a
;; dedicated infra budget — not the redirect/work budget — then terminates
;; once the budget is spent. Driven through the real compiled execution
;; machine so the guarded-array behaviour matches production.

(defn- guarded-phase-machine
  "Compile a single guarded-phase (implement) workflow and park the FSM at it."
  []
  (let [m (workflow-fsm/compile-execution-machine
           {:workflow/id :infra-retry-test
            :workflow/version "1.0.0"
            :workflow/pipeline [{:phase :implement}]})]
    {:machine m
     :state (->> (workflow-fsm/initialize-execution m)
                 (workflow-fsm/start-execution m))}))

(deftest infra-verdict-retries-same-phase-then-terminates
  (testing "infra verdict retries the same phase up to the infra budget, then
            terminates — and never touches the redirect/work budget"
    (let [{:keys [machine state]} (guarded-phase-machine)
          phase (:_state state)
          fail-infra (fn [s] (fsm/transition machine s
                                             {:type :phase/fail
                                              :phase/verdict :implement/rate-limited}))
          s1 (fail-infra state)
          s2 (fail-infra s1)
          s3 (fail-infra s2)]
      (is (= phase (:_state s1)) "1st infra fail retries the same phase, not :failed")
      (is (= phase (:_state s3)) "still at the phase while infra budget remains")
      (is (= 3 (:infra-retry-count s3)) "the infra counter bumps each retry")
      (is (= 0 (get s3 :redirect-count 0)) "redirect/work budget is untouched")
      (let [s4 (fail-infra s3)]
        (is (= :failed (:_state s4))
            "infra budget exhausted (>= max-infra-retries) → terminal :failed")))))

(deftest non-infra-verdict-does-not-consume-infra-budget
  (testing "a work-terminal verdict goes straight to :failed without retrying"
    (let [{:keys [machine state]} (guarded-phase-machine)
          out (fsm/transition machine state
                              {:type :phase/fail :phase/verdict :stagnated})]
      (is (= :failed (:_state out)) "terminal work verdict → :failed immediately")
      (is (= 0 (get out :infra-retry-count 0)) "no infra retry consumed"))))

;; -------------------------------------------------------------------------- apply-gate-validation (fail-closed)
;;
;; Gates validate the canonical phase :output ([:result :output]). When gates
;; are configured they MUST run even if that output is absent — skipping on a
;; nil artifact would let a phase bypass validation (fail-open). The gate
;; runner turns a nil artifact into a failed gate result.

(deftest apply-gate-validation-fails-closed-on-missing-output
  (testing "gates configured + no canonical :output → phase fails (not skipped)"
    (let [out (exec/apply-gate-validation {:config {:gates [:review-approved]}}
                                          {:result {}} {})]
      (is (= :failed (:phase/status out)))))
  (testing "gates configured + approved verdict at the canonical location → passes"
    (let [out (exec/apply-gate-validation {:config {:gates [:review-approved]}}
                                          {:result {:output {:review/decision :approved}}} {})]
      (is (not= :failed (:phase/status out)))))
  (testing "no gates configured → unchanged (nothing to validate)"
    (let [pr {:result {}}]
      (is (= pr (exec/apply-gate-validation {:config {:gates []}} pr {}))))))
