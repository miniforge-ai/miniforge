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

(ns ai.miniforge.workflow.fsm-test
  "Tests for workflow FSM."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.fsm.interface :as engine]
   [ai.miniforge.workflow.fsm :as fsm]
   [ai.miniforge.workflow.runner-defaults :as defaults]))

(deftest workflow-states-test
  (testing "All workflow states are defined"
    (is (contains? fsm/workflow-states :pending))
    (is (contains? fsm/workflow-states :running))
    (is (contains? fsm/workflow-states :completed))
    (is (contains? fsm/workflow-states :failed))
    (is (contains? fsm/workflow-states :paused))
    (is (contains? fsm/workflow-states :cancelled))))

(deftest valid-transition-test
  (testing "Valid transitions from pending"
    (is (fsm/valid-transition? :pending :start)))

  (testing "Valid transitions from running"
    (is (fsm/valid-transition? :running :complete))
    (is (fsm/valid-transition? :running :fail))
    (is (fsm/valid-transition? :running :pause))
    (is (fsm/valid-transition? :running :cancel)))

  (testing "Valid transitions from paused"
    (is (fsm/valid-transition? :paused :resume))
    (is (fsm/valid-transition? :paused :cancel)))

  (testing "Invalid transitions"
    (is (not (fsm/valid-transition? :pending :complete)))
    (is (not (fsm/valid-transition? :pending :fail)))
    (is (not (fsm/valid-transition? :completed :start)))
    (is (not (fsm/valid-transition? :failed :resume)))))

(deftest terminal-state-test
  (testing "Terminal states"
    (is (fsm/terminal-state? :completed))
    (is (fsm/terminal-state? :failed))
    (is (fsm/terminal-state? :cancelled)))

  (testing "Non-terminal states"
    (is (not (fsm/terminal-state? :pending)))
    (is (not (fsm/terminal-state? :running)))
    (is (not (fsm/terminal-state? :paused)))))

(deftest next-state-test
  (testing "Next state for valid transitions"
    (is (= :running (fsm/next-state :pending :start)))
    (is (= :completed (fsm/next-state :running :complete)))
    (is (= :failed (fsm/next-state :running :fail)))
    (is (= :paused (fsm/next-state :running :pause)))
    (is (= :cancelled (fsm/next-state :running :cancel)))
    (is (= :running (fsm/next-state :paused :resume)))
    (is (= :cancelled (fsm/next-state :paused :cancel))))

  (testing "Next state for invalid transitions"
    (is (nil? (fsm/next-state :pending :complete)))
    (is (nil? (fsm/next-state :completed :start)))))

(deftest transition-test
  (testing "Successful transitions"
    (let [result (fsm/transition :pending :start)]
      (is (:success? result))
      (is (= :running (:state result)))))

  (testing "Failed transition - terminal state"
    (let [result (fsm/transition :completed :start)]
      (is (not (:success? result)))
      (is (= :terminal-state (:error result)))))

  (testing "Failed transition - invalid state"
    (let [result (fsm/transition :invalid-state :start)]
      (is (not (:success? result)))
      (is (= :invalid-state (:error result)))))

  (testing "Failed transition - invalid transition"
    (let [result (fsm/transition :pending :complete)]
      (is (not (:success? result)))
      (is (= :invalid-transition (:error result)))))

  (testing "Transition with guard function"
    (let [guard (fn [_state _event] false)
          result (fsm/transition :pending :start guard)]
      (is (not (:success? result)))
      (is (= :guard-failed (:error result))))))

(deftest valid-state-test
  (testing "Valid states"
    (is (fsm/valid-state? :pending))
    (is (fsm/valid-state? :running))
    (is (fsm/valid-state? :completed)))

  (testing "Invalid states"
    (is (not (fsm/valid-state? :invalid)))
    (is (not (fsm/valid-state? :unknown)))))

