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
(ns ai.miniforge.phase-software-factory.implement-context
  "Inputs the implement phase reads from the execution context.

   The worktree path, the files in scope and their contents (from the
   cache, the context pack, a task capsule or disk), the context pack, the
   task base refs the artifact collector diffs against, review feedback and
   handoffs, prior gate and verify failures, and the streaming callback,
   peer advice and backend keyword `enter-implement` wires into the agent
   call. Split out of `implement` (rule 210 layer budget)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.interface.task-base :as task-base]
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.implement-verify-output :as verify-output]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.agent.interface.protocols.messaging :as messaging]
   [ai.miniforge.context-pack.interface :as context-pack]
   [ai.miniforge.repo-index.interface :as repo-index]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private current-head-ref
  "Git ref for the currently acquired promoted workspace."
  "HEAD")

(defn ^{:stratum 0} build-context-pack
  "Build a context pack for the implement phase, falling back gracefully."
  [worktree-path files-in-scope]
  (try
    (when-let [index (repo-index/build-index worktree-path)]
      (let [search-index (try (repo-index/build-search-index index)
                              (catch Exception _ nil))
            pack (context-pack/build-pack :implement index
                   {:files-in-scope files-in-scope
                    :search-index search-index
                    :search-query (when (seq files-in-scope)
                                    (first files-in-scope))})]
        (context-pack/->pack-context index pack)))
    (catch Exception _e
      nil)))

(defn ^{:stratum 0} resolve-worktree-path-result
  "Resolve the worktree path from execution context, returning a path or a
   canonical anomaly. Do not fall back to the host filesystem when no
   environment has been acquired."
  [ctx]
  (or (get ctx :execution/worktree-path)
      (anomaly/sub-anomaly :invalid-input
                           :anomalies.phase/enter-failed
                           (messages/t :implement/no-worktree)
                           {:ctx-keys (vec (keys ctx))})))

(defn ^{:stratum 0} resolve-files-in-scope
  "Resolve files-in-scope from input, checking context and intent."
  [input]
  (or (get-in input [:context :files-in-scope])
      (get-in input [:intent :scope])))

(defn ^{:stratum 0} load-files-from-capsule
  "Read scope files from inside a task capsule via execute-fn.
   Returns seq of {:path :content} maps, same shape as phase/load-files-in-scope."
  [execute-fn executor env-id worktree-path files-in-scope]
  (when (seq files-in-scope)
    (->> files-in-scope
         (keep (fn [path]
                 (try
                   (let [full-path (str worktree-path "/" path)
                         result (execute-fn executor env-id (str "cat " (agent/shell-quote full-path))
                                           {:workdir worktree-path})
                         content (get-in result [:data :stdout])]
                     (when (and content (zero? (get-in result [:data :exit-code] 1)))
                       {:path path :content content}))
                   (catch Exception _ nil))))
         vec)))

(defn ^{:stratum 0} resolve-review-feedback
  "Extract review feedback from phase results."
  [ctx]
  (or (get-in ctx [:execution/phase-results :review :result :output :review/feedback])
      (get-in ctx [:execution/phase-results :review :result :output :review/issues])))

(defn ^{:stratum 0} resolve-phase-handoff
  "Resolve the current review repair handoff targeting implement."
  [ctx]
  (get-in ctx [:execution/phase-results :review :phase/handoff]))

(defn ^{:stratum 0} assoc-optional-task-fields
  "Conditionally assoc repo-map, repo-index, context-pack, and knowledge-context onto a task."
  [task pack-ctx kb-context]
  (cond-> task
    (:repo-map-text pack-ctx)
    (assoc :task/repo-map (:repo-map-text pack-ctx))
    (:repo-index pack-ctx)
    (assoc :task/repo-index (:repo-index pack-ctx))
    (:context-pack pack-ctx)
    (assoc :task/context-pack (:context-pack pack-ctx))
    kb-context
    (assoc :task/knowledge-context kb-context)))

(defn ^{:stratum 0} create-streaming-callback
  "Create a streaming callback for agent output if event-stream is available."
  [ctx]
  (phase/create-streaming-callback ctx :implement))

(defn ^{:stratum 0} collect-peer-advice
  "Collect peer messages for the implementer agent if a message router is available."
  [ctx]
  (when-let [msg-router (:message-router ctx)]
    (try
      (let [msgs (messaging/get-messages-for-agent msg-router :implementer (:execution/id ctx))]
        (when (seq msgs)
          {:peer-messages (vec msgs)}))
      (catch Exception _e nil))))

