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

(ns ai.miniforge.workflow.fsm
  "Finite State Machine for workflow execution.

   Uses the fsm component (clj-statecharts wrapper) for state machine implementation.
   Defines explicit states, valid transitions, and guards for workflow execution.

   States:
   - :pending   - Initial state, workflow not started
   - :running   - Workflow is executing
   - :completed - Workflow completed successfully
   - :failed    - Workflow failed
   - :paused    - Workflow execution paused
   - :cancelled - Workflow cancelled by user

   Events:
   - :start   - Begin workflow execution
   - :complete - Mark workflow as completed
   - :fail    - Mark workflow as failed
   - :pause   - Pause execution
   - :resume  - Resume paused execution
   - :cancel  - Cancel workflow"
  (:require
   [clojure.set :as set]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.workflow.actions :as actions]
   [ai.miniforge.workflow.definition :as definition]
   [ai.miniforge.workflow.guards :as guards]
   [ai.miniforge.workflow.messages :as messages]
   ;; Side-effecting require — registers `:verdict/terminal?`,
   ;; `:budget/redirects-spent?` and `:redirect/inc-count` at load
   ;; time so `compile-execution-machine`'s resolver can substitute
   ;; them. Phase 2b consumes these from the review-phase
   ;; `:phase/fail` guarded array.
   [ai.miniforge.workflow.standard-guards-and-actions]))

(declare current-state first-phase-index reachable-states unreachable-states)

;; ============================================================================
;; FSM Definition using clj-statecharts
;; ============================================================================

;; Phase 4b removed `redirect-event-namespace`. The original
;; per-target redirect events (`workflow.event/redirect-to-<phase>`)
;; were the channel-A redirect mechanism the dogfood-class bug
;; exploited. Phase 2b/3 replaced them with the verdict-driven
;; guarded `:phase/fail` array; Phase 4b deletes the now-dead
;; per-target event keywords.

(def phase-state-prefix
  "Prefix used for active phase state identifiers."
  "phase")

(def paused-phase-state-prefix
  "Prefix used for paused phase state identifiers."
  "paused-phase")

(defn- phase-state-name
  [prefix index phase]
  (str prefix "-" index "-" (name phase)))

(def workflow-machine-config
  "Workflow execution state machine configuration."
  {:fsm/id :workflow-execution
   :fsm/initial :pending
   :fsm/context {}
   :fsm/states
   {:pending   {:on {:start :running}}
    :running   {:on {:complete :completed
                     :fail :failed
                     :pause :paused
                     :cancel :cancelled}}
    :paused    {:on {:resume :running
                     :cancel :cancelled}}
    :completed {:type :final}
    :failed    {:type :final}
    :cancelled {:type :final}}})

(def workflow-machine
  "Compiled workflow state machine."
  (fsm/define-machine workflow-machine-config))

;------------------------------------------------------------------------------
; Layer 0.5: Compiled execution machine

(defn phase-state-id
  "Build the active execution-machine state id for a pipeline phase."
  [index phase]
  (keyword (phase-state-name phase-state-prefix index phase)))

(defn paused-phase-state-id
  "Build the paused execution-machine state id for a pipeline phase."
  [index phase]
  (keyword (phase-state-name paused-phase-state-prefix index phase)))

(defn- increment-redirect-count
  [redirect-count]
  (inc (or redirect-count 0)))

(defn- matching-phase-index
  [phase [index config]]
  (when (= phase (:phase config))
    index))

(defn- duplicate-phase?
  [[_phase count]]
  (> count 1))

(defn- unresolved-target-error
  [error-key phase target]
  {:error error-key
   :phase phase
   :target target
   :message (messages/t error-key
                        {:phase phase
                         :target target})})

(defn- unknown-success-target
  [pipeline {:keys [phase on-success]}]
  (when (and on-success
             (nil? (first-phase-index pipeline on-success)))
    (unresolved-target-error :unknown-on-success-target phase on-success)))

(defn- unknown-fail-target
  [pipeline {:keys [phase on-fail]}]
  (when (and on-fail
             (nil? (first-phase-index pipeline on-fail)))
    (unresolved-target-error :unknown-on-fail-target phase on-fail)))

(defn- duplicate-phase-warning
  [duplicate-phases]
  {:warning :duplicate-phase-identifiers
   :phases duplicate-phases
   :message (messages/t :status/duplicate-phases)})