(deftest get-available-events-test
  (testing "Available events from pending"
    (let [events (fsm/get-available-events :pending)]
      (is (= [:start] events))))

  (testing "Available events from running"
    (let [events (set (fsm/get-available-events :running))]
      (is (= #{:complete :fail :pause :cancel} events))))

  (testing "Available events from paused"
    (let [events (set (fsm/get-available-events :paused))]
      (is (= #{:resume :cancel} events))))

  (testing "No available events from terminal states"
    (is (empty? (fsm/get-available-events :completed)))
    (is (empty? (fsm/get-available-events :failed)))
    (is (empty? (fsm/get-available-events :cancelled)))))

(deftest fsm-graph-test
  (testing "FSM graph structure"
    (let [graph (fsm/fsm-graph)]
      (is (set? (:states graph)))
      (is (vector? (:transitions graph)))
      (is (set? (:terminal-states graph)))
      (is (= fsm/workflow-states (:states graph)))
      (is (= fsm/terminal-states (:terminal-states graph))))))

(deftest fsm-workflow-lifecycle-test
  (testing "Complete workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Complete: running -> completed
      (let [r2 (fsm/transition (:state r1) :complete)]
        (is (:success? r2))
        (is (= :completed (:state r2)))

        ;; Cannot transition from terminal state
        (let [r3 (fsm/transition (:state r2) :start)]
          (is (not (:success? r3)))
          (is (= :terminal-state (:error r3)))))))

  (testing "Failed workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Fail: running -> failed
      (let [r2 (fsm/transition (:state r1) :fail)]
        (is (:success? r2))
        (is (= :failed (:state r2))))))

  (testing "Paused workflow lifecycle"
    ;; Start: pending -> running
    (let [r1 (fsm/transition :pending :start)]
      (is (:success? r1))
      (is (= :running (:state r1)))

      ;; Pause: running -> paused
      (let [r2 (fsm/transition (:state r1) :pause)]
        (is (:success? r2))
        (is (= :paused (:state r2)))

        ;; Resume: paused -> running
        (let [r3 (fsm/transition (:state r2) :resume)]
          (is (:success? r3))
          (is (= :running (:state r3)))

          ;; Complete: running -> completed
          (let [r4 (fsm/transition (:state r3) :complete)]
            (is (:success? r4))
            (is (= :completed (:state r4)))))))))

(deftest compiled-execution-machine-projects-phase-state-test
  (let [workflow {:workflow/id :test
                  :workflow/pipeline [{:phase :plan}
                                      {:phase :implement}
                                      {:phase :done}]}
        machine (fsm/compile-execution-machine workflow)
        pending-state (fsm/initialize-execution machine)
        running-state (fsm/start-execution machine pending-state)]
    (testing "pending state projects no active phase"
      (is (= {:execution/status :pending
              :execution/current-phase nil
              :execution/phase-index nil
              :execution/redirect-count 0}
             (fsm/execution-projection machine pending-state))))
    (testing "start enters the first pipeline phase"
      (is (= :running (fsm/execution-status machine running-state)))
      (is (= :plan (fsm/current-phase-id machine running-state)))
      (is (= 0 (fsm/current-phase-index machine running-state))))
    (testing "active machine entry includes the phase config and index"
      (is (= {:index 0
              :phase :plan
              :config {:phase :plan}
              :state-id :phase-0-plan
              :paused-state-id :paused-phase-0-plan}
             (fsm/machine-active-phase-entry machine running-state))))))

(deftest compiled-execution-machine-follows-phase-transitions-test
  (let [workflow {:workflow/id :test
                  :workflow/pipeline [{:phase :plan}
                                      {:phase :implement}
                                      {:phase :verify :on-fail :implement}
                                      {:phase :done}]}
        machine (fsm/compile-execution-machine workflow)
        s0 (fsm/start-execution machine (fsm/initialize-execution machine))
        s1 (fsm/transition-execution machine s0 :phase/succeed)
        s2 (fsm/transition-execution machine s1 :phase/succeed)
        ;; Phase 4b: redirect now flows through the verdict-driven
        ;; guarded `:phase/fail` array — non-terminal verdict +
        ;; configured on-fail → on-fail target + counter bump via the
        ;; `:redirect/inc-count` action. No more per-target
        ;; redirect-to-X event keyword.
        s3 (fsm/transition-execution machine s2
                                     {:type :phase/fail
                                      :phase/verdict :repair-requested})
        s4 (fsm/transition-execution machine s3 :pause)
        s5 (fsm/transition-execution machine s4 :resume)
        s6 (fsm/transition-execution machine s5 :phase/succeed)
        s7 (fsm/transition-execution machine s6 :phase/already-done)]
    (testing "success transitions advance through the compiled phase graph"
      (is (= :implement (fsm/current-phase-id machine s1)))
      (is (= :verify (fsm/current-phase-id machine s2))))
    (testing "redirect transitions return to the configured phase and increment redirect count"
      (is (= :implement (fsm/current-phase-id machine s3)))
      (is (= 1 (:execution/redirect-count (fsm/execution-projection machine s3)))))
    (testing "pause and resume are phase-local machine states"
      (is (= :paused (fsm/execution-status machine s4)))
      (is (= :implement (fsm/current-phase-id machine s4)))
      (is (= :running (fsm/execution-status machine s5))))
    (testing "already-done transitions jump to :done and then completion"
      (is (= :verify (fsm/current-phase-id machine s6)))
      (is (= :done (fsm/current-phase-id machine s7))))))

;; Phase 4a removed the `:phase/terminal-fail` event + the test that
;; pinned its bypass-on-fail semantics. The verdict-driven guarded
;; `:phase/fail` array (Phase 2b/3) handles every case the legacy event
;; was introduced for — see `review-phase-verdict-routes-fail-via-
;; guarded-array-test` below for the superseding assertions.

;------------------------------------------------------------------------------ Phase 2b: verdict-driven :phase/fail array

(deftest review-phase-verdict-routes-fail-via-guarded-array-test
  (testing "Phase 2b: the review phase's :phase/fail dispatch reads
            :phase/verdict from the event. A terminal verdict
            (:stagnated / :needs-decomposition / :exhausted) routes
            straight to :failed bypassing :on-fail, while a
            :repair-requested verdict (within budget) takes the on-fail
            redirect AND bumps :redirect-count via the registered
            :redirect/inc-count action."
    (let [workflow {:workflow/id :verdict-array-test
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :implement}
                                        {:phase :verify}
                                        {:phase :review :on-fail :implement}
                                        {:phase :done}]}
          machine (fsm/compile-execution-machine workflow)
          at-review (->> (fsm/initialize-execution machine)
                         (fsm/start-execution machine)
                         (#(fsm/transition-execution machine % :phase/succeed))
                         (#(fsm/transition-execution machine % :phase/succeed))
                         (#(fsm/transition-execution machine % :phase/succeed)))]
      (is (= :review (fsm/current-phase-id machine at-review)))
      (testing ":phase/verdict :stagnated terminates straight to :failed"
        (let [evt {:type :phase/fail :phase/verdict :stagnated}
              after (fsm/transition-execution machine at-review evt)]
          (is (= :failed (fsm/execution-status machine after))
              "terminal verdict bypasses :on-fail :implement")))
      (testing ":phase/verdict :needs-decomposition terminates straight to :failed"
        (let [evt {:type :phase/fail :phase/verdict :needs-decomposition}
              after (fsm/transition-execution machine at-review evt)]
          (is (= :failed (fsm/execution-status machine after)))))
      (testing ":phase/verdict :exhausted terminates straight to :failed"
        (let [evt {:type :phase/fail :phase/verdict :exhausted}
              after (fsm/transition-execution machine at-review evt)]
          (is (= :failed (fsm/execution-status machine after)))))
      (testing ":review/backend-timeout is a TRANSIENT infra verdict — it
                retries the review phase on its dedicated infra budget and
                NEVER redirects into on-fail :implement (a dead provider
                wouldn't recover via an implement cycle). Distinct from
                :exhausted (LLM HAD a verdict the gate couldn't approve)
                and :rejected (real defect), which are work-terminal."
        (let [evt {:type :phase/fail :phase/verdict :review/backend-timeout}
              after (fsm/transition-execution machine at-review evt)]
          (is (= :review (fsm/current-phase-id machine after))
              "infra verdict retries the SAME phase, not on-fail :implement")
          (is (= 1 (get after :infra-retry-count 0))
              "consumes the dedicated infra budget")
          (is (= 0 (get after :redirect-count 0))
              "the redirect/work budget is untouched"))
        (testing "infra budget spent → terminal :failed (still never on-fail)"
          (let [maxed (engine/update-context
                       at-review assoc :infra-retry-count (defaults/max-infra-retries))
                after (fsm/transition-execution
                       machine maxed {:type :phase/fail :phase/verdict :review/backend-timeout})]
            (is (= :failed (fsm/execution-status machine after))))))
      (testing ":phase/verdict :repair-requested follows on-fail to :implement"
        (let [evt {:type :phase/fail :phase/verdict :repair-requested}
              after (fsm/transition-execution machine at-review evt)]
          (is (= :implement (fsm/current-phase-id machine after))
              "non-terminal verdict + on-fail set → redirect branch fires")
          (is (= 1 (get after :redirect-count 0))
              ":redirect/inc-count action bumped the FSM-context counter
               — the SINGLE accounting site that Phase 4 will rely on
               when the runner's parallel namespace-sniff check is removed")))
      (testing "budget-redirects-spent? guard pre-empts the redirect when
                the FSM-context :redirect-count is already at the ceiling"
        (let [max-r (defaults/max-redirects)
              over-budget (engine/update-context at-review assoc :redirect-count max-r)
              evt {:type :phase/fail :phase/verdict :repair-requested}
              after (fsm/transition-execution machine over-budget evt)]
          (is (= :failed (fsm/execution-status machine after))
              "budget-spent guard takes the second :failed branch
               before the redirect branch fires"))))))

(deftest verify-phase-verdict-routes-fail-via-guarded-array-test
  (testing "Phase 3: verify phase now ALSO uses the guarded :phase/fail
            array. :verify/timeout and :verify/rate-limited are TRANSIENT
            infra verdicts — they retry the verify phase on a dedicated
            infra budget, go terminal once it is spent, and never redirect
            to on-fail :implement — fixes the 2026-05-28 dogfood bottleneck
            where verify timed out, looped to implement, implementer wrote
            a recovery, verify timed out again, repeat for hours."
    (let [workflow {:workflow/id :verify-verdict-test
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :implement}
                                        {:phase :verify :on-fail :implement}
                                        {:phase :review}
                                        {:phase :done}]}
          machine (fsm/compile-execution-machine workflow)
          at-verify (->> (fsm/initialize-execution machine)
                         (fsm/start-execution machine)
                         (#(fsm/transition-execution machine % :phase/succeed))
                         (#(fsm/transition-execution machine % :phase/succeed)))]
      (is (= :verify (fsm/current-phase-id machine at-verify)))
      (testing ":verify/timeout is a TRANSIENT infra verdict — retries the
                verify phase on its infra budget; never redirects to
                on-fail :implement (retrying the implementer can't unblock
                a hung test process)."
        (let [evt {:type :phase/fail :phase/verdict :verify/timeout}
              after (fsm/transition-execution machine at-verify evt)]
          (is (= :verify (fsm/current-phase-id machine after))
              "infra verdict retries the SAME phase, not on-fail :implement")
          (is (= 1 (get after :infra-retry-count 0)) "consumes the infra budget")
          (is (= 0 (get after :redirect-count 0)) "redirect budget untouched")))
      (testing ":verify/rate-limited retries verify on the infra budget"
        (let [evt {:type :phase/fail :phase/verdict :verify/rate-limited}
              after (fsm/transition-execution machine at-verify evt)]
          (is (= :verify (fsm/current-phase-id machine after)))
          (is (= 1 (get after :infra-retry-count 0)))))
      (testing "verify infra budget spent → terminal :failed (never on-fail)"
        (let [maxed (engine/update-context
                     at-verify assoc :infra-retry-count (defaults/max-infra-retries))
              after (fsm/transition-execution
                     machine maxed {:type :phase/fail :phase/verdict :verify/timeout})]
          (is (= :failed (fsm/execution-status machine after)))))
      (testing ":repair-requested follows on-fail to :implement (baseline)"
        (let [evt {:type :phase/fail :phase/verdict :repair-requested}
              after (fsm/transition-execution machine at-verify evt)]
          (is (= :implement (fsm/current-phase-id machine after)))
          (is (= 1 (get after :redirect-count 0))
              "single accounting site bumps for verify→implement too"))))))

(deftest release-phase-verdict-routes-fail-via-guarded-array-test
  (testing "Phase 3b: release phase joins the guarded :phase/fail array.
            `:release/zero-files` (curator's empty-diff verdict) is
            terminal — bypasses on-fail because retrying the implementer
            wouldn't change the curator's decision (the diff is empty;
            the next pass would just produce another empty diff)."
    (let [workflow {:workflow/id :release-verdict-test
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :implement}
                                        {:phase :verify}
                                        {:phase :review}
                                        {:phase :release :on-fail :implement}
                                        {:phase :done}]}
          machine (fsm/compile-execution-machine workflow)
          at-release (->> (fsm/initialize-execution machine)
                          (fsm/start-execution machine)
                          (#(fsm/transition-execution machine % :phase/succeed))
                          (#(fsm/transition-execution machine % :phase/succeed))
                          (#(fsm/transition-execution machine % :phase/succeed))
                          (#(fsm/transition-execution machine % :phase/succeed)))]
      (is (= :release (fsm/current-phase-id machine at-release)))
      (testing ":release/zero-files terminates straight to :failed"
        (let [evt {:type :phase/fail :phase/verdict :release/zero-files}
              after (fsm/transition-execution machine at-release evt)]
          (is (= :failed (fsm/execution-status machine after))
              "curator's empty-diff verdict MUST NOT redirect")))
      (testing ":repair-requested follows on-fail to :implement"
        (let [evt {:type :phase/fail :phase/verdict :repair-requested}
              after (fsm/transition-execution machine at-release evt)]
          (is (= :implement (fsm/current-phase-id machine after)))
          (is (= 1 (get after :redirect-count 0))
              "single accounting site bumps for release→implement too"))))))

(deftest implement-phase-verdict-routes-fail-via-guarded-array-test
  (testing "Phase 3c: implement joins the guarded :phase/fail array.
            :implement/empty-diff and :implement/already-implemented-invalid
            are work-terminal (retrying would hit the same wall).
            :implement/rate-limited, :implement/network-dropped, and
            :implement/backend-timeout are TRANSIENT infra verdicts — they
            retry the implement phase on a dedicated infra budget, go
            terminal once it is spent, and never redirect to on-fail."
    (let [workflow {:workflow/id :impl-verdict-test
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :implement :on-fail :plan}
                                        {:phase :verify}
                                        {:phase :review}
                                        {:phase :done}]}
          machine (fsm/compile-execution-machine workflow)
          at-implement (->> (fsm/initialize-execution machine)
                            (fsm/start-execution machine)
                            (#(fsm/transition-execution machine % :phase/succeed)))]
      (is (= :implement (fsm/current-phase-id machine at-implement)))
      (testing ":implement/rate-limited is a TRANSIENT infra verdict —
                retries implement on its infra budget; never redirects to
                on-fail :plan (provider quota won't change between attempts,
                but the budget bounds the spin)."
        (let [evt {:type :phase/fail :phase/verdict :implement/rate-limited}
              after (fsm/transition-execution machine at-implement evt)]
          (is (= :implement (fsm/current-phase-id machine after))
              "infra verdict retries the SAME phase, not on-fail :plan")
          (is (= 1 (get after :infra-retry-count 0)) "consumes the infra budget")
          (is (= 0 (get after :redirect-count 0)) "redirect budget untouched")))
      (testing ":implement/empty-diff terminates straight to :failed"
        (let [evt {:type :phase/fail :phase/verdict :implement/empty-diff}
              after (fsm/transition-execution machine at-implement evt)]
          (is (= :failed (fsm/execution-status machine after))
              "curator's no-files-written verdict MUST NOT redirect")))
      (testing ":implement/already-implemented-invalid terminates"
        (let [evt {:type :phase/fail
                   :phase/verdict :implement/already-implemented-invalid}
              after (fsm/transition-execution machine at-implement evt)]
          (is (= :failed (fsm/execution-status machine after)))))
      (testing ":implement/network-dropped is a TRANSIENT infra verdict —
                retries implement on its infra budget before going
                terminal; never redirects to on-fail :plan (the operator
                surfaces for manual resume once connectivity is restored)."
        (let [evt {:type :phase/fail :phase/verdict :implement/network-dropped}
              after (fsm/transition-execution machine at-implement evt)]
          (is (= :implement (fsm/current-phase-id machine after)))
          (is (= 1 (get after :infra-retry-count 0)))))
      (testing ":implement/backend-timeout is a TRANSIENT infra verdict —
                retries implement on its infra budget; companion to
                :review/backend-timeout, same shape on the implement phase.
                Never redirects to on-fail :plan."
        (let [evt {:type :phase/fail :phase/verdict :implement/backend-timeout}
              after (fsm/transition-execution machine at-implement evt)]
          (is (= :implement (fsm/current-phase-id machine after)))
          (is (= 1 (get after :infra-retry-count 0)))))
      (testing "implement infra budget spent → terminal :failed (never on-fail)"
        (let [maxed (engine/update-context
                     at-implement assoc :infra-retry-count (defaults/max-infra-retries))
              after (fsm/transition-execution
                     machine maxed {:type :phase/fail :phase/verdict :implement/backend-timeout})]
          (is (= :failed (fsm/execution-status machine after)))))
      (testing ":repair-requested follows on-fail (here :plan) + counter"
        (let [evt {:type :phase/fail :phase/verdict :repair-requested}
              after (fsm/transition-execution machine at-implement evt)]
          (is (= :plan (fsm/current-phase-id machine after)))
          (is (= 1 (get after :redirect-count 0))
              "single accounting site for implement→on-fail too"))))))

(deftest state-graph-walks-guarded-array-transitions-test
  ;; Phase 2a permits transition values shaped as ordered vectors of
  ;; map entries (guarded arrays — first matching guard wins; each
  ;; branch is a reachable edge). The state-graph walker used by the
  ;; reachability validator must traverse every map's `:target` slot
  ;; in the array, not just bare-keyword + single-map shapes — else
  ;; phases reachable only via a guarded branch are misreported as
  ;; unreachable.
  (testing "guarded-array transitions contribute every branch's :target
            to the state graph"
    (let [states {:s1 {:on {:event-a [{:target :s2 :guard :always}
                                       {:target :s3}]}}
                  :s2 {:type :final}
                  :s3 {:type :final}}
          graph  (#'fsm/build-state-graph states)]
      (is (= #{:s2 :s3} (get graph :s1))
          "BOTH branches should be edges from :s1; pre-Phase-2a code
           returned #{} because (keep transition-target) discarded the
           vector"))))

;------------------------------------------------------------------------------ Phase 5: structural invariants

(defn- production-shape-workflow
  "A workflow that includes all four work-loop guarded phases so the
   structural-invariant tests can synthesize per-state mutations
   (extra :redirect/inc-count action, removed default branch, etc.)
   against a realistic compile target."
  []
  {:workflow/id :phase5-structural-test
   :workflow/pipeline [{:phase :plan}
                       {:phase :implement :on-fail :plan}
                       {:phase :verify    :on-fail :implement}
                       {:phase :review    :on-fail :implement}
                       {:phase :release   :on-fail :implement}
                       {:phase :done}]})

(deftest structural-invariants-clean-on-production-workflow-test
  (testing "the production-shape workflow (every guarded phase) emits a
            machine that passes ALL three Phase 5 structural invariants.
            Phase 5 is locking IN the post-Phase-4 shape — the baseline
            must validate."
    (let [result (fsm/validate-execution-machine (production-shape-workflow))]
      (is (:valid? result)
          (str "production workflow must validate; errors: " (:errors result)))
      (is (empty? (filter (comp #{:guarded-fail-missing-default
                                  :multiple-redirect-counting-actions
                                  :budget-guard-misordered}
                                :error)
                          (:errors result)))
          "no structural-invariant errors on the production shape"))))

(deftest structural-invariant-guarded-fail-missing-default-test
  (testing "if a state's guarded :phase/fail array has no default
            (no-guard) branch, the structural-invariant aggregator
            surfaces a :guarded-fail-missing-default error so the
            regression is caught at workflow validation time, not at
            the next dogfood.

            clj-statecharts picks the FIRST matching guard in document
            order; without a default a transition that fails every
            guard would be silently dropped."
    (let [bad-states
          ;; Every entry has a `:guard`. clj-statecharts picks the
          ;; first matching guard in document order; if every entry
          ;; specifies a guard and the runtime event matches none of
          ;; them, the transition is silently dropped. The structural
          ;; invariant catches this by requiring AT LEAST ONE entry
          ;; without a `:guard` slot — the catch-all.
          {:phase-0-review
           {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                              {:target :failed :guard :budget/redirects-spent?}
                              {:target :phase-1-implement
                               :guard   :config/on-fail-set?
                               :actions [:redirect/inc-count]}
                              ;; NO catch-all entry — every branch
                              ;; conditional, so a non-matching event
                              ;; would slip through.
                              ]}}}
          errs (#'fsm/structural-invariant-errors bad-states)]
      (is (some #(= :guarded-fail-missing-default (:error %)) errs)
          "missing-default error must surface")
      (let [err (first (filter #(= :guarded-fail-missing-default (:error %)) errs))]
        (is (= :phase-0-review (:state err))
            "error names the offending state"))))

  (testing "end-to-end via validate-execution-machine: a workflow that
            produces a default-less guarded array is invalid. The
            structural-invariant errors flow through the same
            :errors vector the existing validator surfaces."
    ;; Synthesize the bad-states map and inject it via with-redefs of
    ;; build-raw-states so validate-execution-machine wires it through
    ;; the real error-aggregation path (not just the inner helper).
    (let [base-workflow {:workflow/id :phase5-missing-default-e2e
                         :workflow/pipeline [{:phase :plan}
                                             {:phase :review :on-fail :plan}
                                             {:phase :done}]}
          original-build-raw @#'fsm/build-raw-states
          strip-default      (fn [build]
                               (let [states (:states build)
                                     review-id (some #(when (= :review (-> (val %) (get-in [:on :phase/fail])
                                                                            (as-> t (when (sequential? t) :unused))))
                                                        (key %))
                                                     states)
                                     ;; Find any guarded :phase/fail state and strip default
                                     fixed-states
                                     (into {}
                                           (map (fn [[sid scfg]]
                                                  (let [t (get-in scfg [:on :phase/fail])]
                                                    (if (sequential? t)
                                                      [sid (assoc-in scfg [:on :phase/fail]
                                                                     (vec (remove #(and (map? %)
                                                                                        (not (contains? % :guard)))
                                                                                  t)))]
                                                      [sid scfg]))))
                                           states)]
                                 (assoc build :states fixed-states)))]
      (with-redefs [fsm/build-raw-states (fn [wf] (strip-default (original-build-raw wf)))]
        (let [result (fsm/validate-execution-machine base-workflow)]
          (is (not (:valid? result))
              "workflow with a default-less guarded array must validate as invalid")
          (is (some #(= :guarded-fail-missing-default (:error %)) (:errors result))
              "the structural error flows through :errors"))))))

(deftest structural-invariant-multiple-redirect-actions-test
  (testing "if a single state's guarded :phase/fail array lists
            :redirect/inc-count on more than one entry, the validator
            surfaces a :multiple-redirect-counting-actions error.
            Locks out double-counting (channel-A's regression vector)."
    (let [bad-states
          {:phase-0-review
           {:on {:phase/fail [{:target :failed
                               :guard :verdict/terminal?}
                              ;; TWO entries with the redirect action
                              {:target :phase-1-implement
                               :actions [:redirect/inc-count]}
                              {:target :phase-1-implement
                               :actions [:redirect/inc-count :review/record-fingerprint]}
                              {:target :failed}]}}}
          errs (#'fsm/structural-invariant-errors bad-states)]
      (is (some #(= :multiple-redirect-counting-actions (:error %)) errs)
          "multi-redirect error must surface")
      (let [err (first (filter #(= :multiple-redirect-counting-actions (:error %)) errs))]
        (is (= :phase-0-review (:state err))
            "error names the offending state")
        (is (= [1 2] (:entry-indices err))
            "error lists the indices of the offending entries")))))

(deftest structural-invariant-budget-guard-misordered-test
  (testing "if the redirect branch precedes the :budget/redirects-spent?
            guard in document order, the budget check would never fire
            (clj-statecharts picks the first matching branch). Validator
            surfaces a :budget-guard-misordered error."
    (let [bad-states
          {:phase-0-review
           {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                              ;; redirect FIRST — wrong order
                              {:target :phase-1-implement
                               :actions [:redirect/inc-count]}
                              ;; budget check AFTER, would never fire
                              {:target :failed :guard :budget/redirects-spent?}
                              {:target :failed}]}}}
          errs (#'fsm/structural-invariant-errors bad-states)]
      (is (some #(= :budget-guard-misordered (:error %)) errs)
          "budget-misordered error must surface")
      (let [err (first (filter #(= :budget-guard-misordered (:error %)) errs))]
        (is (= :phase-0-review (:state err)))
        (is (< (:redirect-index err) (:budget-guard-index err))
            "the error data names the redirect-before-budget mismatch"))))

  (testing "a redirect branch with NO :budget/redirects-spent? guard
            anywhere in the array surfaces :budget-guard-missing
            (distinct from :budget-guard-misordered so the diagnostic
            message names the actual problem — Copilot caught the
            single-error-keyword shape was confusing)."
    (let [bad-states
          {:phase-0-review
           {:on {:phase/fail [{:target :failed :guard :verdict/terminal?}
                              ;; No budget-check guard anywhere; just redirect
                              {:target :phase-1-implement
                               :actions [:redirect/inc-count]}
                              {:target :failed}]}}}
          errs (#'fsm/structural-invariant-errors bad-states)]
      (is (some #(= :budget-guard-missing (:error %)) errs)
          ":budget-guard-missing (not :budget-guard-misordered) when no
           budget guard is present at all")
      (is (not (some #(= :budget-guard-misordered (:error %)) errs))
          "missing and misordered are distinct error keywords"))))

(deftest compiled-execution-machine-reachability-test
  (let [workflow {:workflow/id :test
                  :workflow/pipeline [{:phase :plan}
                                      {:phase :implement}
                                      {:phase :done}]}
        machine (fsm/compile-execution-machine workflow)
        expected-reachable
        #{:pending
          :phase-0-plan
          :paused-phase-0-plan
          :phase-1-implement
          :paused-phase-1-implement
          :phase-2-done
          :paused-phase-2-done
          :completed
          :failed
          :cancelled}]
    (testing "initial reachability exactly matches the compiled machine state set"
      (is (= expected-reachable
             (fsm/compiled-state-ids machine)))
      (is (= expected-reachable
             (fsm/reachable-states machine)))
      (is (= #{}
             (fsm/unreachable-states machine))))
    (testing "terminal states cannot reach active workflow states"
      (is (= #{:completed}
             (fsm/reachable-states machine :completed)))
      (is (= (disj expected-reachable :completed)
             (fsm/unreachable-states machine :completed))))))

(deftest validate-execution-machine-test
  (testing "detects unresolved transition targets"
    (let [result (fsm/validate-execution-machine
                  {:workflow/id :bad
                   :workflow/pipeline [{:phase :plan :on-success :missing}]})]
      (is (false? (:valid? result)))
      (is (= :unknown-on-success-target (-> result :errors first :error)))))
  (testing "warns on duplicate phase ids because target resolution becomes ambiguous"
    (let [result (fsm/validate-execution-machine
                  {:workflow/id :dup
                   :workflow/pipeline [{:phase :plan}
                                       {:phase :plan}]})]
      (is (true? (:valid? result)))
      (is (= :duplicate-phase-identifiers (-> result :warnings first :warning)))))
  (testing "rejects compiled workflows with unreachable phase states"
    (let [result (fsm/validate-execution-machine
                  {:workflow/id :skip-impl
                   :workflow/pipeline [{:phase :plan :on-success :verify}
                                       {:phase :implement}
                                       {:phase :verify :on-success :done}
                                       {:phase :done}]})]
      (is (false? (:valid? result)))
      (is (some #(= :unreachable-phase-states (:error %))
                (:errors result)))
      (is (some #(= :unreachable-machine-states (:error %))
                (:errors result))))))
