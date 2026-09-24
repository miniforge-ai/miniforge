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
   [ai.miniforge.anomaly.interface :as anomaly]
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

(defn- ^{:stratum 0} await-then-record!
  "On the verification pool: wait for the launched run to show itself
   (`:await-start!`), then `record!` the readback — or fail with what the
   launcher reports. A throw here would vanish with the thread, so it is
   recorded as `:application-error`."
  [stream dispatched launcher launch record!]
  (try
    (let [started ((:await-start! launcher) launch)]
      (if (anomaly/anomaly? started)
        (apply core/fail! stream dispatched (core/anomaly-failure started :resume-not-started))
        (record!)))
    (catch Exception _e
      (core/fail! stream dispatched :application-error))))

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

(defn- ^{:stratum 1} record-resume-readback!
  "Read the launched run back through the resume machinery itself. The
   readback is the resume component's view, not the launcher's return
   value: a launcher naming a run it never started must not buy a
   `verified` chip."
  [stream dispatched events-dir verb plan run-id]
  (let [readback {:verb verb
                  :resume/run-id run-id
                  :resume/from-phase (:resume/from-phase plan)
                  :observed (mechanism/resume-observable? events-dir run-id)
                  :expected true}]
    (when-let [applied (core/advance! stream dispatched
                                      intervention/apply-result readback)]
      (verify-readback! stream applied readback
                        :resume-readback-mismatch))))

(defn- ^{:stratum 1} record-policy-evaluation!
  [stream dispatched interv evaluation]
  (let [readback (mechanism/record-policy-evaluation! stream interv evaluation)]
    (when-let [applied (core/advance! stream dispatched
                                      intervention/apply-result readback)]
      (verify-readback! stream applied readback
                        :policy-evaluation-readback-mismatch))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply-re-evaluate-verb!
  "Run the registered evaluator, publish its verdict as a gate event,
   and read the materialized entity table back.

   `verified` means a PolicyEvaluation that did not exist before the
   publish exists after it — a new immutable record per N5-delta-1
   §12.2, not a mutation of the evaluation being re-run. An evaluator
   that declines to give a verdict returns an anomaly, and the failure
   carries its reason; any other non-evaluation fails typed. Nothing is
   published for either — a verdict nobody computed is never recorded."
  [stream dispatched evaluate interv]
  (let [evaluation (when evaluate (evaluate (mechanism/evaluation-request interv)))]
    (cond
      (nil? evaluate) (core/fail! stream dispatched :no-policy-evaluator)
      (anomaly/anomaly? evaluation) (apply core/fail! stream dispatched
                                           (core/anomaly-failure evaluation :policy-evaluation-refused))
      (not (mechanism/valid-evaluation? evaluation)) (core/fail! stream dispatched :invalid-policy-evaluation)
      :else (record-policy-evaluation! stream dispatched interv evaluation))))

(defn ^{:stratum 2} apply-resume-verb!
  "Rebuild resume state for a retry, then hand the plan to the launcher.

   Every rejection is typed and lands before the launcher runs — a
   request naming a phase the run never reached must not be guessed
   into a plan and started. A launcher refusal fails with the code and
   details it names. A launcher with `:await-start!` returns once the
   run is spawned; the wait for the run to show itself, and the
   readback, run on the verification pool so neither the consumer's
   pass nor its cross-process lock is held. The intervention stays
   `:dispatched` until then."
  [stream dispatched launcher verb interv]
  (let [events-dir (core/resume-events-dir launcher)
        prepared (when launcher (mechanism/prepare-resume events-dir interv verb))
        plan (:resume/plan prepared)
        launch (when plan ((:launch! launcher) plan))
        run-id (mechanism/launched-run-id launch)
        record! #(record-resume-readback! stream dispatched events-dir verb plan run-id)]
    (cond
      (nil? launcher) (core/fail! stream dispatched :no-resume-launcher)
      (:failure/code prepared) (core/fail! stream dispatched (:failure/code prepared))
      (nil? run-id) (apply core/fail! stream dispatched
                           (core/anomaly-failure launch :resume-not-dispatched))
      (:await-start! launcher) (core/submit-verification!
                                dispatched
                                #(await-then-record! stream dispatched launcher launch record!))
      :else (record!))))
