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
   THIS process. Anything else fails `:no-live-runner` — visible,
   typed, retryable — rather than pretending. The not-yet-applied
   verbs fail `:not-implemented` for the same reason: a red chip with
   a reason keyword beats silently-parked hope.

   **Verification is a readback, not an echo:** control-state verbs
   verify by reading the flag back (`paused?` / `cancelled?`), so a
   `verified` chip means the runner's own control flags actually
   changed."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.reliability.interface :as reliability]))

;------------------------------------------------------------------------------ Layer 0
;; Live-runner registry — process-scoped

(defonce ^:private live-runners
  ;; workflow-id string → {:control-state <atom>
  ;;                       :degradation-manager <manager, optional>}
  (atom {}))

(defn register-runner!
  "Register a live runner's control handles for `workflow-id`.
   `handles` must carry `:control-state`; `:degradation-manager` is
   optional (safe-mode verbs fail typed when absent)."
  [workflow-id handles]
  (swap! live-runners assoc (str workflow-id) handles)
  nil)

(defn deregister-runner!
  "Remove `workflow-id` from the live-runner registry. Idempotent."
  [workflow-id]
  (swap! live-runners dissoc (str workflow-id))
  nil)

(defn live-runner?
  [workflow-id]
  (contains? @live-runners (str workflow-id)))

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle publication helpers

(defn- advance!
  "Apply lifecycle `step-fn` to `interv`, publish the transition, and
   return the updated intervention. Returns nil (after publishing a
   `failed` transition where possible) when the step is rejected."
  [stream interv step-fn & step-args]
  (let [result (apply step-fn interv step-args)]
    (if (:success? result)
      (let [updated (:intervention result)]
        (consumer/publish-state-changed! stream updated)
        updated)
      nil)))

(defn- fail!
  [stream interv reason]
  (when-let [failed (advance! stream interv intervention/fail reason)]
    failed))

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
  [stream dispatched entry verb interv]
  (if-let [manager (:degradation-manager entry)]
    (do (case verb
          :force-safe-mode
          (reliability/enter-safe-mode! manager :manual
                                        (or (:intervention/justification interv)
                                            "operator intervention"))
          :exit-safe-mode
          (reliability/exit-safe-mode! manager
                                       (or (:intervention/justification interv)
                                           "operator intervention")
                                       (:intervention/requested-by interv)))
        (when-let [applied (advance! stream dispatched intervention/apply-result)]
          (advance! stream applied intervention/verify {:verb verb})))
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
          (if entry
            (apply-safe-mode-verb! stream dispatched entry verb interv)
            (fail! stream dispatched :no-live-runner))

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
