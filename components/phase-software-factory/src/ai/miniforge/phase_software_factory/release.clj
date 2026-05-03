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

(ns ai.miniforge.phase-software-factory.release
  "Release phase interceptor.

   Prepares release artifacts and creates PRs using the release-executor component.
   All git/gh operations route through the DAG executor so governed-mode capsules
   never shell out to the host. The GitHub token is resolved and injected into the
   capsule as GH_TOKEN for gh CLI authentication.
   Agent: :releaser
   Default gates: [:release-ready]"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.messages :as messages]

            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.release-executor.interface :as release-executor]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :release))

;; Register defaults on load
(phase/register-phase-defaults! :release default-config)

;; Also register :done as a terminal phase
(phase/register-phase-defaults! :done
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

;; assert-implement-artifact! used to throw when the implement phase
;; reported anything other than :status :success. That gate disagreed with
;; verify and review, which read the environment directly and run regardless
;; of what the implement result map said. The disagreement surfaced under
;; the curator's :curator/no-files-written path: the agent narrates code in
;; chat, curator marks implement :failed, but persist still captures whatever
;; landed on disk and verify+review work fine against it. Release would
;; then explode with `:release/no-implement-artifact` even though there was
;; real work to release. Removed — the env-based `:release/zero-files`
;; check below is the authoritative gate, matching what verify and review
;; already do.

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
   :code/files artifact.

   Filters out non-substantive paths (session markers, miniforge runtime
   artifacts) via `agent/substantive-file?` — a diff consisting only of
   these must not count as releasable work. Matches the same filter the
   curator applies upstream (iter-23 regression: empty-diff PRs)."
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
           (filter agent/substantive-file?)
           vec))
    (catch Exception _ [])))

(defn- git-dirty-files-capsule
  "Scan git working tree inside a task capsule via execute-fn (N11 §9.3).
   Used for K8s executors where the filesystem is not locally accessible.
   execute-fn is dag-exec/execute! passed through context to avoid cross-component requires.

   Applies the same `agent/substantive-file?` filter as the local variant so
   session markers and miniforge runtime artifacts don't slip through the
   governed path (iter-23 empty-diff regression)."
  [execute-fn executor env-id root-path]
  (try
    (let [result (execute-fn executor env-id "git status --porcelain -uall" {:workdir root-path})
          out    (get-in result [:data :stdout] "")
          lines  (->> (str/split-lines out) (remove str/blank?))
          path-of (fn [line] (str/trim (subs line 3)))
          substantive-line? (fn [line]
                              (and (>= (count line) 3)
                                   (agent/substantive-file? {:path (path-of line)})))]
      (->> lines
           (filter substantive-line?)
           (keep (fn [line]
                   (let [status (str/trim (subs line 0 2))
                         path   (path-of line)]
                     (cond
                       (str/includes? status "D")
                       {:path path :content "" :action :delete}

                       :else
                       ;; Read file content via executor
                       (let [cat-result (execute-fn executor env-id (str "cat " root-path "/" path)
                                                    {:workdir root-path})
                             content (get-in cat-result [:data :stdout] "")]
                         {:path    path
                          :content content
                          :action  (if (= "??" status) :create :modify)})))))
           vec))
    (catch Exception _ [])))