(defn ^{:stratum 0} resolve-backend-keyword
  "Best-effort resolution of the configured LLM backend keyword from ctx.

   The live llm-backend client carries the keyword on its config at
   `[:llm-backend :config :backend]` — the canonical location elsewhere in
   the codebase (see agent/core) — so that wins. Falls back to the various
   user/self-healing config :llm maps, then :unknown."
  [ctx]
  (or (get-in ctx [:llm-backend :config :backend])
      (get-in ctx [:execution/opts :llm-backend :config :backend])
      (get-in ctx [:user-config :llm :backend])
      (get-in ctx [:config :llm :backend])
      (get-in ctx [:llm :backend])
      :unknown))

(defn ^{:stratum 0} prior-gate-failures
  "Structured gate errors from the prior denied attempts that redirected
   here, implement's own first, then verify's. Verify's gates (:tests-pass,
   :policy-verify) deny on the verify result and redirect to implement, and
   their errors travel the same [:phase/gate-failures] channel — so the
   failing tests reach the implementer as a denial, not only as test output.
   nil when no prior denial exists."
  [ctx]
  (seq (mapcat (fn [phase-name]
                 (get-in ctx [:execution/phase-results phase-name :phase/gate-failures]))
               [:implement :verify])))

(defn ^{:stratum 0} build-verify-failures
  "Extract lean verify-failure data from phase results.
   In the environment model, test metrics are in :result :metrics."
  [verify-result]
  (let [metrics  (get-in verify-result [:result :metrics])
        failures (get metrics :failures [])]
    {:test-results (cond-> {:pass-count (get metrics :pass-count 0)
                            :fail-count (get metrics :fail-count 0)
                            :summary    (get-in verify-result [:result :summary])}
                     (seq failures) (assoc :failing-tests (mapv :test failures)))
     :test-output (verify-output/failure-focused-output failures (get metrics :test-output))}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-worktree-path
  "Boundary wrapper around the anomaly-returning
   `resolve-worktree-path-result`; retained for existing exception callers."
  [ctx]
  (let [result (resolve-worktree-path-result ctx)]
    (if (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result)
                      (:anomaly/data result)))
      result)))

(defn ^{:stratum 1} base-ref-candidates
  "Return candidate refs for collecting the promoted task artifact."
  [ctx]
  (letfn [(present-string? [value]
            (and (string? value) (not (str/blank? value))))
          (branch-refs [branch]
            (when (present-string? branch)
              [(str "refs/remotes/origin/" branch)
               (str "origin/" branch)
               (str "refs/heads/" branch)]))]
    (let [base-sha (get-in ctx [:execution/environment-metadata :base-sha])
          base-branch (or (get-in ctx [:execution/environment-metadata :base-branch])
                          (get-in ctx [:execution/opts :branch])
                          (get-in ctx [:execution/input :branch]))
          resume-workspace (get-in ctx [:execution/opts :resume-workspace])
          original-commit (get-in ctx [:execution/input :context :git-commit])
          original-branch (get-in ctx [:execution/input :context :git-branch])
          original-refs (concat (when (present-string? original-commit)
                                  [original-commit])
                                (branch-refs original-branch))
          resume-ranges (when resume-workspace
                          (map #(str % "..." current-head-ref) original-refs))]
      (into []
            (comp (filter present-string?) (distinct))
            (concat resume-ranges
                    [base-sha]
                    (branch-refs base-branch))))))

(defn ^{:stratum 1} resolve-existing-files
  "Load existing files from cache, context pack, capsule, or disk."
  [ctx pack-ctx worktree-path files-in-scope]
  (or (get-in ctx [:execution/cached-files])
      (:existing-files pack-ctx)
      ;; In governed mode, read files from inside the capsule
      (when-let [execute-fn (get ctx :execution/execute-fn)]
        (load-files-from-capsule execute-fn
                                 (get ctx :execution/executor)
                                 (get ctx :execution/environment-id)
                                 (or (get ctx :execution/capsule-workdir) worktree-path)
                                 files-in-scope))
      (phase/load-files-in-scope worktree-path files-in-scope)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} task-base-refs
  "The task base refs to carry: the ones a previous implement attempt
   recorded (a resumed run restores them), else fresh candidates."
  [ctx]
  (or (task-base/recorded-refs ctx) (base-ref-candidates ctx)))
