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
   | :retry / :retry-from-phase / :waive / :re-evaluate / :request-replan | not yet applied — fail loudly |

   **Process-lifetime honesty:** control-state is in-process, so
   interventions act only on workflows registered by a live runner in
   THIS process. Anything else fails visibly with a localized reason
   and a typed `:failure/code` — rather than pretending. The
   not-yet-applied verbs fail the same way: a red chip with a stable
   code beats silently-parked hope.

   **Verification is a readback, not an echo:** control-state verbs
   verify by reading the flag back (`paused?` / `cancelled?`), so a
   `verified` chip means the runner's own control flags actually
   changed."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
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

(def ^:private failure-message-key-by-code
  {:application-error :application/application-error
   :control-state-readback-mismatch :application/control-state-readback-mismatch
   :no-degradation-manager :application/no-degradation-manager
   :no-live-runner :application/no-live-runner
   :not-implemented :application/not-implemented
   :safe-mode-readback-mismatch :application/safe-mode-readback-mismatch})

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
  (let [{:keys [observed expected] :as readback}
        (control-state-effect! (:control-state entry) verb)]
    (if-let [applied (advance! stream dispatched intervention/apply-result)]
      (if (= observed expected)
        (advance! stream applied intervention/verify readback)
        (fail! stream applied :control-state-readback-mismatch))
      nil)))

(defn- apply-safe-mode-verb!
  [stream dispatched manager verb interv]
  (if manager
    (let [justification (intervention-justification interv)
          _ (case verb
              :force-safe-mode
              (reliability/enter-safe-mode! manager :manual justification)
              :exit-safe-mode
              (reliability/exit-safe-mode! manager
                                           justification
                                           (:intervention/requested-by interv)))
          observed (reliability/degradation-mode manager)
          expected (get expected-degradation-mode-by-verb verb)
          readback {:verb verb :observed observed :expected expected}]
      (if-let [applied (advance! stream dispatched intervention/apply-result)]
        (if (= observed expected)
          (advance! stream applied intervention/verify readback)
          (fail! stream applied :safe-mode-readback-mismatch))
        nil))
    (fail! stream dispatched :no-degradation-manager)))

(defn- apply-no-effect-verb!
  "Verbs whose whole effect IS the supervisory record (Phase D mapping:
   `supervisory-state only`). Dispatch → applied → verified with no
   machine touch."
  [stream dispatched verb]
  (when-let [applied (advance! stream dispatched intervention/apply-result)]
    (advance! stream applied intervention/verify {:verb verb})))

;------------------------------------------------------------------------------ Layer 2
;; The applier hook

(defn apply-intervention!
  "Apply one `:approved` intervention. This is the `:apply!` hook the
   operator-event consumer invokes (Phase D D-3).

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

          ;; :retry / :retry-from-phase / :waive / :re-evaluate /
          ;; :request-replan — mechanisms not yet wired (resume
          ;; machinery, waiver records, policy re-evaluation). Loud,
          ;; typed failure per the honesty doctrine; D-3b lands them.
          (fail! stream dispatched :not-implemented))
        (catch Exception _e
          ;; A throwing mechanism must not abort the consumer's pass —
          ;; the failure IS the outcome, recorded on the lifecycle.
          (fail! stream dispatched :application-error)))
      nil)))
