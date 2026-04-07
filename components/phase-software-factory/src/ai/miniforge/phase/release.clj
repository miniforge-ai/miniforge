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
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.phase.messages :as messages]
            [ai.miniforge.phase.phase-result :as phase-result]
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

(defn- log-already-implemented!
  "Log when implement phase reports work already done."
  [ctx impl-status]
  (when (= :already-implemented impl-status)
    (let [logger (or (get-in ctx [:execution/logger])
                     (log/create-logger {:min-level :info :output :human}))]
      (log/info logger :release :release/skipped-already-implemented
                {:data {:summary (get-in ctx [:execution/phase-results :implement :result :summary])}}))))

(defn- assert-implement-artifact!
  "Throw when implement phase has no result or has not succeeded.

   In the new environment model, the implement result carries :status,
   :environment-id, :summary, and :metrics — NOT a serialized :output map.
   We check :status to confirm the phase completed successfully."
  [ctx impl-result]
  (let [impl-status (:status impl-result)
        succeeded?  (phase-result/succeeded? impl-result)]
    (when (or (nil? impl-result) (not succeeded?))
      (let [phase-results-keys (vec (keys (:execution/phase-results ctx)))
            logger (or (get-in ctx [:execution/logger])
                       (log/create-logger {:min-level :error :output :human}))]
        (log/error logger :release :release/no-implement-artifact
                   {:data {:implement-status impl-status
                           :phase-results-keys phase-results-keys}})
        (throw (ex-info (messages/t :release/no-implement-artifact)
                        {:phase            :release
                         :implement-status impl-status
                         :hint             (messages/t :release/no-implement-hint)}))))))

(defn- ctx-worktree-path
  "Resolve the working directory from phase context."
  [ctx]
  (or (get-in ctx [:execution/worktree-path])
      (get-in ctx [:worktree-path])
      (get-in ctx [:execution/opts :worktree-path])
      (System/getProperty "user.dir")))

(defn- git-dirty-files
  "Scan git working tree for new/modified/deleted files; return as :code/files entries.
   Used as fallback when the implement phase wrote files directly to disk
   (design docs, specs, UX assets, etc.) rather than returning them in a
   :code/files artifact."
  [root-path]
  (try
    (let [{:keys [out]} (shell/sh "git" "status" "--porcelain" "-uall" :dir root-path)
          lines (->> (str/split-lines (or out "")) (remove str/blank?))]
      (->> lines
           (keep (fn [line]
                   (let [xy   (subs line 0 2)
                         path (str/trim (subs line 3))
                         file (io/file root-path path)]
                     (cond
                       (str/includes? xy "D") {:path path :content "" :action :delete}
                       (.isFile file)         {:path path
                                               :content (slurp file)
                                               :action (if (str/starts-with? (str/trim xy) "?")
                                                         :create
                                                         :modify)}))))
           vec))
    (catch Exception _ [])))

(defn build-workflow-state
  "Build workflow state from phase context for the release executor.

   In the new environment model, code changes live in the execution environment's
   git working tree (:execution/worktree-path) rather than being serialized
   into phase results. This function reads the current git diff from the
   worktree to discover which files need to be released.

   Falls back gracefully if implement was :already-implemented (other phases
   such as verify or review may have produced git changes that still need
   releasing).

   Throws :release/zero-files when no changed files are found in the worktree."
  [ctx]
  (let [impl-result   (get-in ctx [:execution/phase-results :implement :result])
        impl-status   (:status impl-result)
        already-impl? (= :already-implemented impl-status)]
    (log-already-implemented! ctx impl-status)
    ;; Assert implement phase succeeded (unless :already-implemented).
    ;; In the environment model we check :status on the result map directly.
    (when-not already-impl?
      (assert-implement-artifact! ctx impl-result))
    ;; Discover files via the environment's git working tree.
    ;; Code provenance is environment-based: the agent writes to the worktree
    ;; and the PR diff is the authoritative record of what changed.
    (let [worktree-path (ctx-worktree-path ctx)
          files         (or (not-empty (git-dirty-files worktree-path))
                            (throw (ex-info (messages/t :release/zero-files)
                                            {:phase          :release
                                             :environment-id (:environment-id impl-result)
                                             :worktree-path  worktree-path})))
          code-artifacts [{:artifact/type    :code
                           :artifact/content {:code/id        (random-uuid)
                                              :code/language  "mixed"
                                              :code/files     files
                                              :code/summary   (get impl-result :summary
                                                                   (messages/t :release/changes-description))
                                              :environment-id (:environment-id impl-result)}}]
          input          (get-in ctx [:execution/input])]
      {:workflow/id       (or (get-in ctx [:execution/id]) (random-uuid))
       :workflow/phase    :release
       :workflow/spec     {:spec/description (or (:description input)
                                                 (:title input)
                                                 (messages/t :default/task-description))}
       :workflow/artifacts code-artifacts})))