(defn build-workflow-state
  "Build workflow state from phase context for the release executor.

   In the new environment model, code changes live in the execution
   environment's git working tree (:execution/worktree-path) rather than
   being serialized into phase results. This function reads the current
   git diff from the worktree to discover which files need to be released.

   The environment is the authoritative source — release proceeds when the
   worktree has dirty paths, regardless of what the implement phase's
   result map said. This matches verify and review, which both read the
   environment directly. The previous behavior (gating on
   `[:execution/phase-results :implement :result :status]`) disagreed with
   verify/review and dropped real work whenever the curator marked
   implement :failed despite persist capturing files on disk.

   Throws :release/zero-files when no changed files are found in the
   worktree — that's the only legitimate \"nothing to release\" condition
   in the env model."
  [ctx]
  (let [impl-result   (get-in ctx [:execution/phase-results :implement :result])
        impl-status   (:status impl-result)]
    (log-already-implemented! ctx impl-status)
    ;; Discover files via the environment's git working tree.
    ;; Code provenance is environment-based: the agent writes to the worktree
    ;; and the PR diff is the authoritative record of what changed.
    ;; In governed mode with a non-local executor, use capsule-aware git (N11 §9.3).
    (let [worktree-path (ctx-worktree-path ctx)
          governed?     (= :governed (get ctx :execution/mode))
          execute-fn    (get ctx :execution/execute-fn)
          executor      (get ctx :execution/executor)
          env-id        (get ctx :execution/environment-id)
          files         (or (not-empty (if (and governed? execute-fn executor env-id)
                                         (git-dirty-files-capsule execute-fn executor env-id worktree-path)
                                         (git-dirty-files worktree-path)))
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

(defn- resolve-github-token
  "Resolve GitHub token for capsule PR operations.
   Mirrors the resolution order from docker.clj's resolve-git-token:
   1. Execution context (passed by task runner)
   2. MINIFORGE_GIT_TOKEN env var (universal override)
   3. GH_TOKEN env var (GitHub-specific)
   4. `gh auth token` CLI fallback (host-side only, pre-capsule)"
  [ctx]
  (or (get-in ctx [:execution/opts :github-token])
      (get-in ctx [:execution/github-token])
      (System/getenv "MINIFORGE_GIT_TOKEN")
      (System/getenv "GH_TOKEN")
      (try (let [r (shell/sh "gh" "auth" "token")]
             (when (zero? (:exit r))
               (str/trim (:out r))))
           (catch Exception _ nil))))

(defn build-executor-context
  "Build context for the release executor from phase context.
   Includes :github-token so the release executor can inject GH_TOKEN
   into the capsule's environment for gh CLI authentication."
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
                             (get config :create-pr? true))
             ;; Resolve and inject GitHub token for capsule gh CLI auth
             :github-token (resolve-github-token ctx)}
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
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
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
    (if (and (contains? #{:already-implemented :already-satisfied nil} impl-status)
             (let [wt         (ctx-worktree-path ctx)
                   execute-fn (get ctx :execution/execute-fn)
                   executor   (get ctx :execution/executor)
                   env-id     (get ctx :execution/environment-id)]
               (empty? (if (and (= :governed (get ctx :execution/mode)) execute-fn executor env-id)
                         (git-dirty-files-capsule execute-fn executor env-id wt)
                         (git-dirty-files wt)))))
      (-> (phase/enter-context ctx :release nil gates budget start-time
                                      (phase/skipped :already-implemented))
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

    (-> (phase/enter-context ctx :release :releaser gates budget start-time result)
        ;; Store PR info at top level for easy access
        (cond-> (phase/result-succeeded? result)
          (assoc-in [:workflow/pr-info] (get-in (:output result) [:workflow/pr-info]))))))))

(defn leave-release
  "Post-processing for release phase.

   Records release metrics and PR info."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        release-data (when (phase/result-succeeded? result) (:output result))
        release-metrics (get release-data :release/metrics {})
        metrics (merge {:tokens 0 :duration-ms duration-ms} release-metrics)
        agent-status (:status result)
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        phase-status (phase/determine-phase-status
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
            (phase/retrying? (:phase updated-ctx))
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
        on-fail (get-in ctx [:phase-config :on-fail])
        error-map (phase/exception-error ex)]
    (cond
      ;; Within budget - retry
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Has on-fail transition - redirect
      on-fail
      (let [phase-result (-> (:phase ctx)
                             (phase/fail-and-request-redirect
                              error-map
                              on-fail))]
        (assoc ctx :phase phase-result))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map)))))) 

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod phase/get-phase-interceptor-method :release
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::release
     :config merged
     :enter (fn [ctx]
              (enter-release (assoc ctx :phase-config merged)))
     :leave leave-release
     :error error-release}))

(defmethod phase/get-phase-interceptor-method :done
  [config]
  (let [merged (phase/merge-with-defaults config)]
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
  (phase/get-phase-interceptor {:phase :release})
  (phase/get-phase-interceptor {:phase :done})
  (phase/phase-defaults :release)
  (phase/phase-defaults :done)
  (phase/list-phases)
  :leave-this-here)
