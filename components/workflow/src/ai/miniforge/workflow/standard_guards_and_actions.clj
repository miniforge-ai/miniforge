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

(ns ai.miniforge.workflow.standard-guards-and-actions
  "Standard FSM guards and actions for workflow phase transitions.

   Phase 2b of the FSM RFC. Registers the predicates and side-effects
   the review-phase guarded `:phase/fail` array consumes:

     {:on {:phase/fail
           [{:target :failed     :guard :verdict/terminal?}
            {:target :failed     :guard :budget/redirects-spent?}
            {:target on-fail     :actions [:redirect/inc-count]}
            {:target :failed}]}}

   Registration happens at namespace-load time — requiring this ns is
   enough to populate the registries. `compile-execution-machine`
   resolves the keyword refs against the populated registries.

   No `:config/on-fail-set?` guard is registered: the RFC's three-guard
   set collapses to two at runtime because `build-phase-state` decides
   at COMPILE time whether to include the redirect branch (based on
   `(:on-fail config)`). If on-fail is unset, the redirect branch is
   omitted entirely — no runtime check needed. This is structurally
   identical to the RFC behavior with simpler runtime arithmetic."
  (:require
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.workflow.actions :as actions]
   [ai.miniforge.workflow.guards :as guards]
   [ai.miniforge.workflow.runner-defaults :as defaults]
   [statecharts.core :as sc]))

;------------------------------------------------------------------------------ Verdict-driven guards

(def terminal-verdicts
  "The set of `:phase/verdict` values that route a phase failure straight
   to the workflow terminal `:failed` state, bypassing any on-fail
   redirect. Mirrors the legacy `:stagnated?` / `:needs-decomposition?`
   flag bits from the workaround era; collapsed here into a single
   FSM-readable signal."
  #{;; Convergence-failure verdicts (review phase)
    :stagnated
    :needs-decomposition
    :exhausted
    ;; Verify-specific (Phase 3a): retrying implement does not unblock a
    ;; timed-out test process or a provider rate-limit. Both terminate.
    :verify/timeout
    :verify/rate-limited
    ;; Release-specific (Phase 3b): curator-rejected (no files to ship).
    ;; Retrying implement won't change the curator's decision — the
    ;; diff is empty and the next pass would just produce another empty
    ;; diff. Terminal.
    :release/zero-files
    ;; Implement-specific (Phase 3c). All three are cases where the
    ;; on-fail :implement loop would just re-enter and hit the same
    ;; wall — terminate the workflow instead.
    ;;   :rate-limited — provider quota; budget retries won't change it
    ;;   :empty-diff   — curator: no files written; retry would produce
    ;;                   another empty diff
    ;;   :already-implemented-invalid — the implementer claimed done
    ;;                   but verify hadn't passed; retry won't fix the
    ;;                   stale claim
    :implement/rate-limited
    :implement/empty-diff
    :implement/already-implemented-invalid
    ;; PR-C of network-resilience stack: the implement phase exhausted
    ;; its iteration budget retrying from the persisted checkpoint
    ;; after PR-B's network-monitor detected sustained provider
    ;; outages. Retrying again would just hit the same dead network on
    ;; the next probe cycle. Terminal — operator surfaces the workflow
    ;; for manual resume once connectivity is restored.
    :implement/network-dropped
    ;; PR-B of phase-timeout stack (companion to network-resilience):
    ;; the reviewer LLM call hit its adaptive timeout (stream-idle /
    ;; stagnation / hard-limit / generic) with no real verdict
    ;; produced. PR-A (#1058) made the reviewer agent take the
    ;; `:reviewer/backend-timeout` error exit instead of synthesizing
    ;; a false `:rejected`; this verdict surfaces that to the FSM.
    ;; Distinct from `:exhausted` — `:exhausted` means the reviewer
    ;; HAD a verdict the gate couldn't approve, this means the
    ;; reviewer never produced one.
    :review/backend-timeout
    ;; PR-C of phase-timeout stack (companion to PR-B + network-
    ;; resilience PR-C): the implementer LLM call exhausted its
    ;; iteration budget retrying from the persisted checkpoint after
    ;; consecutive stream-idle / stagnation / hard-limit timeouts.
    ;; Same recovery shape as `:implement/network-dropped` — retriable
    ;; up to the budget, then terminal. Retrying again would just hit
    ;; the same slow provider on the next pass.
    :implement/backend-timeout})

(defn verdict-terminal?
  "Guard: true when the failing-phase event carries a terminal verdict.
   Reads `(:phase/verdict event)` — the phase code attaches this on its
   result; `determine-phase-event` plumbs it into the FSM event map."
  [_state event]
  (contains? terminal-verdicts (:phase/verdict event)))

;------------------------------------------------------------------------------ Budget-driven guard

(defn budget-redirects-spent?
  "Guard: true when the FSM-context redirect-count has reached the
   workflow-wide `max-redirects` ceiling. Reads from FSM context
   (`(fsm/context state)`) which is the single source of truth for
   the budget — the runner's parallel check in `apply-phase-transition`
   becomes redundant once Phase 3 migrates the remaining phases."
  [state _event]
  (let [ctx (fsm/context state)
        max-r (defaults/max-redirects)]
    (>= (get ctx :redirect-count 0) max-r)))

;------------------------------------------------------------------------------ Actions

(def redirect-inc-count
  "Action: bumps the FSM-context `:redirect-count` by 1. Uses
   `statecharts.core/assign` directly rather than the local
   `ai.miniforge.fsm.interface/assign` wrapper — the latter returns a
   plain fn that clj-statecharts doesn't recognize as an assign action,
   so context mutations get silently dropped (verified by a probe with
   a 1-state machine returning `{:counter 0}` after `:go` despite the
   action running). `sc/assign` produces the engine-tagged variant
   that actually propagates context updates.

   Single accounting site — the runner's `redirect-event?` namespace
   sniff goes away in Phase 4."
  (sc/assign (fn [ctx _event]
               (update ctx :redirect-count (fnil inc 0)))))

;------------------------------------------------------------------------------ Registration

;; Eager registration at namespace load — `(require '...standard-guards-and-actions)`
;; is enough to populate both registries. `compile-execution-machine` then
;; resolves the keyword refs.
(guards/register-guard! :verdict/terminal?         verdict-terminal?)
(guards/register-guard! :budget/redirects-spent?   budget-redirects-spent?)
(actions/register-action! :redirect/inc-count      redirect-inc-count)
