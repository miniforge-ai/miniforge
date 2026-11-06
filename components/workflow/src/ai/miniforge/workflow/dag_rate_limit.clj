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
(ns ai.miniforge.workflow.dag-rate-limit
  "Checkpoint-and-pause handling for a batch that hit a rate limit,
   split out of `dag-orchestrator` (rule 210 — a 1810-line namespace
   with a non-monotonic layer stack, miniforge#1317). Kept separate
   from the scheduling loop itself so `execute-dag-loop`'s own in-file
   call depth doesn't have to absorb this chain too."
  (:require
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-finalize :as dag-finalize]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.dag-resilience :as resilience]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} make-resume-hint-event
  "Create a :dag/resume-hint event map for the event stream."
  [workflow-id reset-at auto-resume? completed-ids wait-ms]
  {:event/type :dag/resume-hint
   :event/timestamp (str (java.time.Instant/now))
   :workflow/id workflow-id
   :dag/reset-at (str reset-at)
   :dag/auto-resume? auto-resume?
   :dag/completed-task-ids (vec completed-ids)
   :dag/wait-ms wait-ms})

(defn- ^{:stratum 0} checkpoint-log-data
  "Build the log data map for a checkpoint event."
  [reset-at wait-ms completed-count message]
  {:data {:reset-at (str reset-at)
          :wait-minutes (long (/ wait-ms 60000))
          :completed-tasks completed-count
          :message message}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} emit-resume-hint!
  "Emit a :dag/resume-hint event if we know when the rate limit clears."
  [event-stream workflow-id reset-at auto-resume? completed-ids wait-ms]
  (when (and event-stream reset-at)
    (try
      (events/publish! event-stream
                       (make-resume-hint-event workflow-id reset-at auto-resume?
                                               completed-ids wait-ms))
      (catch Exception _ nil))))

(defn- ^{:stratum 1} log-checkpoint-info
  "Log actionable checkpoint info for the user."
  [logger auto-resume? reset-at wait-ms completed-count]
  (when logger
    (if auto-resume?
      (log/info logger :dag-orchestrator :dag/checkpoint-for-resume
                (checkpoint-log-data reset-at wait-ms completed-count
                                     "Workflow checkpointed. Resume with: miniforge run --resume <workflow-id>"))
      (log/info logger :dag-orchestrator :dag/checkpoint-for-manual-resume
                (checkpoint-log-data reset-at wait-ms completed-count
                                     "Rate limit too far out. Resume manually when ready.")))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} handle-rate-limit-in-batch
  "Handle rate-limited tasks in a batch. Returns either:
   - {:action :continue ...} to re-queue tasks (short wait or backend switch)
   - {:action :pause :result <paused-map>} to checkpoint and stop execution

   On the pause path, always emits :dag/paused (completed IDs + reason).
   When a reset time is known, also emits :dag/resume-hint with
   :reset-at so external schedulers can auto-resume — flagged
   auto-resumable for medium waits (30min-2hrs), manual-resume-only for
   long waits (>2hrs)."
  [context rate-limited-ids new-completed failed-ids all-results batch-results
   event-stream workflow-id logger]
  (let [decision (resilience/handle-rate-limited-batch
                  context rate-limited-ids new-completed logger batch-results)]
    (if (= :continue (:action decision))
      decision
      (let [;; `all-results` only holds PRIOR batches — the caller merges
            ;; the current batch in only on the :continue recur, which we
            ;; never reach on this pause path. Fold in this batch's own
            ;; completed results here so the paused result's artifacts
            ;; include tasks that finished before the rate limit hit.
            merged-results (merge all-results (select-keys batch-results new-completed))
            {:keys [artifacts]} (dag-finalize/aggregate-results merged-results)
            reset-at (:reset-at decision)
            auto-resume? (= :checkpoint-and-resume (:action decision))]
        ;; :dag/paused itself carries only completed-ids + reason; the
        ;; reset time (when known) goes out separately below via
        ;; :dag/resume-hint so schedulers can auto-resume without
        ;; parsing the free-text reason.
        (resilience/emit-dag-paused! event-stream workflow-id new-completed
                                     (:reason decision))
        (emit-resume-hint! event-stream workflow-id reset-at auto-resume?
                          new-completed (:wait-ms decision))
        (log-checkpoint-info logger auto-resume? reset-at
                             (:wait-ms decision 0) (count new-completed))
        {:action :pause
         :result (dag-plan/dag-execution-paused new-completed failed-ids
                                                artifacts decision)}))))