(defn- empty-pipeline-error
  []
  {:error :empty-pipeline
   :message (messages/t :status/empty-pipeline)})

(defn- unknown-entry-phase-error
  [entry-phase]
  {:error :unknown-entry-phase
   :entry-phase entry-phase
   :message (messages/t :status/unknown-entry-phase
                        {:entry-phase entry-phase})})

(defn- first-phase-index
  [pipeline phase]
  (some (partial matching-phase-index phase)
        (map-indexed vector pipeline)))

(defn- build-phase-entry
  [index config]
  (let [phase (:phase config)]
    {:index index
     :phase phase
     :config config
     :state-id (phase-state-id index phase)
     :paused-state-id (paused-phase-state-id index phase)}))

(defn- state-target
  [phase-entries index]
  (some-> (get phase-entries index) :state-id))

;; Phase 4b removed `redirect-transition` and `redirect-event?`.
;; The verdict-driven guarded `:phase/fail` array (Phase 2b/3) is the
;; only redirect mechanism now; budget accounting lives in the
;; `:redirect/inc-count` action on the guarded array's redirect branch
;; — the SINGLE accounting site the RFC's compile-time invariant will
;; enforce in Phase 5.

(defn transition-execution
  "Apply an event to a compiled execution machine snapshot.

   Pure delegation to the underlying FSM. Phase 4b removed the parallel
   `redirect-event?` namespace check that bumped `:redirect-count` for
   channel-A redirects — the FSM action `:redirect/inc-count` on the
   guarded `:phase/fail` array now handles every budget increment."
  [machine state event]
  (fsm/transition machine state event))

(defn- terminal-state-message
  [current-state]
  (messages/t :status/terminal-state-transition {:state current-state}))

(defn- invalid-state-message
  [current-state]
  (messages/t :status/invalid-current-state {:state current-state}))

(defn- no-transition-message
  [current-state event]
  (messages/t :status/no-transition-defined
              {:state current-state
               :event event}))

(defn- guarded-fail-transition
  "Emit the verdict-driven guarded `:phase/fail` array.

   Ordering matters — clj-statecharts picks the first guard whose
   predicate returns truthy. The compile-time choice to include or omit
   the redirect branch makes the runtime `:config/on-fail-set?` guard
   from the RFC's three-guard set unnecessary (when on-fail isn't
   configured, the branch is simply absent).

   * Branch 1: terminal verdict (`:stagnated` / `:needs-decomposition`
     / `:exhausted` / `:verify/timeout` / `:verify/rate-limited`) →
     straight to `:failed`. Bypasses on-fail.
   * Branch 2: redirect budget spent → straight to `:failed` (no more
     on-fail redirects).
   * Branch 3 (only when `:on-fail` is configured): the on-fail
     redirect, plus the `:redirect/inc-count` action that bumps the
     FSM-owned `:redirect-count` (single accounting site — Phase 4
     deletes the runner's parallel `redirect-event?` check).
   * Branch 4: default — no verdict, no on-fail, just fail."
  [failure-target on-fail-configured?]
  (if on-fail-configured?
    [{:target :failed         :guard :verdict/terminal?}
     {:target :failed         :guard :budget/redirects-spent?}
     {:target failure-target  :actions [:redirect/inc-count]}
     {:target :failed}]
    [{:target :failed :guard :verdict/terminal?}
     {:target :failed}]))