(defn build-executor-context
  "Build context for the release executor from phase context."
  [ctx config]
  (let [on-chunk (phase/create-streaming-callback ctx :release)]
    (cond-> {:worktree-path (or (get-in ctx [:execution/worktree-path])
                                (get-in ctx [:worktree-path])
                                (get-in config [:worktree-path])
                                (get-in ctx [:execution/opts :worktree-path])
                                (System/getProperty "user.dir"))
             :executor (get-in ctx [:execution/executor])
             :environment-id (get-in ctx [:execution/environment-id])
             :logger (get-in ctx [:execution/logger])
             :llm-backend (get-in ctx [:execution/llm-backend])
             :artifact-store (get-in ctx [:execution/artifact-store])
             :event-stream (:event-stream ctx)
             ;; Allow disabling PR creation via config or execution opts
             :create-pr? (or (get-in ctx [:execution/opts :create-pr?])
                             (get config :create-pr? true))}
      on-chunk (assoc :on-chunk on-chunk))))

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
        impl-status (get-in ctx [:execution/phase-results :implement :result :status])

        logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :debug :output :human}))
        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :release)]
    ;; Short-circuit: skip release when there is nothing to release.
    ;; This covers:
    ;; - implement returned :already-implemented (no new code)
    ;; - plan returned :already-satisfied (DAG had 0 tasks, no implement ran)
    ;; In both cases, only skip if there are also no git changes from other phases.
    (if (and (#{:already-implemented :already-satisfied nil} impl-status)
             (empty? (git-dirty-files (ctx-worktree-path ctx))))
      (-> (phase-result/enter-context ctx :release nil gates budget start-time
                                      (phase-result/skipped :already-implemented))
          (assoc-in [:phase :status] :completed))
      (let [;; Build workflow state and context
            workflow-state (build-workflow-state ctx)
        _ (log/debug logger :release :release/workflow-state-built
                     {:data {:artifact-count (count (:workflow/artifacts workflow-state))
                             :file-count (count (get-in (first (:workflow/artifacts workflow-state))
                                                        [:artifact/content :code/files]))}})
        exec-context (build-executor-context ctx config)
        releaser-agent (get config :releaser-agent)
        ;; Execute the release phase
        ;; Emit agent-started telemetry event for release executor
        _ (phase/emit-agent-started! ctx :release :releaser)

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
                         {:release/id (get release-artifact :artifact/id (random-uuid))
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
                      (ex-info (messages/t :release/phase-failed)
                               {:errors (:errors exec-result)
                                :metrics (:metrics exec-result)}))))
                 (catch Exception e
                   (response/failure e)))

        ;; Emit agent-completed telemetry event for release executor
        _ (phase/emit-agent-completed! ctx :release :releaser result)]

    (-> (phase-result/enter-context ctx :release :releaser gates budget start-time result)
        ;; Store PR info at top level for easy access
        (cond-> (= :success (:status result))
          (assoc-in [:workflow/pr-info] (get-in (:output result) [:workflow/pr-info]))))))))

(defn leave-release
  "Post-processing for release phase.

   Records release metrics and PR info."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        release-data (when (= :success (:status result)) (:output result))
        release-metrics (get release-data :release/metrics {})
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
    ;; Handle retrying: increment iteration counter, then emit telemetry
    (doto (cond-> updated-ctx
            (registry/retrying? (:phase updated-ctx))
            (-> (update-in [:phase :iterations] (fnil inc 1))
                (assoc-in [:phase :last-error]
                          (get-in result [:error :message] (messages/t :release/phase-failed)))))
      (phase/emit-phase-completed! :release
        {:outcome     (if (= :completed phase-status) :success :failure)
         :duration-ms duration-ms
         :tokens      (:tokens metrics 0)}))))

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
          (assoc-in [:phase :error] (phase-result/exception-error ex)))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] (phase-result/exception-error ex))))))

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
