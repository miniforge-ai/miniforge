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
   [clojure.set :as set]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.workflow.actions :as actions]
   [ai.miniforge.workflow.guards :as guards]
   [ai.miniforge.workflow.runner-defaults :as defaults]
   [statecharts.core :as sc]))

;------------------------------------------------------------------------------ Verdict-driven guards

;; Verdict taxonomy (Fable review §2.4). Three classes of failure verdict,
;; each carrying different machine semantics. PR-A (this change) names the
;; classes and keeps `terminal-verdicts` as their UNION — so routing is
;; byte-for-byte unchanged. PR-B peels `infrastructure-verdicts` off the
;; terminal path to retry-same-phase against an infra budget (transient
;; failures a backoff can clear), where ~half the recorded burn incidents
;; live.

(def infrastructure-verdicts
  "TRANSIENT infrastructure failures — a timeout, provider rate-limit,
   backend/stream death, or dropped network. A retry after backoff CAN clear
   these; they are not evidence the work is wrong. Today still terminal (kept
   in `terminal-verdicts`); PR-B routes them to retry-same-phase against a
   dedicated infra-retry budget instead of the redirect/work budget."
  #{:verify/timeout
    :verify/rate-limited
    :implement/rate-limited
    :implement/network-dropped
    :implement/backend-timeout
    :review/backend-timeout})

(def no-op-verdicts
  "EVIDENCE-OF-ABSENCE failures — the work produced nothing actionable, so a
   retry would just reproduce the same empty result. Always fail closed.
     :empty-diff / :zero-files — curator found no files written
     :already-implemented-invalid — implementer claimed done but verify
                                    hadn't passed; retry won't fix the claim"
  #{:implement/empty-diff
    :release/zero-files
    :implement/already-implemented-invalid})

(def work-terminal-verdicts
  "WORK that ran its course — the review/repair loop exhausted its budget
   without converging. Terminal (the end of the work-redirect path)."
  #{:stagnated
    :needs-decomposition
    :exhausted})

(def terminal-verdicts
  "The set of `:phase/verdict` values that route a phase failure straight to
   the workflow terminal `:failed` state, bypassing any on-fail redirect.

   The UNION of the three terminal classes — identical membership to the
   pre-taxonomy flat set, so `verdict-terminal?` routing is unchanged by the
   PR-A split. PR-B removes `infrastructure-verdicts` from this union so
   transient failures retry rather than terminate."
  (set/union infrastructure-verdicts no-op-verdicts work-terminal-verdicts))

(defn verdict-class
  "Classify a phase `:phase/verdict` into the taxonomy (Fable §2.4):
     :infrastructure — transient; retry-worthy (PR-B)
     :no-op          — evidence of absence; fail closed
     :work-terminal  — work budget exhausted; fail closed
     :work           — an ordinary actionable failure → redirect / repair
   Closed over the three sets; the default `:work` covers every
   non-terminal verdict (e.g. `:repair-requested`)."
  [verdict]
  (cond
    (contains? infrastructure-verdicts verdict) :infrastructure
    (contains? no-op-verdicts verdict)          :no-op
    (contains? work-terminal-verdicts verdict)  :work-terminal
    :else                                       :work))

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

(defn verdict-infra-retriable?
  "Guard: true when the failing-phase verdict is a TRANSIENT infrastructure
   verdict (timeout / rate-limit / backend-died / network-dropped) AND the
   infra-retry budget is not yet spent. When true the guarded `:phase/fail`
   array retries the SAME phase (a backoff can clear the failure) against the
   dedicated infra budget — not the redirect/work budget. Once the budget is
   spent this returns false and the verdict falls through to
   `verdict-terminal?` → `:failed` (infra verdicts are still in
   `terminal-verdicts`). Fable §2.4."
  [state event]
  (and (contains? infrastructure-verdicts (:phase/verdict event))
       (< (get (fsm/context state) :infra-retry-count 0)
          (defaults/max-infra-retries))))

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

(def infra-inc-count
  "Action: bumps the FSM-context `:infra-retry-count` by 1 on each infra
   retry. Separate counter from `:redirect-count` so transient
   infrastructure retries never consume the work/redirect budget. Uses
   `sc/assign` directly for the same reason as `redirect-inc-count`."
  (sc/assign (fn [ctx _event]
               (update ctx :infra-retry-count (fnil inc 0)))))

;------------------------------------------------------------------------------ Registration

;; Eager registration at namespace load — `(require '...standard-guards-and-actions)`
;; is enough to populate both registries. `compile-execution-machine` then
;; resolves the keyword refs.
(guards/register-guard! :verdict/terminal?         verdict-terminal?)
(guards/register-guard! :verdict/infra-retriable?  verdict-infra-retriable?)
(guards/register-guard! :budget/redirects-spent?   budget-redirects-spent?)
(actions/register-action! :redirect/inc-count      redirect-inc-count)
(actions/register-action! :infra/inc-count         infra-inc-count)
