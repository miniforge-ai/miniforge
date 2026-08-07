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
(ns ai.miniforge.operator.application
  "Intervention application layer — Phase D D-3.

   Turns an `:approved` InterventionRequest into an actual effect on a
   live workflow runner, advancing the lifecycle
   `approved → dispatched → applied → verified` (or `→ failed`) and
   publishing every transition through the consumer's canonical
   state-changed emitter.

   This namespace is the facade: it owns the process-scoped registries
   (live runners, degradation manager, resume launcher, policy
   evaluator), the ownership predicate a consumer uses to claim an
   event, and the `apply-intervention!` hook that dispatches an approved
   intervention to its verb applier. The verb appliers live in
   [[ai.miniforge.operator.application.verbs]] and the lifecycle
   primitives they compose in [[ai.miniforge.operator.application.core]]
   — split out so no single namespace exceeds the three-layer budget
   (rule 210).

   Application mapping (Phase D design decision 5, v1):

   | intervention                       | mechanism                          |
   |------------------------------------|------------------------------------|
   | :pause / :resume / :cancel         | event-stream control-state flags   |
   | :acknowledge / :request-human-review | supervisory-state only (no machine effect) |
   | :force-safe-mode / :exit-safe-mode | degradation manager (when wired)   |
   | :retry / :retry-from-phase         | workflow-resume + the resume launcher |
   | :re-evaluate                       | policy evaluator → new PolicyEvaluation |
   | :waive / :request-replan           | not yet applied — fail loudly      |

   **Process-lifetime honesty:** control-state is in-process, so
   control-state interventions act only on workflows registered by a
   live runner in THIS process. Anything else fails visibly with a
   localized reason and a typed `:failure/code` — rather than
   pretending. The not-yet-applied verbs fail the same way: a red chip
   with a stable code beats silently-parked hope.

   `:waive` stays unapplied on purpose. N5-delta-1 §6 Waiver records
   have a schema (`schema/Waiver`) and a TUI surface that derives
   `:waived` from them, but no store: no event type produces one, no
   accumulator table holds one, no emitter publishes one. Applying the
   verb would mean inventing that store here, off to one side of the
   supervisory entity families — so it fails `:not-implemented` until
   the store lands where the other entities live.

   **Verification is a readback, not an echo:** control-state verbs
   verify by reading the flag back (`paused?` / `cancelled?`), so a
   `verified` chip means the runner's own control flags actually
   changed. The D-3b mechanisms hold the same bar: a resume verifies by
   reconstructing the launched run from its event history, a
   re-evaluation by finding a PolicyEvaluation in the materialized
   entity table that was not there before the publish."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.operator.application.core :as core]
   [ai.miniforge.operator.application.verbs :as verbs]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.operator.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Live-runner registry — process-scoped
(defonce ^{:stratum 0} ^:private live-runners
  ;; workflow-id string → {:control-state <atom>}
  (atom {}))

(defonce ^{:stratum 0} ^:private process-degradation-manager
  ;; Safe mode is a process-level `:degradation` target, not a workflow
  ;; target. Its canonical target id therefore is not a runner id.
  (atom nil))

(defonce ^{:stratum 0} ^:private process-resume-launcher
  ;; {:launch! (fn [plan] -> {:resume/run-id …}), :events-dir <dir-or-nil>}
  ;; A retry restarts a run whose runner is gone by definition, so it
  ;; cannot go through the live-runner registry. Starting a pipeline is
  ;; adapter work (workflow loader + LLM client), so the process owner
  ;; registers the handle — the same shape as the degradation manager.
  (atom nil))

(defonce ^{:stratum 0} ^:private process-policy-evaluator
  ;; (fn [request] -> {:evaluation/passed? … :evaluation/violations […]
  ;;                   :evaluation/packs-applied […]})
  ;; The return shape is `policy-pack/evaluate-external-pr`'s, so the
  ;; canonical implementer is a partial of it bound to a pack loader and
  ;; a PR fetcher — both adapter-owned.
  (atom nil))

(def ^{:stratum 0} ^:private resume-ownership-verbs
  "Retry verbs whose ownership cannot hinge on a live runner. Their
   canonical target type is `:workflow`, but a retry restarts a run
   whose original runner is gone by definition — the live runner it
   would gate on is exactly the thing it will never have. So they are
   process-global for ownership: whichever consumer sees one claims it,
   and the application layer either dispatches through the registered
   resume launcher or fails `:no-resume-launcher` — a visible red chip,
   never a silent park."
  #{:retry :retry-from-phase})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} live-runner?
  [workflow-id]
  (contains? @live-runners (str workflow-id)))

(defn ^{:stratum 1} register-runner!
  "Register a live runner's control handles for `workflow-id`.
   `handles` must carry `:control-state`; `:event-stream` enables the
   process consumer to publish lifecycle events through this workflow's
   sequence counter."
  [workflow-id handles]
  (if-not (and (map? handles)
               (instance? clojure.lang.Atom (:control-state handles)))
    (anomaly/anomaly
     :invalid-input
     (messages/t :application/invalid-control-state)
     {:workflow-id workflow-id
      :control-state (:control-state handles)})
    (do
      (swap! live-runners assoc (str workflow-id) handles)
      nil)))

(defn ^{:stratum 1} register-degradation-manager!
  "Register the process-scoped degradation manager used by safe-mode
   interventions."
  [manager]
  (reset! process-degradation-manager manager)
  nil)

(defn ^{:stratum 1} register-resume-launcher!
  "Register the process-scoped resume launcher used by `:retry` /
   `:retry-from-phase`.

   `handles` must carry `:launch!` — `(fn [plan] → {:resume/run-id …})`
   — which starts a run from the resume plan
   [[ai.miniforge.operator.mechanism/resume-plan]] builds and reports
   the run id it started. `:events-dir` optionally overrides the event
   root the resume context is reconstructed from (default:
   `~/.miniforge/events`).

   Pass nil to clear. Without a registered launcher, retries fail
   `:no-resume-launcher` rather than parking."
  [handles]
  ;; `fn?`, not `ifn?`: a launcher must be an actual function. `ifn?`
  ;; also admits keywords / maps / sets, which would register silently
  ;; and only surface later as `:resume-not-dispatched` — reject the
  ;; misconfiguration here, where the message names it.
  (if-not (or (nil? handles)
              (and (map? handles) (fn? (:launch! handles))))
    (anomaly/anomaly
     :invalid-input
     (messages/t :application/invalid-resume-launcher)
     {:launch! (:launch! handles)})
    (do
      (reset! process-resume-launcher handles)
      nil)))

(defn ^{:stratum 1} register-policy-evaluator!
  "Register the process-scoped PR policy evaluator used by
   `:re-evaluate`.

   `evaluate` is `(fn [request] → evaluation)` where `request` is
   [[ai.miniforge.operator.mechanism/evaluation-request]] and
   `evaluation` is the `policy-pack/evaluate-external-pr` result shape
   (`:evaluation/passed?`, `:evaluation/violations`,
   `:evaluation/packs-applied`). Pass nil to clear.

   Without a registered evaluator, `:re-evaluate` fails
   `:no-policy-evaluator`. It never publishes a verdict it did not
   receive — a fabricated pass is worse than a visible failure."
  [evaluate]
  ;; `fn?`, not `ifn?` — see register-resume-launcher!. A keyword or map
  ;; is not an evaluator; reject it at registration, not as a confusing
  ;; `:invalid-policy-evaluation` on the first re-evaluate.
  (if-not (or (nil? evaluate) (fn? evaluate))
    (anomaly/anomaly
     :invalid-input
     (messages/t :application/invalid-policy-evaluator)
     {:evaluator evaluate})
    (do
      (reset! process-policy-evaluator evaluate)
      nil)))

(defn ^{:stratum 1} deregister-runner!
  "Remove `workflow-id` from the live-runner registry. Idempotent."
  [workflow-id]
  (swap! live-runners dissoc (str workflow-id))
  nil)

(defn ^{:stratum 1} live-intervention-stream
  "Return the registered event stream for a workflow-targeted
   intervention, or nil for process-global and unowned targets."
  [event]
  (when (= :workflow
           (intervention/intervention-target-type
            (:intervention/type event)))
    (:event-stream
     (get @live-runners (str (:intervention/target-id event))))))

;; The applier hook
(defn ^{:stratum 1} apply-intervention!
  "Apply one `:approved` intervention. This is the `:apply!` hook the
   operator-event consumer invokes (Phase D D-3, mechanisms extended in
   D-3b).

   Returns the final intervention map (state `:verified` or `:failed`),
   or nil when a lifecycle step was itself rejected (never expected
   from `:approved` input; nil keeps the caller honest rather than
   fabricating a state)."
  [stream interv]
  (let [verb (:intervention/type interv)
        entry (get @live-runners (str (:intervention/target-id interv)))]
    (if-let [dispatched (core/advance! stream interv intervention/dispatch)]
      (try
        (case verb
          (:pause :resume :cancel)
          (if entry
            (verbs/apply-control-verb! stream dispatched entry verb)
            (core/fail! stream dispatched :no-live-runner))

          (:acknowledge :request-human-review)
          (verbs/apply-no-effect-verb! stream dispatched verb)

          (:force-safe-mode :exit-safe-mode)
          (verbs/apply-safe-mode-verb! stream
                                       dispatched
                                       @process-degradation-manager
                                       verb
                                       interv)

          (:retry :retry-from-phase)
          (verbs/apply-resume-verb! stream
                                    dispatched
                                    @process-resume-launcher
                                    verb
                                    interv)

          :re-evaluate
          (verbs/apply-re-evaluate-verb! stream
                                         dispatched
                                         @process-policy-evaluator
                                         interv)

          ;; :waive — N5-delta-1 §6 Waiver records have a schema and a
          ;; TUI surface but no store: no event, no entity table, no
          ;; emitter. :request-replan — deferred to D-7 (phase-redirect
          ;; semantics). Loud, typed failure per the honesty doctrine
          ;; rather than a mechanism invented here.
          (core/fail! stream dispatched :not-implemented))
        (catch Exception _e
          ;; A throwing mechanism must not abort the consumer's pass —
          ;; the failure IS the outcome, recorded on the lifecycle.
          (core/fail! stream dispatched :application-error)))
      nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} live-intervention-target?
  "True when this process may claim `event` for application. Workflow
   targets must belong to a registered live runner; process-global
   intervention targets — and retry verbs, whose runner is gone by
   definition (see [[resume-ownership-verbs]]) — may be claimed by
   whichever serialized consumer sees them first."
  [event]
  (if-not (intervention/valid-type? (:intervention/type event))
    true
    (let [verb (:intervention/type event)
          canonical-target-type (intervention/intervention-target-type verb)
          declared-target-type (:intervention/target-type event)]
      (or (and declared-target-type
               (not= canonical-target-type declared-target-type))
          (contains? resume-ownership-verbs verb)
          (not= :workflow canonical-target-type)
          (live-runner? (:intervention/target-id event))))))
