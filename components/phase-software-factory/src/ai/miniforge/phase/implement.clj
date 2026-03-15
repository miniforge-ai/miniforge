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

(ns ai.miniforge.phase.implement
  "Implementation phase interceptor.

   Generates code artifacts from plans.
   Agent: :implementer
   Default gates: [:syntax :lint]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.phase.file-context :as file-ctx]
            [ai.miniforge.phase.agent-behavior :as agent-beh]
            [ai.miniforge.phase.messages :as messages]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.knowledge.interface :as knowledge]
            [ai.miniforge.repo-index.interface :as repo-index]
            [ai.miniforge.response.interface :as response]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :implementer
   :gates [:syntax :lint]
   :budget {:tokens 30000
            :iterations 8
            :time-seconds 600}
   ;; Implementation is code-heavy - hint at current Sonnet
   :model-hint :sonnet-4.6})

;; Register defaults on load
(registry/register-phase-defaults! :implement default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(def ^:private max-test-output-lines
  "Cap raw test output sent to implementer to avoid token waste.
   Failure summaries are typically in the first/last 30 lines."
  60)

(defn- truncate-test-output
  "Truncate test output to max-test-output-lines, keeping head and tail."
  [output]
  (when (string? output)
    (let [lines (str/split-lines output)
          n (count lines)]
      (if (<= n max-test-output-lines)
        output
        (let [head-n 30
              tail-n 25
              head (take head-n lines)
              tail (take-last tail-n lines)
              skipped (- n head-n tail-n)]
          (str/join "\n"
            (concat head
                    [(str "\n... [" skipped " lines omitted] ...\n")]
                    tail)))))))

(defn- build-repo-map-context
  "Build a repo map for the target repository, falling back gracefully.
   Returns {:repo-index <RepoIndex> :repo-map-text <string>} or nil."
  [worktree-path]
  (try
    (when-let [index (repo-index/build-index worktree-path)]
      {:repo-index index
       :repo-map-text (repo-index/repo-map-text index)})
    (catch Exception _e
      nil)))

(defn- build-verify-failures
  "Extract lean verify-failure data from phase results."
  [verify-result]
  (let [test-results (get-in verify-result [:result :output :metadata :test-results])
        test-results-lean (dissoc test-results :output)]
    {:test-results test-results-lean
     :test-output (truncate-test-output
                   (get-in verify-result [:result :output :metadata :test-results :output]))}))

(defn- resolve-worktree-path
  "Resolve the worktree path from context, falling back to user.dir."
  [ctx]
  (get ctx :worktree-path (System/getProperty "user.dir")))

(defn- resolve-files-in-scope
  "Resolve files-in-scope from input, checking context and intent."
  [input]
  (or (get-in input [:context :files-in-scope])
      (get-in input [:intent :scope])))

(defn- resolve-existing-files
  "Load existing files from cache, repo index, or disk."
  [ctx repo-ctx worktree-path files-in-scope]
  (or (get-in ctx [:execution/cached-files])
      (if (and (:repo-index repo-ctx) (seq files-in-scope))
        (repo-index/get-files (:repo-index repo-ctx) files-in-scope)
        (file-ctx/load-files-in-scope worktree-path files-in-scope))))

(defn- resolve-review-feedback
  "Extract review feedback from phase results."
  [ctx]
  (or (get-in ctx [:execution/phase-results :review :result :output :review/feedback])
      (get-in ctx [:execution/phase-results :review :result :output :review/issues])))

(defn- assoc-optional-task-fields
  "Conditionally assoc repo-map, repo-index, and knowledge-context onto a task."
  [task repo-ctx kb-context]
  (cond-> task
    (:repo-map-text repo-ctx)
    (assoc :task/repo-map (:repo-map-text repo-ctx))
    (:repo-index repo-ctx)
    (assoc :task/repo-index (:repo-index repo-ctx))
    kb-context
    (assoc :task/knowledge-context kb-context)))

(defn build-implement-task
  "Build the task map for the implementer agent from execution context."
  [ctx]
  (let [input (get-in ctx [:execution/input])
        plan-result (get-in ctx [:execution/phase-results :plan :result :output])
        verify-failure (get-in ctx [:execution/phase-results :verify])
        worktree-path (resolve-worktree-path ctx)
        files-in-scope (resolve-files-in-scope input)
        repo-ctx (or (get-in ctx [:execution/repo-context])
                     (build-repo-map-context worktree-path))
        existing-files (resolve-existing-files ctx repo-ctx worktree-path files-in-scope)
        behavior-addendum (agent-beh/load-and-filter-behaviors
                            :implement {:task {:task/intent (:intent input)}})
        review-feedback (resolve-review-feedback ctx)
        kb-context (knowledge/inject-and-format
                    (:knowledge-store ctx) :implementer (get input :tags []))
        base-task (assoc-optional-task-fields
                    {:task/id (random-uuid)
                     :task/type :implement
                     :task/description (:description input)
                     :task/title (:title input)
                     :task/intent (:intent input)
                     :task/constraints (:constraints input)
                     :task/plan plan-result
                     :task/existing-files existing-files
                     :task/behavior-addendum behavior-addendum}
                    repo-ctx kb-context)]
    (cond-> base-task
      verify-failure
      (assoc :task/verify-failures (build-verify-failures verify-failure))
      review-feedback
      (assoc :task/review-feedback review-feedback))))

(defn create-streaming-callback
  "Create a streaming callback for agent output if event-stream is available."
  [ctx]
  (when-let [es (:event-stream ctx)]
    (when-let [create-cb (requiring-resolve
                           'ai.miniforge.event-stream.interface/create-streaming-callback)]
      (create-cb es (:execution/id ctx) :implement
                 {:print? (not (:quiet ctx)) :quiet? (:quiet ctx)}))))

(defn collect-peer-advice
  "Collect peer messages for the implementer agent if a message router is available."
  [ctx]
  (when-let [msg-router (:message-router ctx)]
    (try
      (let [get-msgs (requiring-resolve
                       'ai.miniforge.agent.interface.protocols.messaging/get-messages-for-agent)
            msgs (get-msgs msg-router :implementer (:execution/id ctx))]
        (when (seq msgs)
          {:peer-messages (vec msgs)}))
      (catch Exception _e nil))))

(defn enter-implement
  "Execute implementation phase.

   Reads plan from context, invokes implementer agent,
   runs through inner loop with syntax/lint gates."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        implementer-agent (agent/create-implementer {})
        task (build-implement-task ctx)
        ;; Cache loaded files and repo context for subsequent retries
        ctx (cond-> ctx
              (not (get-in ctx [:execution/cached-files]))
              (assoc-in [:execution/cached-files] (:task/existing-files task))
              (and (:task/repo-index task) (not (get-in ctx [:execution/repo-context])))
              (assoc-in [:execution/repo-context]
                        {:repo-index (:task/repo-index task)
                         :repo-map-text (:task/repo-map task)}))
        on-chunk (create-streaming-callback ctx)
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))
        peer-advice (collect-peer-advice ctx)
        task (cond-> task
               peer-advice (assoc :task/peer-advice peer-advice))
        result (try
                 (agent/invoke implementer-agent task agent-ctx)
                 (catch Exception e
                   (response/failure e)))]
    (-> ctx
        (assoc-in [:phase :name] :implement)
        (assoc-in [:phase :agent] :implementer)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(def ^:private rate-limit-pattern
  "Lightweight rate limit detection for the phase level.
   Avoids a dependency on workflow/dag-resilience."
  #"(?i)you've hit your limit|rate.?limit|429|quota.?exceeded|resets \d+[ap]m")

(defn- rate-limit-in-result?
  "Check if an agent result contains rate limit indicators.
   Scans both error message and output text."
  [result]
  (some (fn [text]
          (and (string? text) (re-find rate-limit-pattern text)))
        [(get-in result [:error :message])
         (when (string? (:output result)) (:output result))]))

(defn- extract-error-message
  "Extract the most relevant error message from an agent result."
  [result default-msg]
  (or (not-empty (get-in result [:error :message]))
      (not-empty (get-in result [:output :error]))
      default-msg))

(defn- build-phase-error
  "Build a structured phase error map from an agent result."
  [result agent-status rate-limited? iterations]
  {:message (or (extract-error-message result nil)
                (when (string? (:output result))
                  (not-empty (:output result)))
                (messages/t :implement/exhausted-retries))
   :agent-status agent-status
   :rate-limited? (boolean rate-limited?)
   :iterations iterations})

(defn leave-implement
  "Post-processing for implementation phase.

   Records metrics, captures code artifacts."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        rate-limited? (and (= :error agent-status) (rate-limit-in-result? result))
        gate-failed? (= :failed (:phase/status (get-in ctx [:phase])))
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        phase-status (cond
                       gate-failed? :failed
                       (= :already-implemented agent-status) :already-implemented
                       ;; Rate limit: fail immediately, don't burn retry budget
                       rate-limited? :failed
                       :else (registry/determine-phase-status
                               agent-status iterations max-iterations))
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        cost-usd (or (:cost-usd result)
                     (:cost-usd metrics)
                     (* (get metrics :tokens 0) 0.000015))
        metrics (assoc metrics :cost-usd cost-usd :duration-ms duration-ms)
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :implementation :duration-ms] duration-ms)
                        (assoc-in [:metrics :implementation :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :implement)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) cost-usd)
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Capture inner-loop learning when repair succeeded (iterations > 1, completed)
    (when (= :completed phase-status)
      (knowledge/capture-repair-learning!
       (:knowledge-store ctx) :implementer
       (get-in ctx [:execution/input :title]) iterations))
    ;; Warn if result has no output and isn't already-implemented
    (when (and (not= :already-implemented agent-status)
               (nil? (:output result)))
      (println (messages/t :implement/warn-no-output)))
    ;; Handle retrying, failure, or already-implemented outcomes
    (cond-> updated-ctx
      (registry/retrying? (:phase updated-ctx))
      (-> (update-in [:phase :iterations] (fnil inc 1))
          (assoc-in [:phase :last-error]
                    (extract-error-message result (messages/t :implement/agent-error))))

      (= :failed phase-status)
      (assoc-in [:phase :error]
                (build-phase-error result agent-status rate-limited? iterations))

      (= :already-implemented agent-status)
      (assoc-in [:phase :skipped-reason] :already-implemented))))

(defn error-implement
  "Handle implementation phase errors.

   Attempts repair via inner loop if within budget."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 5)
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

(defmethod registry/get-phase-interceptor :implement
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::implement
     :config merged
     :enter (fn [ctx]
              (enter-implement (assoc ctx :phase-config merged)))
     :leave leave-implement
     :error error-implement}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get implement interceptor with defaults
  (registry/get-phase-interceptor {:phase :implement})

  ;; Get with overrides
  (registry/get-phase-interceptor {:phase :implement
                                   :budget {:tokens 50000}
                                   :gates [:syntax :lint :no-secrets]})

  ;; Check defaults
  (registry/phase-defaults :implement)

  :leave-this-here)
