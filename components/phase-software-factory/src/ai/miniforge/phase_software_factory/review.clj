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
            [ai.miniforge.response.interface :as response]
            [clojure.string :as str]))

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

(defn- rehydrate-from-paths
  "Reconstitute `:code/files` for an outer-artifact that was persisted as
   paths-only metadata by `lightweight-curated-artifact`.

   The implement phase boundary commits dirty work onto the task branch
   immediately after `leave-implement`, so by the time review runs the
   worktree is clean at HEAD and `git status --porcelain` reports
   nothing — the prior fallback (`collect-written-files (empty-snapshot)
   worktree-path`) returned an empty `:code/files` and the reviewer
   reported 'no files were created in the worktree' even though the
   committed branch had everything (observed: codex/event-log-tool-
   visibility dogfood, 2026-05-04). Reading the recorded paths off disk
   is cheap, deterministic, and avoids re-running git plumbing."
  [outer-artifact worktree-path]
  (when-let [paths (and outer-artifact
                        worktree-path
                        (seq (:code/file-paths outer-artifact)))]
    (let [files (try
                  (agent/rehydrate-files worktree-path paths
                                         (:code/file-actions outer-artifact))
                  (catch Exception _ nil))]
      (when (seq files)
        (assoc outer-artifact :code/files files)))))

