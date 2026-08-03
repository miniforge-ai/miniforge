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
(ns ai.miniforge.operator.application.verbs
  "The per-verb appliers of the D-3/D-3b application layer, split out of
   `application` so no namespace in it carries more than three real
   layers (rule 210).

   Each `apply-*-verb!` takes an already-`:dispatched` intervention plus
   the handle its mechanism needs (a control-state, the degradation
   manager, the resume launcher, the policy evaluator — all supplied by
   `application` from its process-scoped registry, never reached for
   here), advances it to `:applied`, and verifies by readback through
   [[verify-readback!]]. The lifecycle publishers and raw effects they
   compose live one stratum down in
   [[ai.miniforge.operator.application.core]]. `application`'s
   `apply-intervention!` dispatches to these by verb."
  (:require
   [ai.miniforge.operator.application.core :as core]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.operator.mechanism :as mechanism]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-readback!
  "Shared tail for every readback-verified mechanism: verify `applied`
   when the mechanism's observable matches what the verb asked for, else
   fail with `mismatch-code`. Returns nil when the lifecycle step is
   itself rejected."
  [stream applied readback mismatch-code]
  (if (= (:observed readback) (:expected readback))
    (core/advance! stream applied intervention/verify readback)
    (core/fail! stream applied mismatch-code)))

(defn ^{:stratum 0} apply-no-effect-verb!
  "Verbs whose whole effect IS the supervisory record (Phase D mapping:
   `supervisory-state only`). Dispatch → applied → verified with no
   machine touch."
  [stream dispatched verb]
  (when-let [applied (core/advance! stream dispatched intervention/apply-result)]
    (core/advance! stream applied intervention/verify {:verb verb})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} apply-control-verb!
  [stream dispatched entry verb]
  (let [readback (core/control-state-effect! (:control-state entry) verb)]
    (when-let [applied (core/advance! stream dispatched intervention/apply-result)]
      (verify-readback! stream applied readback
                        :control-state-readback-mismatch))))

(defn ^{:stratum 1} apply-safe-mode-verb!
  [stream dispatched manager verb interv]
  (if manager
    (let [readback (core/safe-mode-effect! manager verb interv)]
      (when-let [applied (core/advance! stream dispatched intervention/apply-result)]
        (verify-readback! stream applied readback
                          :safe-mode-readback-mismatch)))
    (core/fail! stream dispatched :no-degradation-manager)))

(defn- ^{:stratum 1} dispatch-resume!
  "Hand `plan` to the launcher, then read the run it reports back
   through the resume machinery itself. The readback is deliberately
   the resume component's view rather than the launcher's return value:
   a launcher naming a run it never started must not buy a `verified`
   chip."
  [stream dispatched launcher events-dir verb plan]
  (let [run-id (mechanism/launched-run-id ((:launch! launcher) plan))]
    (if-not run-id
      (core/fail! stream dispatched :resume-not-dispatched)
      (let [readback {:verb verb
                      :resume/run-id run-id
                      :resume/from-phase (:resume/from-phase plan)
                      :observed (mechanism/resume-observable? events-dir run-id)
                      :expected true}]
        (when-let [applied (core/advance! stream dispatched
                                          intervention/apply-result readback)]
          (verify-readback! stream applied readback
                            :resume-readback-mismatch))))))

(defn ^{:stratum 1} apply-re-evaluate-verb!
  "Run the registered evaluator, publish its verdict as a gate event,
   and read the materialized entity table back.

   `verified` means a PolicyEvaluation that did not exist before the
   publish exists after it — a new immutable record per N5-delta-1
   §12.2, not a mutation of the evaluation being re-run."
  [stream dispatched evaluate interv]
  (if-not evaluate
    (core/fail! stream dispatched :no-policy-evaluator)
    (let [evaluation (evaluate (mechanism/evaluation-request interv))]
      ;; Validate BEFORE publishing: a nil/garbage result would otherwise
      ;; coerce to `passed? false`, mint a bogus :gate/failed record, and
      ;; verify — a verdict we never received. Fail typed, publish nothing.
      (if-not (mechanism/valid-evaluation? evaluation)
        (core/fail! stream dispatched :invalid-policy-evaluation)
        (let [readback (mechanism/record-policy-evaluation! stream interv evaluation)]
          (when-let [applied (core/advance! stream dispatched
                                            intervention/apply-result readback)]
            (verify-readback! stream applied readback
                              :policy-evaluation-readback-mismatch)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply-resume-verb!
  "Rebuild resume state for a retry, then dispatch it.

   Every rejection is typed and lands before the launcher runs — a
   request naming a phase the run never reached must not be guessed
   into a plan and started."
  [stream dispatched launcher verb interv]
  (if-not launcher
    (core/fail! stream dispatched :no-resume-launcher)
    (let [events-dir (core/resume-events-dir launcher)
          prepared (mechanism/prepare-resume events-dir interv verb)]
      (if-let [failure-code (:failure/code prepared)]
        (core/fail! stream dispatched failure-code)
        (dispatch-resume! stream dispatched launcher events-dir verb
                          (:resume/plan prepared))))))
