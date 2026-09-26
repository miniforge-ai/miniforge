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
(ns ai.miniforge.workflow.execution-transition
  "What a phase result means for the execution machine: gates decide
   whether the phase passed, the result becomes a machine event, and the
   event is applied to the machine.

   Split out of `execution` (rule 210). `execution-lifecycle` runs the
   gates between enter and leave; `execution` and `execution-dag` apply
   the transitions."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution-events :as exec-events]
            [ai.miniforge.workflow.fsm :as workflow-fsm]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} already-done?
  "Check if phase result indicates work was already done."
  [phase-result]
  (or (phase/already-done? phase-result)
      (phase/already-done? (:result phase-result))))

(defn- ^{:stratum 0} phase-transition-event-message
  [event]
  (messages/t :status/invalid-phase-transition-event {:event event}))

;; Phase 4b removed `redirect-target`. With handle-error refactored
;; to emit `:phase/verdict :repair-requested` (the last in-production
;; caller of `phase/request-redirect`), no phase result carries a
;; redirect-target marker anymore. `determine-phase-event` reads the
;; verdict instead.
(defn- ^{:stratum 0} phase-verdict
  "Read the `:phase/verdict` keyword from a phase result.

   Production shape: leave-* fns assoc the verdict into the inner agent
   result at `[:phase :result :output :phase/verdict]` on the ctx —
   so the `phase-result` extracted by `extract-phase-result` (which is
   the `:phase` map) carries it at `[:result :output :phase/verdict]`.

   Copilot's #1030 review caught the original `[:output :phase/verdict]`
   read path that never resolved against the production shape — the
   path-bug was latent from Phase 2b and silently dropped verdict
   events for the whole Phase 3 series. Fall back to a top-level
   `:phase/verdict` for synthetic test results."
  [phase-result]
  (or (get-in phase-result [:result :output :phase/verdict])
      (get-in phase-result [:output :phase/verdict])
      (get phase-result :phase/verdict)))

(def ^{:stratum 0} max-redirects
  "Maximum number of phase redirects before failing to prevent infinite loops."
  (defaults/max-redirects))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-succeeded?
  "Check if phase completed successfully or work was already done."
  [phase-result]
  (or (phase/succeeded-or-done? phase-result)
      (already-done? phase-result)))

(defn- ^{:stratum 1} invalid-phase-transition-anomaly
  [event]
  (response/make-anomaly
   :anomalies.workflow/invalid-transition
   (phase-transition-event-message event)))

(defn- ^{:stratum 1} phase-transition-failure
  [ctx event anomaly transition-to-failed-fn]
  (let [message (phase-transition-event-message event)]
    (-> ctx
        (update :execution/errors conj
                {:type :invalid-transition
                 :message message
                 :anomaly anomaly})
        (update :execution/response-chain
                response/add-failure :pipeline anomaly
                {:error message
                 :event event})
        (transition-to-failed-fn))))

(defn ^{:stratum 1} determine-phase-event
  "Translate a phase result into an execution-machine event.

   For failed phases that attached a `:phase/verdict`, returns a map
   event `{:type :phase/fail :phase/verdict <v>}` so the verdict-driven
   guarded `:phase/fail` array can dispatch via the
   `:verdict/terminal?` guard.

   Phase 4a removed the `:phase/terminal-fail` legacy workaround
   (introduced by #1013 to bypass on-fail when phase code set
   `:stagnated?` / `:needs-decomposition?` flag bits). Phase 2b's
   verdict-driven array supersedes that mechanism for every phase
   that now emits a verdict; the flag bits are no longer set anywhere
   in production code."
  [_phase-config phase-result]
  (let [verdict (phase-verdict phase-result)]
    (cond
      (phase/retrying? phase-result)
      :phase/retry

      (phase/already-done? phase-result)
      :phase/already-done

      (phase/succeeded? phase-result)
      :phase/succeed

      ;; Failed phase with a verdict — send a map event so the FSM's
      ;; guarded array reads it via `:verdict/terminal?`.
      (and (phase/failed? phase-result) verdict)
      {:type :phase/fail :phase/verdict verdict}

      (phase/failed? phase-result)
      :phase/fail

      :else
      :phase/succeed)))

