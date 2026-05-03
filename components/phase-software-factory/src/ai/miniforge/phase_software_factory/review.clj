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
            [ai.miniforge.phase-software-factory.messages :as messages]
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
   3. Serialized artifact under inner result :output
   4. Fall back to collecting all changed files from the worktree on disk
      (environment-promotion model where the agent writes directly to the worktree)
   If the persisted outer artifact is metadata-only, merge that metadata over the
   worktree-collected artifact so review sees full file content plus curator flags."
  [implement-phase-result ctx]
  (let [outer-artifact (:artifact implement-phase-result)
        inner-artifact (get-in implement-phase-result [:result :artifact])
        inner-output (get-in implement-phase-result [:result :output])
        worktree-path (or (get ctx :execution/worktree-path)
                          (get ctx :worktree-path))
        worktree-artifact (when worktree-path
                            (agent/collect-written-files (agent/empty-snapshot)
                                                         worktree-path))]
    (or
     ;; Strategy 1 — persisted outer artifact already includes file content
     (when (:code/files outer-artifact)
       outer-artifact)
     ;; Strategy 2 — explicit :artifact in inner result
     (when (:code/files inner-artifact)
       inner-artifact)
     ;; Strategy 3 — result already is the artifact (has :code/files)
     (when (:code/files implement-phase-result)
       implement-phase-result)
     ;; Strategy 4 — serialized artifact under inner :output
     (when (:code/files inner-output)
       inner-output)
     ;; Strategy 5 — metadata-only outer artifact merged over worktree content
     (when (and outer-artifact worktree-artifact)
       (merge worktree-artifact outer-artifact))
     ;; Strategy 6 — read changed files from worktree (env-promotion model)
     worktree-artifact)))

(defn- build-verify-review-input
  "Build a stable verify summary for the reviewer prompt from the full verify phase result."
  [verify-phase-result]
  (when verify-phase-result
    {:phase/status (get verify-phase-result :status)
     :result/status (get-in verify-phase-result [:result :status])
     :summary (or (get-in verify-phase-result [:result :summary])
                  (get-in verify-phase-result [:result :output :summary]))
     :metrics (get verify-phase-result :metrics
                   (get-in verify-phase-result [:result :metrics]))}))