(defn- resolve-implement-artifact
  "Resolve the implement-phase artifact using ordered strategies, from
   most-direct (full content already in the result) to last-resort
   (rebuild from the worktree).

   1. Outer `:artifact` already includes `:code/files`.
   2. Inner result's `:artifact` already includes `:code/files`.
   3. Result map IS the artifact (has `:code/files`).
   4. Inner result's `:output` includes `:code/files`.
   5. Lightweight outer artifact (paths-only) — read content from disk
      using `:code/file-paths` so review sees what landed even though
      the phase-boundary persist already cleaned the worktree.
   6. Metadata-only outer artifact merged over a worktree
      git-status snapshot (legacy fallback).
   7. Worktree git-status snapshot alone (env-promotion model)."
  [implement-phase-result ctx]
  (let [outer-artifact     (:artifact implement-phase-result)
        inner-artifact     (get-in implement-phase-result [:result :artifact])
        inner-output       (get-in implement-phase-result [:result :output])
        worktree-path      (or (get ctx :execution/worktree-path)
                               (get ctx :worktree-path))
        worktree-artifact  (when worktree-path
                             (agent/collect-written-files (agent/empty-snapshot)
                                                          worktree-path))]
    (or
     (when (:code/files outer-artifact)        outer-artifact)
     (when (:code/files inner-artifact)        inner-artifact)
     (when (:code/files implement-phase-result) implement-phase-result)
     (when (:code/files inner-output)          inner-output)
     (rehydrate-from-paths outer-artifact worktree-path)
     (when (and outer-artifact worktree-artifact)
       (merge worktree-artifact outer-artifact))
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

(def ^:private blocking-decisions
  "Reviewer decisions that mean 'don't ship this — fix it.' Iter 23
   regression: :rejected fell through to :completed, letting release
   open a PR against a reviewer-rejected artifact. Both decisions
   route through the :failed branch so the workflow can either redirect
   to :implement or terminate."
  #{:rejected :changes-requested})

(defn- terminate-stagnated
  "Mark a stagnated phase as failed and skip the redirect-to-implement
   path. Carries the fingerprint chain in :phase/error so the
   workflow runner / evidence bundle can report what didn't move.
   Sets both :message and :anomaly/message so display / diagnostic
   consumers that read either key get the localized text."
  [ctx fingerprint-history]
  (let [msg (messages/t :review/stagnation)]
    (-> ctx
        (assoc-in [:phase :stagnated?] true)
        (assoc-in [:phase :error]
                  {:message msg
                   :anomaly/category :anomalies.review/stagnation
                   :anomaly/message  msg
                   :review/fingerprint-history (vec fingerprint-history)}))))

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

(defn- mark-completed
  "Mark the review phase as completed in the execution-level
   phases-completed list. Only called on the :complete branch."
  [ctx]
  (update-in ctx [:execution :phases-completed] (fnil conj []) :review))

(defn- compute-stagnated?
  "True when the reviewer is blocked AND the new fingerprint matches
   the immediately prior fingerprint — i.e., the repair loop has
   produced no movement on the blocking complaints."
  [reviewer-blocked? prior-history current-fp]
  (and reviewer-blocked?
       (agent/review-stagnated? (peek prior-history) current-fp)))

(defn- compute-phase-status
  [reviewer-blocked? gate-failed?]
  (cond
    reviewer-blocked? :failed
    gate-failed?      :failed
    :else             :completed))

(defn- compute-decision
  "Pick the post-review action: :stagnated | :repair | :exhausted | :complete."
  [{:keys [reviewer-blocked? stagnated? within-budget? phase-status actionable-feedback?]}]
  (cond
    stagnated?                                               :stagnated
    (and reviewer-blocked? within-budget? actionable-feedback?) :repair
    (= :failed phase-status)                                 :exhausted
    :else                                                    :complete))

(defn- apply-decision
  "Apply the chosen post-review action to the updated context."
  [decision updated-ctx feedback fingerprint-history]
  (case decision
    :stagnated (terminate-stagnated updated-ctx fingerprint-history)
    :repair    (redirect-to-implement updated-ctx feedback)
    :exhausted updated-ctx
    :complete  (mark-completed updated-ctx)))

(defn- accumulate-base-ctx
  "Apply the always-on context updates: phase metadata, metrics,
   execution-level rollups, and the new fingerprint."
  [ctx end-time duration-ms phase-status metrics iterations current-fp]
  (-> ctx
      (assoc-in [:phase :ended-at] end-time)
      (assoc-in [:phase :duration-ms] duration-ms)
      (assoc-in [:phase :status] phase-status)
      (assoc-in [:phase :metrics] metrics)
      (assoc-in [:metrics :review :duration-ms] duration-ms)
      (assoc-in [:metrics :review :repair-cycles] (dec iterations))
      (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
      (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0))
      (record-fingerprint current-fp)))

(defn- review-feedback
  "Extract feedback the implementer should consume on a repair redirect."
  [result]
  (or (get-in result [:output :review/feedback])
      (get-in result [:output :review/issues])))

(defn- actionable-feedback?
  "True when review feedback gives implement something concrete to repair."
  [feedback]
  (cond
    (string? feedback)     (not (str/blank? feedback))
    (sequential? feedback) (seq feedback)
    :else                  (some? feedback)))

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
        gate-failed?      (= :failed (:phase/status (get-in ctx [:phase])))
        reviewer-blocked? (contains? blocking-decisions review-decision)
        prior-history     (get-in ctx [:execution :review-fingerprints] [])
        current-fp        (agent/review-fingerprint review-artifact)
        stagnated?        (compute-stagnated? reviewer-blocked? prior-history current-fp)
        phase-status      (compute-phase-status reviewer-blocked? gate-failed?)
        within-budget?    (< iterations max-iterations)
        feedback          (review-feedback result)
        decision          (compute-decision {:reviewer-blocked?    reviewer-blocked?
                                             :stagnated?           stagnated?
                                             :within-budget?       within-budget?
                                             :phase-status         phase-status
                                             :actionable-feedback? (actionable-feedback? feedback)})
        updated-ctx       (accumulate-base-ctx ctx end-time duration-ms phase-status
                                               metrics iterations current-fp)
        next-ctx          (apply-decision decision updated-ctx feedback
                                          (conj prior-history current-fp))]
    (when (= :repair decision)
      (knowledge/capture-feedback-learning!
       (:knowledge-store ctx) :reviewer
       (get-in ctx [:execution/input :title]) feedback))
    (phase/emit-phase-completed! next-ctx :review
      {:outcome     (if (= :completed phase-status) :success :failure)
       :duration-ms duration-ms
       :tokens      (:tokens metrics 0)})
    next-ctx))

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