(defn ^{:stratum 1} apply-gate-validation
  "Apply gate validation to phase result.

   Returns updated phase-result carrying :phase/decision-envelope (the ONE
   truth artifact for the gated transition, Ariadne 1d) — plus
   :phase/status :failed and :phase/gate-errors (a projection of the
   envelope's reasons, no longer an independent shape) when the decision is
   :deny. Skips gate checks when phase indicates work is already done. When
   gates are configured they run even if the canonical artifact
   ([:result :output]) is nil — a nil artifact fails the gate (fail-closed),
   never bypasses it; the envelope carries :reason/missing-artifact."
  [interceptor phase-result ctx]
  (if (already-done? phase-result)
    phase-result
    (let [gate-keywords (get-in interceptor [:config :gates] [])
          ;; Canonical: gates validate the phase's :output ([:result :output]) —
          ;; one location, no shape-guessing. The old (or :artifact
          ;; [:result :artifact] [:result :output]) could hand a gate the wrong
          ;; map: the review gate got the code-under-review at :artifact instead
          ;; of the verdict at :output, so it rejected every approved review.
          artifact (response/phase-output phase-result)]
      ;; Fail closed: when gates are configured, run them even if the canonical
      ;; artifact is nil. Skipping on nil would let a phase that omits its
      ;; :output bypass validation entirely — the gate runner turns a nil
      ;; artifact into a failed gate result (loud), which is what we want.
      (if (seq gate-keywords)
        (let [gate-result (gate/check-gates gate-keywords artifact ctx)
              minted (gate/gates->envelope gate-result (nil? artifact))
              ;; envelope minting can itself anomaly; fail closed on that too
              envelope (when (:envelope/id minted) minted)]
          (exec-events/emit-phase-decision! ctx phase-result envelope)
          (if (and (:passed? gate-result)
                   envelope
                   (gate/decision-allowed? envelope))
            (assoc phase-result :phase/decision-envelope envelope)
            (assoc phase-result
                   :phase/status :failed
                   :phase/decision-envelope envelope
                   :phase/gate-errors (if envelope
                                        (vec (:envelope/reasons envelope))
                                        (vec (:failed-gates gate-result)))
                   ;; The envelope's Reasons are schema-bound strings; the
                   ;; repair loop needs the gates' STRUCTURED errors (which
                   ;; gate, which token, which files). This rides the phase
                   ;; result into :execution/phase-results, the one channel
                   ;; a redirect re-entry can still read.
                   ;; gate/passed? handles response-shaped results too;
                   ;; a bare (remove :passed?) would misfile them.
                   :phase/gate-failures (->> (:results gate-result)
                                             (remove gate/passed?)
                                             (mapv #(-> (select-keys % [:gate :errors])
                                                        (update :errors (fnil vec []))))))))
        phase-result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply-phase-transition
  "Apply a phase-outcome event through the execution machine.

   Phase 4b: dropped the parallel `is-redirect?` budget check. The
   FSM's `:budget/redirects-spent?` guard on the guarded `:phase/fail`
   array enforces the same `max-redirects` ceiling, and the
   `:redirect/inc-count` action on the redirect branch is the SINGLE
   accounting site that bumps the counter. With Phase 4b's verdict-
   driven handle-error path, no event ever reaches this fn with the
   former `workflow.event/redirect-to-*` shape that the runner check
   keyed off.

   Returns updated context with refreshed machine projections or
   terminal failure."
  [ctx event _pipeline _transition-to-completed-fn transition-to-failed-fn]
  (let [prior-state (:execution/fsm-state ctx)
        prior-current-state (workflow-fsm/current-state prior-state)
        ;; At this boundary an unknown phase event is a terminal invalid
        ;; transition: route the anomaly-returning FSM result through the same
        ;; fail path as a no-op transition — loud, but graceful, not a crashed
        ;; run.
        next-ctx (context/transition-execution-result ctx event)
        fail (fn [] (phase-transition-failure ctx
                                              event
                                              (invalid-phase-transition-anomaly event)
                                              transition-to-failed-fn))]
    (cond
      ;; Unknown event is always a terminal invalid transition here — never
      ;; return the sentinel; never let :phase/retry leak it out.
      (anomaly/anomaly? next-ctx) (fail)
      (not= prior-current-state
            (workflow-fsm/current-state (:execution/fsm-state next-ctx))) next-ctx
      (= :phase/retry event) next-ctx
      ;; A SELF-transition is legitimate exactly when a guarded branch's
      ;; action ran: the redirect/infra counters in the statechart
      ;; context moved. Inferring validity from the state-id delta alone
      ;; silently killed both self-targeting branches of the guarded
      ;; :phase/fail array — on-fail self-repair (:repair-requested with
      ;; :on-fail :implement died terminal, observed live in the
      ;; trap-bench repair demonstration) and the infra-retry branch
      ;; (:verify/timeout never consulted its budget).
      (not= (select-keys (fsm/context (:execution/fsm-state ctx))
                         [:redirect-count :infra-retry-count])
            (select-keys (fsm/context (:execution/fsm-state next-ctx))
                         [:redirect-count :infra-retry-count])) next-ctx
      :else (fail))))
