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

(ns ai.miniforge.phase.release
  "Release phase interceptor.

   Prepares release artifacts and creates PRs using the release-executor component.
   Agent: :releaser
   Default gates: [:release-ready]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.phase.phase-config :as phase-config]
            [ai.miniforge.release-executor.interface :as release-executor]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :release))

;; Register defaults on load
(registry/register-phase-defaults! :release default-config)

;; Also register :done as a terminal phase
(registry/register-phase-defaults! :done
                                   {:agent nil
                                    :gates []
                                    :budget {:tokens 0 :iterations 1 :time-seconds 1}})

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn build-workflow-state
  "Build workflow state from phase context for the release executor."
  [ctx]
  ;; Read implement result from execution phase results (not :phases)
  ;; This is the canonical location where workflow runner stores phase outputs
  ;; Phase results contain the full phase map, so extract :result :output
  (let [implement-result (get-in ctx [:execution/phase-results :implement :result :output])
        _ (when-not implement-result
            (throw (ex-info "Release phase has no code artifact from implement phase"
                            {:phase :release
                             :implement-status (get-in ctx [:execution/phase-results :implement :result :status])
                             :hint "Implement phase may have failed or produced no output"})))
        _ (when (and (map? implement-result) (empty? (:code/files implement-result)))
            (throw (ex-info "Release phase received code artifact with zero files"
                            {:phase :release :artifact-id (:code/id implement-result)})))
        code-artifacts (if-let [artifacts (:artifacts implement-result)]
                         (map (fn [a] {:artifact/type :code
                                       :artifact/content a})
                              artifacts)
                         ;; Fallback: wrap result directly
                         [{:artifact/type :code
                           :artifact/content implement-result}])
        input (get-in ctx [:execution/input])]
    {:workflow/id (or (get-in ctx [:execution/id]) (random-uuid))
     :workflow/phase :release
     :workflow/spec {:spec/description (or (:description input)
                                           (:title input)
                                           "implement changes")}
     :workflow/artifacts code-artifacts}))

(defn build-executor-context
  "Build context for the release executor from phase context."
  [ctx config]
  {:worktree-path (or (get-in ctx [:execution/worktree-path])
                      (get-in ctx [:worktree-path])
                      (get-in config [:worktree-path]))
   :logger (get-in ctx [:execution/logger])
   :llm-backend (get-in ctx [:execution/llm-backend])
   :artifact-store (get-in ctx [:execution/artifact-store])
   ;; Allow disabling PR creation via config
   :create-pr? (get config :create-pr? true)})

(defn enter-release
  "Execute release phase.

   Uses the workflow release executor to:
   - Generate release metadata (branch name, commit message, PR title/body)
   - Create git branch
   - Write files and stage them
   - Commit changes
   - Push branch and create PR (if enabled)"
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Build workflow state and context
        workflow-state (build-workflow-state ctx)
        exec-context (build-executor-context ctx config)
        releaser-agent (get config :releaser-agent)

        ;; Execute the release phase
        result (try
                 (let [exec-result (release-executor/execute-release-phase
                                    workflow-state
                                    exec-context
                                    {:releaser releaser-agent})]
                   (if (:success? exec-result)
                     (let [release-artifact (first (:artifacts exec-result))
                           content (:artifact/content release-artifact)]
                       (response/success
                        (merge
                         {:release/id (or (:artifact/id release-artifact) (random-uuid))
                          :release/status :completed
                          :release/artifacts (:artifacts exec-result)}
                         ;; Include PR info for evidence bundle
                         (when (:pr-url content)
                           {:workflow/pr-info {:pr-number (:pr-number content)
                                               :pr-url (:pr-url content)
                                               :branch (:branch content)
                                               :commit-sha (:commit-sha content)}})
                         {:release/metrics (:metrics exec-result)})))
                     ;; Execution failed
                     (response/failure
                      (ex-info "Release phase failed"
                               {:errors (:errors exec-result)
                                :metrics (:metrics exec-result)}))))
                 (catch Exception e
                   (response/failure e)))]

    (-> ctx
        (assoc-in [:phase :name] :release)
        (assoc-in [:phase :agent] :releaser)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result)
        ;; Store PR info at top level for easy access
        (cond-> (= :success (:status result))
          (assoc-in [:workflow/pr-info] (get-in (:output result) [:workflow/pr-info]))))))

(defn leave-release
  "Post-processing for release phase.

   Records release metrics and PR info."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        release-data (when (= :success (:status result)) (:output result))
        release-metrics (or (:release/metrics release-data) {})
        metrics (merge {:tokens 0 :duration-ms duration-ms} release-metrics)
        agent-status (:status result)
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        phase-status (registry/determine-phase-status
                       agent-status iterations max-iterations)
        pr-info (get-in ctx [:workflow/pr-info])
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :release :duration-ms] duration-ms)
                        (assoc-in [:metrics :release :repair-cycles] (dec iterations))
                        ;; Include PR info in metrics for evidence bundle
                        (cond-> pr-info
                          (assoc-in [:metrics :release :pr-info] pr-info))
                        (update-in [:execution :phases-completed] (fnil conj []) :release)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Handle retrying: increment iteration counter
    (cond-> updated-ctx
      (registry/retrying? (:phase updated-ctx))
      (-> (update-in [:phase :iterations] (fnil inc 1))
          (assoc-in [:phase :last-error]
                    (or (get-in result [:error :message])
                        "Release phase failed"))))))

(defn error-release
  "Handle release phase errors."
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
;; Registry methods

(defmethod registry/get-phase-interceptor :release
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::release
     :config merged
     :enter (fn [ctx]
              (enter-release (assoc ctx :phase-config merged)))
     :leave leave-release
     :error error-release}))

(defmethod registry/get-phase-interceptor :done
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::done
     :config merged
     :enter (fn [ctx]
              (-> ctx
                  (assoc-in [:phase :name] :done)
                  (assoc-in [:phase :status] :completed)
                  (assoc-in [:execution :status] :completed)))
     :leave identity
     :error (fn [ctx _ex] ctx)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :release})
  (registry/get-phase-interceptor {:phase :done})
  (registry/phase-defaults :release)
  (registry/phase-defaults :done)
  (registry/list-phases)
  :leave-this-here)
