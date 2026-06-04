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
   [ai.miniforge.phase.phase-result :as phase-result]
   [ai.miniforge.workflow.execution :as exec]
   [ai.miniforge.workflow.fsm :as workflow-fsm]))

;; -------------------------------------------------------------------------- redirect-target

(deftest redirect-target-returns-target-when-redirect-requested
  (testing "redirect-target returns the target phase when transition-request is set"
    (let [result (phase-result/request-redirect {:status :failed} :implement)]
      (is (= :implement (#'exec/redirect-target result))
          "redirect-target must extract the target from the transition-request"))))

(deftest redirect-target-returns-nil-when-no-redirect
  (testing "redirect-target returns nil on results that don't request redirect"
    (is (nil? (#'exec/redirect-target {:status :success})))
    (is (nil? (#'exec/redirect-target {:status :failed}))
        "a bare :failed result with no transition-request must not look like a redirect")
    (is (nil? (#'exec/redirect-target {:status :failed
                                       :phase/transition-request
                                       {:transition/type :transition/retry}}))
        "non-redirect transition requests must NOT match")))

;; -------------------------------------------------------------------------- determine-phase-event
;;
;; Six branches in priority order. Test in priority order so a regression
;; that swaps two adjacent branches surfaces clearly.

(deftest determine-phase-event-retrying-wins-over-success
  (testing "retrying status produces :phase/retry — even if later branches would also match"
    (is (= :phase/retry
           (exec/determine-phase-event {} {:status :retrying})))
    ;; Retrying should win over a redirect signal too.
    (is (= :phase/retry
           (exec/determine-phase-event
             {}
             (phase-result/request-redirect {:status :retrying} :implement))))))

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

(deftest determine-phase-event-failure-with-redirect-target
  (testing "a failed result that requested a redirect produces the redirect event for the target"
    (let [result (phase-result/request-redirect {:status :failed} :implement)]
      (is (= (workflow-fsm/redirect-event :implement)
             (exec/determine-phase-event {} result))
          "redirect-event keyword must be in the workflow.redirect/redirect-to-X namespace"))))

(deftest determine-phase-event-failure-without-redirect-target
  (testing "a failed result with NO redirect target produces :phase/fail"
    (is (= :phase/fail
           (exec/determine-phase-event {} {:status :failed})))))

(deftest determine-phase-event-verdict-failure-emits-map-event
  (testing "Phase 4a (superseding the deleted :phase/terminal-fail
            workaround): a failed phase that attached a `:phase/verdict`
            produces a MAP event `{:type :phase/fail :phase/verdict v}`
            so the FSM's guarded-array `:verdict/terminal?` guard can
            read the verdict directly. This is the post-Phase-3
            equivalent of the old :phase/terminal-fail branch — verdict
            on the result, dispatch on the guard, single accounting
            site via `:redirect/inc-count`."
    (is (= {:type :phase/fail :phase/verdict :stagnated}
           (exec/determine-phase-event
             {} {:status :failed :output {:phase/verdict :stagnated}})))
    (is (= {:type :phase/fail :phase/verdict :needs-decomposition}
           (exec/determine-phase-event
             {} {:status :failed :output {:phase/verdict :needs-decomposition}})))
    (is (= {:type :phase/fail :phase/verdict :verify/timeout}
           (exec/determine-phase-event
             {} {:status :failed :output {:phase/verdict :verify/timeout}})))))

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

(deftest apply-phase-transition-redirect-over-budget-transitions-to-failed
  (testing "redirect event past max-redirects skips the FSM and flips to failed via transition-to-failed-fn"
    (let [ctx (-> (ctx-at-plan-active)
                  (assoc :execution/redirect-count exec/max-redirects))
          redirect-event (workflow-fsm/redirect-event :implement)
          out (exec/apply-phase-transition ctx redirect-event [] identity always-fail)]
      (is (true? (:test/transition-to-failed-called? out))
          "redirect-over-budget MUST route through transition-to-failed-fn — silent FSM advance was the regression")
      (is (some #(= :max-redirects-exceeded (:type %)) (:execution/errors out))
          ":max-redirects-exceeded error must land in :execution/errors")
      (is (= :failed (:execution/status out))
          "ctx status must be :failed after over-budget redirect"))))

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

(deftest determine-then-apply-redirect-chain-fails-at-max-budget
  (testing "running determine-phase-event into apply-phase-transition over budget routes to failed"
    (let [result (phase-result/request-redirect {:status :failed} :implement)
          event (exec/determine-phase-event {} result)
          ctx (-> (ctx-at-plan-active)
                  (assoc :execution/redirect-count exec/max-redirects))
          out (exec/apply-phase-transition ctx event [] identity always-fail)]
      (is (true? (:test/transition-to-failed-called? out))
          "end-to-end: redirect-requested + over-budget → :failed"))))

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
