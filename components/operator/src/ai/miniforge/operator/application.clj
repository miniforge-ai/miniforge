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
   interventions act only on workflows registered by a live runner in
   THIS process. Anything else fails visibly with a localized reason
   and a typed `:failure/code` — rather than pretending. The
   not-yet-applied verbs fail the same way: a red chip with a stable
   code beats silently-parked hope.

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
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.operator.mechanism :as mechanism]
   [ai.miniforge.operator.messages :as messages]
   [ai.miniforge.reliability.interface :as reliability]))

;------------------------------------------------------------------------------ Layer 0
;; Live-runner registry — process-scoped

(defonce ^:private live-runners
  ;; workflow-id string → {:control-state <atom>}
  (atom {}))

(defonce ^:private process-degradation-manager
  ;; Safe mode is a process-level `:degradation` target, not a workflow
  ;; target. Its canonical target id therefore is not a runner id.
  (atom nil))

(defonce ^:private process-resume-launcher
  ;; {:launch! (fn [plan] -> {:resume/run-id …}), :events-dir <dir-or-nil>}
  ;; A retry restarts a run whose runner is gone by definition, so it
  ;; cannot go through the live-runner registry. Starting a pipeline is
  ;; adapter work (workflow loader + LLM client), so the process owner
  ;; registers the handle — the same shape as the degradation manager.
  (atom nil))

(defonce ^:private process-policy-evaluator
  ;; (fn [request] -> {:evaluation/passed? … :evaluation/violations […]
  ;;                   :evaluation/packs-applied […]})
  ;; The return shape is `policy-pack/evaluate-external-pr`'s, so the
  ;; canonical implementer is a partial of it bound to a pack loader and
  ;; a PR fetcher — both adapter-owned.
  (atom nil))

(def ^:private failure-message-key-by-code
  {:application-error :application/application-error
   :control-state-readback-mismatch :application/control-state-readback-mismatch
   :missing-phase :application/missing-phase
   :no-degradation-manager :application/no-degradation-manager
   :no-live-runner :application/no-live-runner
   :no-policy-evaluator :application/no-policy-evaluator
   :no-resume-context :application/no-resume-context
   :no-resume-launcher :application/no-resume-launcher
   :not-implemented :application/not-implemented
   :policy-evaluation-readback-mismatch :application/policy-evaluation-readback-mismatch
   :resume-not-dispatched :application/resume-not-dispatched
   :resume-readback-mismatch :application/resume-readback-mismatch
   :safe-mode-readback-mismatch :application/safe-mode-readback-mismatch
   :unknown-phase :application/unknown-phase
   :unresolved-workflow-type :application/unresolved-workflow-type})

(def ^:private expected-degradation-mode-by-verb
  {:force-safe-mode :safe-mode
   :exit-safe-mode :nominal})

(defn register-runner!
  "Register a live runner's control handles for `workflow-id`.
   `handles` must carry `:control-state`; `:event-stream` enables the
   process consumer to publish lifecycle events through this workflow's
   sequence counter."
  [workflow-id handles]
  (when-not (and (map? handles)
                 (instance? clojure.lang.Atom (:control-state handles)))
    (throw (ex-info (messages/t :application/invalid-control-state)
                    {:workflow-id workflow-id
                     :control-state (:control-state handles)})))
  (swap! live-runners assoc (str workflow-id) handles)
  nil)

(defn register-degradation-manager!
  "Register the process-scoped degradation manager used by safe-mode
   interventions."
  [manager]
  (reset! process-degradation-manager manager)
  nil)

(defn register-resume-launcher!
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
  (when-not (or (nil? handles)
                (and (map? handles) (ifn? (:launch! handles))))
    (throw (ex-info (messages/t :application/invalid-resume-launcher)
                    {:launch! (:launch! handles)})))
  (reset! process-resume-launcher handles)
  nil)

(defn register-policy-evaluator!
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
  (when-not (or (nil? evaluate) (ifn? evaluate))
    (throw (ex-info (messages/t :application/invalid-policy-evaluator)
                    {:evaluator evaluate})))
  (reset! process-policy-evaluator evaluate)
  nil)

(defn deregister-runner!
  "Remove `workflow-id` from the live-runner registry. Idempotent."
  [workflow-id]
  (swap! live-runners dissoc (str workflow-id))
  nil)

