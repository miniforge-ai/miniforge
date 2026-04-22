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

(ns ai.miniforge.phase-software-factory.review
  "Review phase interceptor.

   Performs code review and quality checks.
   Agent: :reviewer
   Default gates: [:review-approved :quality-check]"
  (:require            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            
            [ai.miniforge.phase-software-factory.knowledge-helpers :as kb-helpers]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.knowledge.interface :as knowledge]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :review))

;; Register defaults on load
(phase/register-phase-defaults! :review default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- create-streaming-callback
  "Create a streaming callback for agent output if event-stream is available."
  [ctx phase-name]
  (phase/create-streaming-callback ctx phase-name))

(defn- resolve-implement-artifact
  "Resolve the implement-phase artifact using three strategies:
   1. Serialized :artifact key in the implement result (legacy model)
   2. The result itself when it already contains :code/files (it IS the artifact)
   3. Fall back to collecting all changed files from the worktree on disk
      (environment-promotion model where the agent writes directly to the worktree)"
  [implement-result ctx]
  (or
   ;; Strategy 1 — explicit :artifact in result
   (:artifact implement-result)
   ;; Strategy 2 — result already is the artifact (has :code/files)
   (when (:code/files implement-result)
     implement-result)
   ;; Strategy 3 — read changed files from worktree (env-promotion model)
   (let [worktree-path (or (get ctx :execution/worktree-path)
                           (get ctx :worktree-path))]
     (when worktree-path
       (agent/collect-written-files (agent/empty-snapshot)
                                            worktree-path)))))

(defn- build-review-task
  "Build the task map for the reviewer agent from execution context.
   Returns {:task task-map :rules-manifest manifest-or-nil}."
  [ctx]
  (let [input (get-in ctx [:execution/input])
        implement-result (get-in ctx [:execution/phase-results :implement :result :output])
        verify-result (get-in ctx [:execution/phase-results :verify :result :output])
        {:keys [formatted manifest]} (kb-helpers/inject-with-manifest
                                       (:knowledge-store ctx) :reviewer (get input :tags []))
        artifact (resolve-implement-artifact implement-result ctx)
        task (cond-> {:task/id (random-uuid)
                      :task/type :review
                      :task/description (:description input)
                      :task/title (:title input)
                      :task/intent (:intent input)
                      :task/constraints (:constraints input)
                      :task/artifact artifact
                      :task/tests verify-result}
               formatted
               (assoc :task/knowledge-context formatted))]
    {:task task
     :rules-manifest manifest}))

(defn enter-review
  "Execute review phase.

   Runs code review, quality checks, and policy validation."
  [ctx]
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :review)
        reviewer-agent (agent/create-reviewer
                        (select-keys ctx [:llm-backend]))
        {:keys [task rules-manifest]} (build-review-task ctx)
        on-chunk (create-streaming-callback ctx :review)
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))

        ;; Emit agent-started telemetry event
        _ (phase/emit-agent-started! agent-ctx :review :reviewer)

        result (try
                 (agent/invoke reviewer-agent task agent-ctx)
                 (catch Exception e
                   (response/failure e)))

        ;; Emit agent-completed telemetry event
        _ (phase/emit-agent-completed! agent-ctx :review :reviewer result)]

    (-> (phase/enter-context ctx :review :reviewer gates budget start-time result)
        (assoc-in [:phase :rules-manifest] rules-manifest))))

(defn leave-review
  "Post-processing for review phase.

   Records review metrics: issues found, approval status.
   When review decision is :changes-requested and within iteration budget,
   sets status to :failed with :redirect-to :implement so the execution engine
   jumps back to implement with the review feedback attached."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        review-decision (get-in result [:output :review/decision])
        metrics (-> (get result :metrics {:tokens 0 :duration-ms duration-ms})
                    (assoc :duration-ms duration-ms))
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        ;; :rejected and :changes-requested both indicate the reviewer found
        ;; blocking issues. Iter 23 regression: :rejected fell through to
        ;; :completed, letting release open a PR against a reviewer-rejected
        ;; artifact. Both decisions now set :failed; within iteration budget
        ;; the phase redirects to :implement for repair.
        ;; Preserve :failed from gate validation — don't overwrite with :completed.
        gate-failed? (= :failed (:phase/status (get-in ctx [:phase])))
        reviewer-blocked? (contains? #{:rejected :changes-requested} review-decision)
        phase-status (cond
                       reviewer-blocked? :failed
                       gate-failed? :failed
                       :else :completed)
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :review :duration-ms] duration-ms)
                        (assoc-in [:metrics :review :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :review)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Handle reviewer-blocked (:changes-requested OR :rejected): redirect
    ;; to implement with review feedback for repair within iteration budget.
    (doto (if (and reviewer-blocked?
                   (< iterations max-iterations))
            (let [feedback (or (get-in result [:output :review/feedback])
                               (get-in result [:output :review/issues]))]
              (knowledge/capture-feedback-learning!
               (:knowledge-store ctx) :reviewer
               (get-in ctx [:execution/input :title]) feedback)
              (-> updated-ctx
                  (update-in [:phase :iterations] (fnil inc 1))
                  (assoc-in [:phase :review-feedback] feedback)
                  (assoc-in [:phase :redirect-to] :implement)))
            updated-ctx)
      (phase/emit-phase-completed! :review
        {:outcome     (if (= :completed phase-status) :success :failure)
         :duration-ms duration-ms
         :tokens      (:tokens metrics 0)}))))

(defn error-review
  "Handle review phase errors.

   On rejection, can redirect to :implement if on-fail specified."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)
        on-fail (get-in ctx [:phase-config :on-fail])]
    (cond
      ;; Within budget - retry
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Has on-fail transition - redirect
      on-fail
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :redirect-to] on-fail)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)}))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod phase/get-phase-interceptor-method :review
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::review
     :config merged
     :enter (fn [ctx]
              (enter-review (assoc ctx :phase-config merged)))
     :leave leave-review
     :error error-review}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/get-phase-interceptor {:phase :review})
  (phase/get-phase-interceptor {:phase :review :on-fail :implement})
  (phase/phase-defaults :review)
  :leave-this-here)
