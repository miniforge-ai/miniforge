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
(ns ai.miniforge.workflow.execution-dag
  "After the plan phase, hand the plan to the DAG executor and fold its
   result back into the execution context, or record why the DAG
   executor was skipped.

   Split out of `execution` (rule 210). Copying sub-worktree changes into
   the parent lives in `execution-dag-sync`, the result summaries behind
   the skip diagnostic in `execution-result-summary`, and the event
   publishers in `execution-events`."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
            [ai.miniforge.workflow.execution-dag-sync :as dag-sync]
            [ai.miniforge.workflow.execution-events :as exec-events]
            [ai.miniforge.workflow.execution-result-summary :as result-summary]
            [ai.miniforge.workflow.execution-transition :as transition]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} extract-plan-from-phase-result
  "Extract a plan map from an interceptor-style phase result, if present."
  [phase-result]
  (let [output (get-in phase-result [:result :output])]
    (when (and (map? output) (:plan/id output))
      output)))

(defn- ^{:stratum 0} roll-dag-metrics-into-execution
  "Accumulate DAG sub-workflow tokens / cost / duration into top-line
   `:execution/metrics`. Without this, sub-workflow spend stays buried in
   `:execution/dag-result` and the run summary lies (`Cost: $0.0000` on a
   real spend). Applied on BOTH success and failure paths — tokens were
   spent either way."
  [ctx dag-result]
  (let [{:keys [tokens cost-usd duration-ms]} (:metrics dag-result)]
    (-> ctx
        (update-in [:execution/metrics :tokens]      (fnil + 0)   (or tokens 0))
        (update-in [:execution/metrics :cost-usd]    (fnil + 0.0) (or cost-usd 0.0))
        (update-in [:execution/metrics :duration-ms] (fnil + 0)   (or duration-ms 0)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dag-skip-reason
  "Return the reason DAG execution should be skipped, or nil if it should proceed.
   Reasons are keywords — :not-plan-phase, :disabled, :no-plan-id, :no-tasks."
  [phase-name phase-result ctx]
  (cond
    (not= :plan phase-name) :not-plan-phase
    (:disable-dag-execution ctx) :disabled
    :else (let [plan (extract-plan-from-phase-result phase-result)]
            (cond
              (nil? plan) :no-plan-id
              (empty? (:plan/tasks plan)) :no-tasks
              :else nil))))

(defn ^{:stratum 1} apply-dag-failure
  "Apply a failed DAG result to the execution context."
  [ctx dag-result transition-to-failed-fn]
  (transition-to-failed-fn
   (-> ctx
       (assoc :execution/dag-result dag-result)
       (roll-dag-metrics-into-execution dag-result)
       (update :execution/errors conj
               {:type :dag-execution-failed
                :dag-result dag-result}))))

(defn ^{:stratum 1} dag-skip-diagnostic
  "Produce a structured snapshot of phase-result for :no-plan-id / :no-tasks
   skips. Keys-only (no values) so the event stays bounded and we don't leak
   full plan content into the log.

   Returned shape:
     {:phase-result/keys [...]
      :result/keys       [...]
      :result/status     <value or nil>
      :output/type       :nil | :map | :sequential | :string | :other
      :output/keys       [...] (only if :map)
      :output/has-plan-id? bool (only if :map)
      :plan/task-count   int (only when reason is :no-tasks)
      :result/error      {:error/message ... :anomaly ... :error/data-keys ...}
                         (only when :result is a failure per summarize-error)}"
  [phase-result reason]
  (let [phase-keys    (some-> phase-result keys sort)
        result        (:result phase-result)
        result-keys   (some-> result keys sort)
        result-status (:status result)
        output        (:output result)
        output-type   (result-summary/classify-output output)
        output-keys   (when (= :map output-type) (sort (keys output)))
        has-plan-id?  (when (= :map output-type) (boolean (:plan/id output)))
        plan          (extract-plan-from-phase-result phase-result)
        error-summary (result-summary/summarize-error result)]
    (cond-> {:phase-result/keys (vec phase-keys)
             :result/keys       (vec result-keys)
             :result/status     result-status
             :output/type       output-type}
      (seq output-keys)       (assoc :output/keys (vec output-keys))
      (some? has-plan-id?)    (assoc :output/has-plan-id? has-plan-id?)
      error-summary           (assoc :result/error error-summary)
      (= :no-tasks reason)    (assoc :plan/task-count
                                     (count (:plan/tasks plan))))))

(defn ^{:stratum 1} apply-dag-success
  "Apply a successful DAG result to the execution context.
   Merges artifact provenance, copies sub-worktree file changes into the
   parent worktree, synthesizes an :implement phase result, and advances
   past implement to verify → review → release."
  [ctx dag-result pipeline transition-to-completed-fn transition-to-failed-fn]
  (let [artifacts    (:artifacts dag-result)
        task-count   (count artifacts)
        ;; Merge sub-worktree changes into parent worktree so the release
        ;; phase can discover dirty files via git status.
        parent-wt    (or (get ctx :execution/worktree-path)
                         (System/getProperty "user.dir"))
        sub-wt-paths (:worktree-paths dag-result)
        logger       (get ctx :execution/logger)
        sync-result  (when (and parent-wt (seq sub-wt-paths))
                       (dag-sync/merge-sub-worktree-changes! parent-wt sub-wt-paths logger))]
    (if (anomaly/anomaly? sync-result)
      ;; Anomaly already logged inside merge-sub-worktree-changes!; transition
      ;; the workflow to :failed so the runner loop receives a valid context map
      ;; and the failure is recorded in :execution/errors. The DAG finished
      ;; before the sync failed, so its artifacts and spend are kept as on the
      ;; success path: that work happened and the run summary must show it.
      (transition-to-failed-fn
       (-> ctx
           (update :execution/artifacts into artifacts)
           (assoc :execution/dag-result dag-result)
           (roll-dag-metrics-into-execution dag-result)
           (update :execution/errors conj
                   {:type    :sync-sub-worktrees-failed
                    :message (:anomaly/message sync-result)
                    :anomaly sync-result})))
      (let [;; Synthesize new-style implement phase result.
            synthesized-implement-result
            {:name   :implement
             :status :completed
             :result {:status         :success
                      :environment-id (get ctx :execution/environment-id)
                      :summary        (messages/t :status/dag-executed-summary
                                                  {:task-count task-count})
                      :metrics        (merge {:task-count task-count}
                                             (:metrics dag-result))}}
            ctx-with-dag (-> ctx
                             (update :execution/artifacts into artifacts)
                             (assoc :execution/dag-result dag-result)
                             (assoc-in [:execution/phase-results :implement]
                                       synthesized-implement-result)
                             (roll-dag-metrics-into-execution dag-result))
            ctx-after-plan
            (transition/apply-phase-transition ctx-with-dag
                                               :phase/succeed
                                               pipeline
                                               transition-to-completed-fn
                                               transition-to-failed-fn)]
        (if (phase/failed? ctx-after-plan)
          ctx-after-plan
          (transition/apply-phase-transition ctx-after-plan
                                             :phase/succeed
                                             pipeline
                                             transition-to-completed-fn
                                             transition-to-failed-fn))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} try-dag-execution
  "After plan phase, execute all plans via the DAG executor.

   The DAG executor is the universal executor — it handles both parallel
   and sequential plans. Returns updated context with DAG results and
   skipped-to index, or nil if DAG execution is not applicable.

   Always emits :workflow/dag-considered so the event log captures whether
   DAG fired and — when skipped — exactly why."
  [ctx phase-name phase-result pipeline
   transition-to-completed-fn transition-to-failed-fn]
  (let [skip-reason (dag-skip-reason phase-name phase-result ctx)]
    (if skip-reason
      (do
        (when (= :plan phase-name)
          (let [base  {:phase/name phase-name}
                extra (if (contains? #{:no-plan-id :no-tasks} skip-reason)
                        (assoc base :dag/diagnostic
                               (dag-skip-diagnostic phase-result skip-reason))
                        base)]
            (exec-events/emit-dag-considered! ctx :skipped skip-reason extra)
            (exec-events/emit-dag-skipped! ctx skip-reason extra)))
        nil)
      (let [plan (extract-plan-from-phase-result phase-result)
            ctx-with-resume (assoc ctx :pre-completed-ids
                                   (get-in ctx [:execution/opts :pre-completed-dag-tasks] #{}))
            _ (exec-events/emit-dag-considered! ctx :activated :plan-has-tasks
                                                {:plan/id (:plan/id plan)
                                                 :plan/task-count (count (:plan/tasks plan))})
            _ (exec-events/emit-dag-activated! ctx plan)
            dag-result (dag-orch/execute-plan-as-dag plan ctx-with-resume)]
        (if (schema/succeeded? dag-result)
          (apply-dag-success ctx dag-result pipeline
                             transition-to-completed-fn
                             transition-to-failed-fn)
          (apply-dag-failure ctx dag-result transition-to-failed-fn))))))