(defn live-runner?
  [workflow-id]
  (contains? @live-runners (str workflow-id)))

(defn live-intervention-target?
  "True when this process may claim `event` for application. Workflow
   targets must belong to a registered live runner; process-global
   intervention targets may be claimed by whichever serialized consumer
   sees them first."
  [event]
  (if-not (intervention/valid-type? (:intervention/type event))
    true
    (let [canonical-target-type
          (intervention/intervention-target-type (:intervention/type event))
          declared-target-type (:intervention/target-type event)]
      (or (and declared-target-type
               (not= canonical-target-type declared-target-type))
          (not= :workflow canonical-target-type)
          (live-runner? (:intervention/target-id event))))))

(defn live-intervention-stream
  "Return the registered event stream for a workflow-targeted
   intervention, or nil for process-global and unowned targets."
  [event]
  (when (= :workflow
           (intervention/intervention-target-type
            (:intervention/type event)))
    (:event-stream
     (get @live-runners (str (:intervention/target-id event))))))

(defn- failure-message
  [reason-code]
  (messages/t (get failure-message-key-by-code
                   reason-code
                   :application/unknown-failure)))

(defn- intervention-justification
  [interv]
  (if-some [justification (:intervention/justification interv)]
    justification
    (messages/t :application/default-justification)))

(defn- transition-succeeded?
  [result]
  (true? (:success? result)))

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle publication helpers

(defn- advance!
  "Apply lifecycle `step-fn` to `interv`, publish the transition, and
   return the updated intervention. Returns nil when the lifecycle step
   itself is rejected."
  [stream interv step-fn & step-args]
  (let [result (apply step-fn interv step-args)]
    (if (transition-succeeded? result)
      (let [updated (:intervention result)]
        (consumer/publish-state-changed! stream updated)
        updated)
      nil)))

(defn- fail!
  [stream interv reason-code]
  (let [with-failure-code (assoc-in interv
                                    [:intervention/details :failure/code]
                                    reason-code)]
    (when-let [failed (advance! stream
                                with-failure-code
                                intervention/fail
                                (failure-message reason-code))]
      failed)))

(defn- verify-readback!
  "Shared tail for every readback-verified mechanism: verify `applied`
   when the mechanism's observable matches what the verb asked for, else
   fail with `mismatch-code`. Returns nil when the lifecycle step is
   itself rejected."
  [stream applied readback mismatch-code]
  (if (= (:observed readback) (:expected readback))
    (advance! stream applied intervention/verify readback)
    (fail! stream applied mismatch-code)))

;; ── Mechanisms ─────────────────────────────────────────────────────────────

(defn- control-state-effect!
  "Flip the control-state flag for `verb` and return the readback the
   verification step asserts."
  [control-state verb]
  (case verb
    :pause  (do (es/pause! control-state)
                {:verb :pause :observed (boolean (es/paused? control-state))
                 :expected true})
    :resume (do (es/resume! control-state)
                {:verb :resume :observed (boolean (es/paused? control-state))
                 :expected false})
    :cancel (do (es/cancel! control-state)
                {:verb :cancel :observed (boolean (es/cancelled? control-state))
                 :expected true})))

(defn- apply-control-verb!
  [stream dispatched entry verb]
  (let [readback (control-state-effect! (:control-state entry) verb)]
    (when-let [applied (advance! stream dispatched intervention/apply-result)]
      (verify-readback! stream applied readback
                        :control-state-readback-mismatch))))

(defn- safe-mode-effect!
  "Move the degradation manager for `verb` and return the readback the
   verification step asserts."
  [manager verb interv]
  (let [justification (intervention-justification interv)]
    (case verb
      :force-safe-mode
      (reliability/enter-safe-mode! manager :manual justification)
      :exit-safe-mode
      (reliability/exit-safe-mode! manager
                                   justification
                                   (:intervention/requested-by interv)))
    {:verb verb
     :observed (reliability/degradation-mode manager)
     :expected (get expected-degradation-mode-by-verb verb)}))

(defn- apply-safe-mode-verb!
  [stream dispatched manager verb interv]
  (if manager
    (let [readback (safe-mode-effect! manager verb interv)]
      (when-let [applied (advance! stream dispatched intervention/apply-result)]
        (verify-readback! stream applied readback
                          :safe-mode-readback-mismatch)))
    (fail! stream dispatched :no-degradation-manager)))