(def ^:private guarded-phases
  "Phases whose `:phase/fail` dispatch routes through the verdict-driven
   guarded array. Phase 2b started with :review; Phase 3a added :verify;
   Phase 3b added :release; Phase 3c adds :implement (where
   `:implement/rate-limited`, `:implement/empty-diff`, and
   `:implement/already-implemented-invalid` are terminal verdicts).
   Phase 4 drops this set and applies the guarded form to every phase
   unconditionally."
  #{:review :verify :release :implement})

(defn- guarded-phase?
  [config]
  (contains? guarded-phases (:phase config)))

(defn- build-phase-state
  [phase-entries {:keys [index config state-id paused-state-id]}]
  (let [terminal-phase? (:terminal? config)
        next-index (when (and (not terminal-phase?)
                              (< (inc index) (count phase-entries)))
                     (inc index))
        on-success-index (or (when-let [target-phase (:on-success config)]
                               (first-phase-index phase-entries target-phase))
                             next-index)
        on-fail-index (when-let [target-phase (:on-fail config)]
                        (first-phase-index phase-entries target-phase))
        done-index (first-phase-index phase-entries :done)
        success-target (or (state-target phase-entries on-success-index)
                           :completed)
        failure-target (or (state-target phase-entries on-fail-index)
                           :failed)
        already-done-target (or (state-target phase-entries done-index)
                                :completed)
        ;; Phase 2b/3: review + verify route :phase/fail through the
        ;; verdict-driven guarded array; release + implement keep the
        ;; flat shape until Phase 3b. Phase 4 drops the per-phase
        ;; opt-in and applies the guarded form unconditionally.
        phase-fail-transition (if (guarded-phase? config)
                                (guarded-fail-transition failure-target
                                                         (some? on-fail-index))
                                failure-target)]
    ;; Phase 4a: `:phase/terminal-fail` removed. The verdict-driven
    ;; guarded `:phase/fail` array (Phase 2b/3) is the superseding
    ;; mechanism — terminal verdicts bypass on-fail through the
    ;; `:verdict/terminal?` guard, not a parallel event keyword.
    ;; `determine-phase-event` no longer emits the event; production
    ;; code no longer sets the `:stagnated?` / `:needs-decomposition?`
    ;; flag bits the workaround read.
    [state-id
     {:on {:phase/retry state-id
           :phase/succeed success-target
           :phase/already-done already-done-target
           :phase/fail phase-fail-transition
           :pause paused-state-id
           :cancel :cancelled
           :fail :failed
           :complete :completed}}]))

(defn- build-paused-state
  [{:keys [state-id paused-state-id]}]
  [paused-state-id
   {:on {:resume state-id
         :cancel :cancelled
         :fail :failed
         :complete :completed}}])

(defn- projection-entry
  [entry paused?]
  [(:state-id entry) {:phase (:phase entry)
                      :index (:index entry)
                      :paused? paused?}])

(defn- paused-projection-entry
  [entry]
  [(:paused-state-id entry)
   {:phase (:phase entry)
    :index (:index entry)
    :paused? true}])

(defn- state-entry
  [entry state-id]
  [state-id entry])

(defn- paused-state-entry
  [entry]
  [(:paused-state-id entry) entry])

(defn- transition-targets
  "Return the set of target state-ids referenced by a single transition
   value. Handles the three transition shapes clj-statecharts accepts:
   bare keyword (target), single map (`:target` slot), and ordered
   vector of maps (guarded array — every map's `:target` is a reachable
   edge, since first matching guard wins but ANY of them MAY fire)."
  [transition]
  (cond
    (keyword? transition)    #{transition}
    (map? transition)        (some-> (:target transition) hash-set)
    (sequential? transition) (into #{}
                                   (keep (fn [entry]
                                           (when (map? entry) (:target entry))))
                                   transition)
    :else                    #{}))

(defn- state-transition-targets
  [state-config]
  (->> (get state-config :on {})
       vals
       (mapcat transition-targets)
       (filter some?)
       set))

(defn- build-state-graph
  [states]
  (into {}
        (map (fn [[state-id state-config]]
               [state-id (state-transition-targets state-config)]))
        states))

(defn- bfs-reachable-states
  [state-graph start-state]
  (loop [to-visit [start-state]
         visited #{}]
    (if (empty? to-visit)
      visited
      (let [current-state (first to-visit)
            remaining (rest to-visit)]
        (if (contains? visited current-state)
          (recur remaining visited)
          (recur (concat remaining (get state-graph current-state #{}))
                 (conj visited current-state)))))))

(defn- unreachable-phase-error
  [phases]
  {:error :unreachable-phase-states
   :phases phases
   :message (messages/t :status/unreachable-machine-phases
                        {:phases phases})})

(defn- unreachable-state-error
  [states]
  {:error :unreachable-machine-states
   :states states
   :message (messages/t :status/unreachable-machine-states
                        {:states states})})

;------------------------------------------------------------------------------ Phase 5: structural invariants
;;
;; Three compile-time invariants on the guarded `:phase/fail` arrays
;; locked in by Phase 2b/3 and Phase 4. Future regressions where a
;; contributor adds a second redirect-counting path or forgets the
;; default branch fail at workflow-validation time — at runner startup,
;; not at the next dogfood.

(def ^:private redirect-action
  "Sentinel action keyword whose presence in a `:phase/fail` array means
   THAT entry is the on-fail redirect branch. Defined here so the
   document-order invariant can find it without coupling to
   `standard-guards-and-actions` (which depends on this module
   transitively via fsm.clj's `:require`)."
  :redirect/inc-count)

(def ^:private redirect-budget-guard
  "Sentinel guard keyword that must precede the redirect branch in
   document order — the redirect-budget check."
  :budget/redirects-spent?)

(defn- phase-fail-entries
  "Return the vector entries of a state's `:phase/fail` transition, or
   nil when the transition is the flat (keyword) form. Only `:phase/fail`
   is inspected — other transitions are simpler and have their own
   walker-based validators (reachability, ref coverage)."
  [state-config]
  (let [t (get-in state-config [:on :phase/fail])]
    (when (sequential? t) (vec t))))

(defn- guarded-fail-states
  "Return a seq of `[state-id entries]` for every state whose
   `:phase/fail` transition is the guarded-array form."
  [compiled-states]
  (keep (fn [[state-id config]]
          (when-let [entries (phase-fail-entries config)]
            [state-id entries]))
        compiled-states))

(defn- entry-has-action?
  [entry action-kw]
  (and (map? entry)
       (some #(= action-kw %) (get entry :actions []))))

(defn- entry-default?
  "A 'default' entry in a guarded array has NO `:guard` slot — it always
   matches when reached. clj-statecharts picks the first matching
   branch in document order; without a default at the end, a transition
   that fails every guard is silently dropped."
  [entry]
  (and (map? entry)
       (not (contains? entry :guard))))

;; ---- Invariant 1: each guarded `:phase/fail` array has a default branch ----

(defn- guarded-fail-missing-default-error
  [state-id]
  {:error :guarded-fail-missing-default
   :state state-id
   :message (messages/t :structural/guarded-fail-missing-default
                        {:state state-id})})

(defn- missing-default-branches
  "Return the seq of state-ids whose guarded `:phase/fail` array has no
   default (no-guard) branch."
  [compiled-states]
  (for [[state-id entries] (guarded-fail-states compiled-states)
        :when (not (some entry-default? entries))]
    state-id))

;; ---- Invariant 2: at most one `:redirect/inc-count` per guarded array ----
;;
;; RFC §Phase 5: "At most one action across the vector increments
;; redirect-count." The scope is the per-state `:phase/fail` array —
;; each work-loop phase legitimately has its own (review, verify,
;; release, implement); what we lock OUT is a single state's array
;; bumping the counter twice on the same transition, which would
;; double-count and silently halve the effective ceiling.

(defn- multiple-redirect-actions-error
  [state-id indices]
  {:error :multiple-redirect-counting-actions
   :state state-id
   :entry-indices indices
   :message (messages/t :structural/multiple-redirect-counting-actions
                        {:state state-id :count (count indices)})})

(defn- multiple-redirect-actions-states
  "Return `[{:state :entry-indices}, ...]` for every state whose
   `:phase/fail` array has MORE THAN ONE entry with the
   `:redirect/inc-count` action. Single occurrence is normal — every
   guarded-phase array has one redirect-counting branch."
  [compiled-states]
  (for [[state-id entries] (guarded-fail-states compiled-states)
        :let [idxs (vec (keep-indexed
                          (fn [i e] (when (entry-has-action? e redirect-action) i))
                          entries))]
        :when (> (count idxs) 1)]
    {:state state-id :entry-indices idxs}))

;; ---- Invariant 3: budget guard must exist and precede the redirect branch ----
;;
;; Split into two distinct error keywords so the user's diagnostic
;; message names the actual problem:
;;   * `:budget-guard-misordered` — present but in the wrong place
;;   * `:budget-guard-missing`    — absent entirely

(defn- budget-misordered-error
  [state-id redirect-idx budget-idx]
  {:error :budget-guard-misordered
   :state state-id
   :redirect-index redirect-idx
   :budget-guard-index budget-idx
   :message (messages/t :structural/budget-guard-misordered
                        {:state state-id
                         :redirect-index redirect-idx
                         :budget-index budget-idx})})

(defn- budget-missing-error
  [state-id redirect-idx]
  {:error :budget-guard-missing
   :state state-id
   :redirect-index redirect-idx
   :message (messages/t :structural/budget-guard-missing
                        {:state state-id
                         :redirect-index redirect-idx})})

(defn- budget-guard-misordered-states
  "Return per-state error data for arrays where the redirect branch is
   present but the budget guard either does not exist or fires AFTER
   the redirect. clj-statecharts picks the first matching guard, so a
   missing or misordered budget check effectively bypasses the ceiling.

   Returns a vector of `{:state ... :kind :missing|:misordered
                         :redirect-index ... :budget-index ...}` for
   the aggregator to convert into typed errors."
  [compiled-states]
  (for [[state-id entries] (guarded-fail-states compiled-states)
        :let [redirect-idx (->> entries
                                (keep-indexed
                                 (fn [i e] (when (entry-has-action? e redirect-action) i)))
                                first)
              budget-idx   (->> entries
                                (keep-indexed
                                 (fn [i e] (when (and (map? e)
                                                       (= redirect-budget-guard (:guard e)))
                                             i)))
                                first)]
        :when (and (some? redirect-idx)
                   (or (nil? budget-idx)
                       (> budget-idx redirect-idx)))]
    (if (nil? budget-idx)
      {:state state-id :kind :missing      :redirect-index redirect-idx}
      {:state state-id :kind :misordered   :redirect-index redirect-idx
       :budget-index budget-idx})))

(defn- structural-invariant-errors
  "Phase 5 RFC-prescribed structural invariants. Operates on the RAW
   (pre-resolve) states map so it sees keyword refs.

   Returns a vector of error maps (possibly empty) in the same shape
   as the rest of the validator's errors."
  [compiled-states]
  (let [missing-defaults (missing-default-branches compiled-states)
        multi-redirects  (multiple-redirect-actions-states compiled-states)
        budget-problems  (budget-guard-misordered-states compiled-states)]
    (vec
     (concat
      (map guarded-fail-missing-default-error missing-defaults)
      (map (fn [{:keys [state entry-indices]}]
             (multiple-redirect-actions-error state entry-indices))
           multi-redirects)
      (map (fn [{:keys [state kind redirect-index budget-index]}]
             (case kind
               :missing    (budget-missing-error state redirect-index)
               :misordered (budget-misordered-error state redirect-index budget-index)))
           budget-problems)))))

(defn- build-raw-states
  "Construct the unresolved states map from a workflow pipeline. Returns
   `{:states <raw-states-map> :phase-entries <vec>}`. Extracted from
   `compile-execution-machine` so `validate-execution-machine` can check
   keyword-ref coverage BEFORE keyword→fn resolution throws on dangling
   references."
  [workflow]
  (let [pipeline (definition/execution-pipeline workflow)
        phase-entries (mapv build-phase-entry (range) pipeline)
        first-active-state (some-> phase-entries first :state-id)
        no-phase-machine? (empty? phase-entries)
        states (merge
                {:pending {:on (cond-> {:fail :failed
                                        :cancel :cancelled}
                                 no-phase-machine? (assoc :start :running)
                                 first-active-state (assoc :start first-active-state))}
                 :running (when no-phase-machine?
                            {:on {:complete :completed
                                  :fail :failed
                                  :pause :paused
                                  :cancel :cancelled}})
                 :paused (when no-phase-machine?
                           {:on {:resume :running
                                 :cancel :cancelled}})
                 :completed {:type :final}
                 :failed {:type :final}
                 :cancelled {:type :final}}
                (into {} (map (partial build-phase-state phase-entries)) phase-entries)
                (into {} (map build-paused-state) phase-entries))]
    {:states        (into {} (remove (comp nil? val)) states)
     :phase-entries phase-entries}))

(defn compile-execution-machine
  "Compile a per-run execution machine from a workflow pipeline.

   The compiled machine is the authoritative source of truth for workflow
   progression. Coarse lifecycle status and phase/index projections are derived
   from the resulting machine snapshot.

   Resolves keyword-form `:guard` and `:actions` references against their
   registries before handing the table to clj-statecharts (the engine
   expects fns, not keywords). `validate-execution-machine` checks
   keyword-ref coverage on the RAW states map BEFORE this resolution so
   dangling refs surface as validation errors; an unregistered keyword
   that survives validation trips the resolve-time
   IllegalStateException (programmer-error)."
  [workflow]
  (let [{:keys [states phase-entries]} (build-raw-states workflow)
        raw-compiled-states states
        compiled-states (-> raw-compiled-states
                            guards/resolve-guard-keywords
                            actions/resolve-action-keywords)
        state-graph (build-state-graph compiled-states)
        state->phase (into {}
                           (concat
                            (map #(projection-entry % false) phase-entries)
                            (map paused-projection-entry phase-entries)))
        state->entry (into {}
                           (concat
                            (map #(state-entry % (:state-id %)) phase-entries)
                            (map paused-state-entry phase-entries)))]
    (assoc (fsm/define-machine
            {:fsm/id :workflow-execution
             :fsm/initial :pending
             :fsm/context {:redirect-count 0}
             :fsm/states compiled-states})
           :workflow/phase-entries phase-entries
           :workflow/state->phase state->phase
           :workflow/state->entry state->entry
           :workflow/state-graph state-graph
           :workflow/state-ids (set (keys compiled-states))
           ;; Attach the RAW (pre-resolve) states map so the guard and
           ;; action validators can walk transitions and surface keyword
           ;; refs. The compiled `compiled-states` map has fns in the
           ;; `:guard` / `:actions` slots — validators can't recover
           ;; the keyword refs from there. Validation runs against this
           ;; raw form in `validate-execution-machine`.
           :workflow/source-states raw-compiled-states
           :workflow/initial-state :pending)))

(defn validate-execution-machine
  "Validate that a workflow pipeline can compile into an execution machine."
  [workflow]
  (let [normalized-workflow (definition/ensure-execution-pipeline workflow)
        pipeline (definition/execution-pipeline normalized-workflow)
        duplicate-phases (->> pipeline
                              (map :phase)
                              frequencies
                              (filter duplicate-phase?)
                              (map first)
                              vec)
        ;; Check that an explicitly declared entry phase actually exists in the
        ;; legacy-phases list. We only validate when the caller declared an entry
        ;; explicitly (via :workflow/entry or :workflow/entry-phase) AND the
        ;; workflow uses the legacy :workflow/phases format. If no entry is
        ;; declared the runtime falls back to the first declared phase, which is
        ;; always valid.
        explicit-entry (or (:workflow/entry workflow) (:workflow/entry-phase workflow))
        phase-ids (when (:workflow/phases workflow)
                    (set (map :phase/id (:workflow/phases workflow))))
        bad-entry-phase (when (and explicit-entry
                                   phase-ids
                                   (not (contains? phase-ids explicit-entry)))
                          explicit-entry)
        unresolved-success (->> pipeline
                                (keep (partial unknown-success-target pipeline))
                                vec)
        unresolved-fail (->> pipeline
                             (keep (partial unknown-fail-target pipeline))
                             vec)
        ;; Phase 2a: build the raw (unresolved) states FIRST so the guard
        ;; and action validators see keyword refs. Only run the full
        ;; `compile-execution-machine` (which RESOLVES keywords → fns
        ;; and would throw on dangling refs) AFTER ref-validation has
        ;; passed. The raw-build is identical structurally to what the
        ;; full compile uses internally.
        raw-build (when (and (seq pipeline)
                             (nil? bad-entry-phase)
                             (empty? unresolved-success)
                             (empty? unresolved-fail))
                    (build-raw-states normalized-workflow))
        raw-states (:states raw-build)
        ;; Named-registry checks — every keyword-form `:guard` and
        ;; `:actions` reference in the raw states must resolve against
        ;; their registries, else surface the dangling reference here
        ;; rather than at runtime IllegalStateException.
        dangling-guards (when raw-states
                          (guards/validate-guard-references raw-states))
        dangling-actions (when raw-states
                           (actions/validate-action-references raw-states))
        ;; Phase 5: structural invariants on guarded `:phase/fail`
        ;; arrays. Locks in the SINGLE-accounting-site / default-branch
        ;; / budget-precedes-redirect guarantees from Phase 2b/3/4.
        structural-errors (when raw-states
                            (structural-invariant-errors raw-states))
        ref-errors (filterv some? [dangling-guards dangling-actions])
        ;; Only compile (with keyword resolution) if ref validation is
        ;; clean — otherwise compile would throw IllegalStateException
        ;; and bypass the structured error report below.
        machine (when (and raw-build (empty? ref-errors))
                  (compile-execution-machine normalized-workflow))
        unreachable-state-set (when machine
                                (unreachable-states machine))
        unreachable-phases (when machine
                             (->> (:workflow/phase-entries machine)
                                  (keep (fn [{:keys [phase state-id]}]
                                          (when (contains? unreachable-state-set state-id)
                                            phase)))
                                  vec))
        errors (vec (concat
                     (when (empty? pipeline)
                       [(empty-pipeline-error)])
                     (when bad-entry-phase
                       [(unknown-entry-phase-error bad-entry-phase)])
                     unresolved-success
                     unresolved-fail
                     ref-errors
                     structural-errors
                     (when (seq unreachable-phases)
                       [(unreachable-phase-error unreachable-phases)])
                     (when (seq unreachable-state-set)
                       [(unreachable-state-error (vec unreachable-state-set))])))
        warnings (vec (concat
                       (when (seq duplicate-phases)
                         [(duplicate-phase-warning duplicate-phases)])))]
    {:valid? (empty? errors)
     :errors errors
     :warnings warnings
     :machine (when (empty? errors)
                machine)}))

(defn state-graph
  "Return the compiled state graph for a workflow execution machine."
  [machine]
  (:workflow/state-graph machine))

(defn compiled-state-ids
  "Return the set of states defined by a compiled workflow execution machine."
  [machine]
  (:workflow/state-ids machine))

(defn reachable-states
  "Return the set of states reachable in a compiled workflow machine.

   With one arg, traversal starts from the machine's initial state.
   With two args, traversal starts from the provided state id."
  ([machine]
   (reachable-states machine (:workflow/initial-state machine)))
  ([machine start-state]
   (if start-state
     (bfs-reachable-states (state-graph machine) start-state)
     #{})))

(defn unreachable-states
  "Return the set of defined states that are not reachable in a compiled workflow machine."
  ([machine]
   (unreachable-states machine (:workflow/initial-state machine)))
  ([machine start-state]
   (set/difference (compiled-state-ids machine)
                   (reachable-states machine start-state))))

(defn machine-phase-entry
  "Get phase metadata for the current compiled machine state."
  [machine state]
  (get-in machine [:workflow/state->phase (current-state state)]))

(defn machine-active-phase-entry
  "Get the active workflow phase entry for the current compiled machine state."
  [machine state]
  (get-in machine [:workflow/state->entry (current-state state)]))

(defn execution-status
  "Project coarse execution status from a compiled machine snapshot."
  [machine state]
  (let [state-id (current-state state)]
    (cond
      (= :pending state-id) :pending
      (= :completed state-id) :completed
      (= :failed state-id) :failed
      (= :cancelled state-id) :cancelled
      (:paused? (machine-phase-entry machine state)) :paused
      :else :running)))

(defn current-phase-id
  "Project the current phase keyword from a compiled machine snapshot."
  [machine state]
  (:phase (machine-phase-entry machine state)))

(defn current-phase-index
  "Project the current phase index from a compiled machine snapshot."
  [machine state]
  (:index (machine-phase-entry machine state)))

(defn execution-projection
  "Project execution fields from a compiled machine snapshot."
  [machine state]
  (let [ctx (fsm/context state)]
    {:execution/status (execution-status machine state)
     :execution/current-phase (current-phase-id machine state)
     :execution/phase-index (current-phase-index machine state)
     :execution/redirect-count (get ctx :redirect-count 0)}))

(defn initialize-execution
  "Initialize a compiled execution machine."
  [machine]
  (fsm/initialize machine))

(defn start-execution
  "Start a compiled execution machine from :pending."
  [machine state]
  (fsm/transition machine state :start))

;; ============================================================================
;; Legacy API - for backward compatibility with state.clj
;; ============================================================================

(def workflow-states
  "Valid states for workflow execution."
  #{:pending :running :completed :failed :paused :cancelled})

(def workflow-transitions
  "Valid state transitions. Map of [from-state event] -> to-state."
  {[:pending :start]      :running
   [:running :complete]   :completed
   [:running :fail]       :failed
   [:running :pause]      :paused
   [:running :cancel]     :cancelled
   [:paused :resume]      :running
   [:paused :cancel]      :cancelled})

(def terminal-states
  "States that are terminal (no further transitions)."
  #{:completed :failed :cancelled})

;; ============================================================================
;; FSM Operations (legacy API backed by clj-statecharts)
;; ============================================================================

(defn valid-transition?
  "Check if a transition from current state via event is valid.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: boolean"
  [current-state event]
  (contains? workflow-transitions [current-state event]))

(defn terminal-state?
  "Check if state is terminal (no further transitions allowed).

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? terminal-states state))

(defn succeeded?
  "Check if a transition result indicates success."
  [result]
  (boolean (:success? result)))

(defn next-state
  "Get next state for a transition.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: Next state keyword or nil if transition invalid"
  [current-state event]
  (get workflow-transitions [current-state event]))

(defn transition
  "Attempt state transition with guard checking.

   Uses clj-statecharts internally for proper FSM semantics.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition
   - guard-fn: Optional guard function (fn [current-state event] -> boolean)

   Returns:
   - {:success? true :state new-state} if transition valid
   - {:success? false :error reason} if transition invalid"
  ([current-state event]
   (transition current-state event (constantly true)))

  ([current-state event guard-fn]
   (cond
     ;; State already terminal
     (terminal-state? current-state)
     {:success? false
      :error :terminal-state
      :message (terminal-state-message current-state)}

     ;; Invalid state
     (not (contains? workflow-states current-state))
     {:success? false
      :error :invalid-state
      :message (invalid-state-message current-state)}

     ;; Guard function failed
     (not (guard-fn current-state event))
     {:success? false
      :error :guard-failed
      :message (messages/t :status/guard-rejected-transition)}

     ;; Invalid transition
     (not (valid-transition? current-state event))
     {:success? false
      :error :invalid-transition
      :message (no-transition-message current-state event)}

     ;; Valid transition - use clj-statecharts for actual transition
     :else
     (let [;; Create a temporary state with the current state value
           state-map {:_state current-state}
           new-state-map (fsm/transition workflow-machine state-map event)
           new-state (fsm/current-state new-state-map)]
       {:success? true
        :state new-state}))))

(defn valid-state?
  "Check if a state keyword is valid.

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? workflow-states state))

;; ============================================================================
;; FSM Visualization
;; ============================================================================

(defn get-available-events
  "Get available events from current state.

   Arguments:
   - current-state: Current FSM state keyword

   Returns: Vector of event keywords"
  [current-state]
  (vec (keep (fn [[[from-state event] _to-state]]
               (when (= from-state current-state)
                 event))
             workflow-transitions)))

(defn fsm-graph
  "Get FSM as a graph structure for visualization.

   Returns: Map with :states and :transitions"
  []
  {:states workflow-states
   :transitions (vec (map (fn [[[from event] to]]
                           {:from from :event event :to to})
                         workflow-transitions))
   :terminal-states terminal-states})

;; ============================================================================
;; New clj-statecharts API
;; ============================================================================

(defn get-machine
  "Get the compiled workflow state machine for direct clj-statecharts usage.

   Returns: Compiled state machine definition"
  []
  workflow-machine)

(defn initialize
  "Initialize workflow FSM state.

   Returns: Initial FSM state map with :_state = :pending"
  []
  (fsm/initialize workflow-machine))

(defn transition-fsm
  "Transition the workflow FSM using full statecharts semantics.

   Arguments:
   - state: Current FSM state map (from initialize or previous transition)
   - event: Event keyword or map {:type :event-type :data ...}

   Returns: New FSM state map"
  [state event]
  (fsm/transition workflow-machine state event))

(defn current-state
  "Get the current state keyword from FSM state map.

   Arguments:
   - state: FSM state map

   Returns: State keyword"
  [state]
  (fsm/current-state state))

(defn is-final?
  "Check if the FSM is in a final (terminal) state.

   Arguments:
   - state: FSM state map

   Returns: boolean"
  [state]
  (fsm/final? workflow-machine state))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Using the legacy API (for backward compatibility)
  (valid-transition? :pending :start)  ;; => true
  (valid-transition? :pending :fail)   ;; => false

  (transition :pending :start)
  ;; => {:success? true :state :running}

  (transition :completed :start)
  ;; => {:success? false :error :terminal-state ...}

  (get-available-events :running)
  ;; => [:complete :fail :pause :cancel]

  ;; Using the new clj-statecharts API
  (def s0 (initialize))
  (current-state s0)  ;; => :pending

  (def s1 (transition-fsm s0 :start))
  (current-state s1)  ;; => :running

  (def s2 (transition-fsm s1 :complete))
  (current-state s2)  ;; => :completed
  (is-final? s2)      ;; => true

  :leave-this-here)