(defn- build-review-task
  "Build the task map for the reviewer agent from execution context.
   Returns {:task task-map :rules-manifest manifest-or-nil}."
  [ctx]
  (let [input (get-in ctx [:execution/input])
        implement-phase-result (get-in ctx [:execution/phase-results :implement])
        verify-phase-result (get-in ctx [:execution/phase-results :verify])
        {:keys [formatted manifest]} (kb-helpers/inject-with-manifest
                                       (:knowledge-store ctx) :reviewer (get input :tags []))
        artifact (resolve-implement-artifact implement-phase-result ctx)
        task (cond-> {:task/id (random-uuid)
                      :task/type :review
                      :task/description (:description input)
                      :task/title (:title input)
                      :task/intent (:intent input)
                      :task/constraints (:constraints input)
                      :task/artifact artifact
                      :task/tests (build-verify-review-input verify-phase-result)}
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

(defn- terminate-stagnated
  "Mark a stagnated phase as failed and skip the redirect-to-implement
   path. Carries the fingerprint chain in :phase/error so the
   workflow runner / evidence bundle can report what didn't move."
  [ctx fingerprint-history]
  (-> ctx
      (assoc-in [:phase :stagnated?] true)
      (assoc-in [:phase :error]
                {:anomaly/category :anomalies.review/stagnation
                 :anomaly/message  (messages/t :review/stagnation)
                 :review/fingerprint-history (vec fingerprint-history)})))

(defn- redirect-to-implement
  "Update phase to redirect back to :implement with review feedback for
   repair. Caller is responsible for the iteration-budget guard."
  [updated-ctx feedback]
  (let [phase-result (-> (:phase updated-ctx)
                         (update :iterations (fnil inc 1))
                         (assoc :review-feedback feedback)
                         (phase/request-redirect :implement))]
    (assoc updated-ctx :phase phase-result)))

(defn- record-fingerprint
  "Append the latest review fingerprint to [:execution :review-fingerprints]."
  [ctx fingerprint]
  (update-in ctx [:execution :review-fingerprints] (fnil conj []) fingerprint))

(defn leave-review
  "Post-processing for review phase.

   Records review metrics: issues found, approval status.
   When review decision is :changes-requested and within iteration budget,
   sets status to :failed with a redirect transition request so the execution
   engine jumps back to implement with the review feedback attached.

   Stagnation guard: if the reviewer's actionable-issue fingerprint is
   identical to the immediately prior iteration's, the repair loop has
   produced no movement on the blocking complaints. Terminate with
   :anomalies.review/stagnation instead of redirecting — better to
   surface the loop than burn another budget cycle."
  [ctx]
  (let [start-time        (get-in ctx [:phase :started-at])
        end-time          (System/currentTimeMillis)
        duration-ms       (- end-time start-time)
        result            (get-in ctx [:phase :result])
        review-artifact   (get result :output)
        review-decision   (get-in result [:output :review/decision])
        metrics           (-> (get result :metrics {:tokens 0 :duration-ms duration-ms})
                              (assoc :duration-ms duration-ms))
        iterations        (get-in ctx [:phase :iterations] 1)
        max-iterations    (get-in ctx [:phase :budget :iterations]
                                  (get-in default-config [:budget :iterations]))
        ;; :rejected and :changes-requested both indicate the reviewer found
        ;; blocking issues. Iter 23 regression: :rejected fell through to
        ;; :completed, letting release open a PR against a reviewer-rejected
        ;; artifact. Both decisions now set :failed; within iteration budget
        ;; the phase requests a redirect to :implement for repair.
        ;; Preserve :failed from gate validation — don't overwrite with :completed.
        gate-failed?      (= :failed (:phase/status (get-in ctx [:phase])))
        reviewer-blocked? (contains? #{:rejected :changes-requested} review-decision)
        prior-history     (get-in ctx [:execution :review-fingerprints] [])
        current-fp        (agent/review-fingerprint review-artifact)
        stagnated?        (and reviewer-blocked?
                               (agent/review-stagnated? (peek prior-history)
                                                        current-fp))
        phase-status      (cond
                            reviewer-blocked? :failed
                            gate-failed?      :failed
                            :else             :completed)
        feedback          (or (get-in result [:output :review/feedback])
                              (get-in result [:output :review/issues]))
        within-budget?    (< iterations max-iterations)
        redirect?         (and reviewer-blocked? within-budget? (not stagnated?))
        updated-ctx       (-> ctx
                              (assoc-in [:phase :ended-at] end-time)
                              (assoc-in [:phase :duration-ms] duration-ms)
                              (assoc-in [:phase :status] phase-status)
                              (assoc-in [:phase :metrics] metrics)
                              (assoc-in [:metrics :review :duration-ms] duration-ms)
                              (assoc-in [:metrics :review :repair-cycles] (dec iterations))
                              ;; Merge agent metrics into execution metrics
                              (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                              (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0))
                              (record-fingerprint current-fp))]
    (when redirect?
      (knowledge/capture-feedback-learning!
       (:knowledge-store ctx) :reviewer
       (get-in ctx [:execution/input :title]) feedback))
    (doto (cond
            stagnated?
            (terminate-stagnated updated-ctx
                                 (conj prior-history current-fp))

            redirect?
            (redirect-to-implement updated-ctx feedback)

            :else
            (cond-> updated-ctx
              (= :completed phase-status)
              (update-in [:execution :phases-completed] (fnil conj []) :review)))
      (phase/emit-phase-completed! :review
        {:outcome     (if (= :completed phase-status) :success :failure)
         :duration-ms duration-ms
         :tokens      (:tokens metrics 0)}))))

(defn error-review
  "Handle review phase errors. Retries within budget; on exhaustion
   redirects via `:on-fail` (typically to `:implement`) when set,
   otherwise propagates. Delegates to the shared `phase/handle-error`
   helper."
  [ctx ex]
  (phase/handle-error ctx ex 2))

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