(defn- apply-no-effect-verb!
  "Verbs whose whole effect IS the supervisory record (Phase D mapping:
   `supervisory-state only`). Dispatch → applied → verified with no
   machine touch."
  [stream dispatched verb]
  (when-let [applied (advance! stream dispatched intervention/apply-result)]
    (advance! stream applied intervention/verify {:verb verb})))

(defn- resume-events-dir
  [launcher]
  (or (:events-dir launcher) (es/default-events-dir)))

(defn- dispatch-resume!
  "Hand `plan` to the launcher, then read the run it reports back
   through the resume machinery itself. The readback is deliberately
   the resume component's view rather than the launcher's return value:
   a launcher naming a run it never started must not buy a `verified`
   chip."
  [stream dispatched launcher events-dir verb plan]
  (let [run-id (mechanism/launched-run-id ((:launch! launcher) plan))]
    (if-not run-id
      (fail! stream dispatched :resume-not-dispatched)
      (let [readback {:verb verb
                      :resume/run-id run-id
                      :resume/from-phase (:resume/from-phase plan)
                      :observed (mechanism/resume-observable? events-dir run-id)
                      :expected true}]
        (when-let [applied (advance! stream dispatched
                                     intervention/apply-result readback)]
          (verify-readback! stream applied readback
                            :resume-readback-mismatch))))))

(defn- apply-resume-verb!
  "Rebuild resume state for a retry, then dispatch it.

   Every rejection is typed and lands before the launcher runs — a
   request naming a phase the run never reached must not be guessed
   into a plan and started."
  [stream dispatched launcher verb interv]
  (if-not launcher
    (fail! stream dispatched :no-resume-launcher)
    (let [events-dir (resume-events-dir launcher)
          prepared (mechanism/prepare-resume events-dir interv verb)]
      (if-let [failure-code (:failure/code prepared)]
        (fail! stream dispatched failure-code)
        (dispatch-resume! stream dispatched launcher events-dir verb
                          (:resume/plan prepared))))))

(defn- apply-re-evaluate-verb!
  "Run the registered evaluator, publish its verdict as a gate event,
   and read the materialized entity table back.

   `verified` means a PolicyEvaluation that did not exist before the
   publish exists after it — a new immutable record per N5-delta-1
   §12.2, not a mutation of the evaluation being re-run."
  [stream dispatched evaluate interv]
  (if-not evaluate
    (fail! stream dispatched :no-policy-evaluator)
    (let [evaluation (evaluate (mechanism/evaluation-request interv))
          readback (mechanism/record-policy-evaluation! stream interv evaluation)]
      (when-let [applied (advance! stream dispatched
                                   intervention/apply-result readback)]
        (verify-readback! stream applied readback
                          :policy-evaluation-readback-mismatch)))))

;------------------------------------------------------------------------------ Layer 2
;; The applier hook

(defn apply-intervention!
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
    (if-let [dispatched (advance! stream interv intervention/dispatch)]
      (try
        (case verb
          (:pause :resume :cancel)
          (if entry
            (apply-control-verb! stream dispatched entry verb)
            (fail! stream dispatched :no-live-runner))

          (:acknowledge :request-human-review)
          (apply-no-effect-verb! stream dispatched verb)

          (:force-safe-mode :exit-safe-mode)
          (apply-safe-mode-verb! stream
                                 dispatched
                                 @process-degradation-manager
                                 verb
                                 interv)

          (:retry :retry-from-phase)
          (apply-resume-verb! stream
                              dispatched
                              @process-resume-launcher
                              verb
                              interv)

          :re-evaluate
          (apply-re-evaluate-verb! stream
                                   dispatched
                                   @process-policy-evaluator
                                   interv)

          ;; :waive — N5-delta-1 §6 Waiver records have a schema and a
          ;; TUI surface but no store: no event, no entity table, no
          ;; emitter. :request-replan — deferred to D-7 (phase-redirect
          ;; semantics). Loud, typed failure per the honesty doctrine
          ;; rather than a mechanism invented here.
          (fail! stream dispatched :not-implemented))
        (catch Exception _e
          ;; A throwing mechanism must not abort the consumer's pass —
          ;; the failure IS the outcome, recorded on the lifecycle.
          (fail! stream dispatched :application-error)))
      nil)))
