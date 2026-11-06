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
(ns ai.miniforge.agent.implementer.session
  "The implementer's agent session: the session checkpoint, the task's
   base refs, the progress monitors, the disallowed native tools, and the
   LLM session invocation."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.implementer.prompt :as impl-prompt]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.llm.interface :as llm]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private current-head-ref
  "Git ref for the currently acquired promoted workspace."
  "HEAD")

(def ^{:stratum 0} ^:private session-checkpoint-filename ".miniforge/session-id")

(def ^{:stratum 0} implementer-disallowed-tools
  "Native read/search tools the implementer MUST NOT call — forces it onto the
   MCP context cache (context_read/context_grep/context_glob), the same
   mechanism the planner and tester use. The cache is read-through +
   write-through (a miss reads from source-root, caches the content, records
   the miss), so context_read serves any file, not just the pre-populated set,
   and re-reads within the session hit.

   Write/Edit/MultiEdit stay allowed (the implementer's whole job) and so does
   Bash — the implementer may legitimately need a shell for build/git steps.
   That leaves a `cat`/`rg`-via-Bash cache-bypass open; if dogfood shows the
   implementer evading the cache through the shell, add \"Bash\" here (the
   planner disallows it for exactly that reason)."
  ["Read" "Grep" "Glob" "LS" "Agent"])

(defn- ^{:stratum 0} create-implementer-progress-monitor
  "Implementer main-turn progress monitor. Thresholds live in
   resources/prompts/implementer.edn (:prompt/progress-monitor) — the
   ceiling an implement turn gets before the LLM client cuts it
   (`:max-total-ms`). Returns nil when the block is absent so the LLM
   client's framework default applies. Mirrors the planner/reviewer
   factories."
  []
  (prompts/load-progress-monitor @impl-prompt/implementer-prompt-data :prompt/progress-monitor))

(defn ^{:stratum 0} create-implementer-retry-progress-monitor
  "Progress monitor for the short submission-only recovery turn
   (:prompt/submission-retry-monitor in implementer.edn); nil when absent."
  []
  (prompts/load-progress-monitor @impl-prompt/implementer-prompt-data :prompt/submission-retry-monitor))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-session-checkpoint
  "Read the Claude session UUID from the worktree's runtime directory.

   Returns nil on any failure (missing file, permission error, garbage
   content). Iter 24 receipt: the previous path (`.miniforge-session-id`
   at the worktree root) got committed into git and every fresh worktree
   inherited a stale UUID — passing `--resume <stale>` to Claude CLI
   caused the session to die in ~5s with no output. Path is now under
   `.miniforge/`, which is gitignored, and readers tolerate anything."
  [working-dir]
  (when working-dir
    (try
      (let [f (io/file working-dir session-checkpoint-filename)]
        (when (.exists f)
          (let [s (str/trim (slurp f))]
            (when-not (str/blank? s) s))))
      (catch Exception _ nil))))

(defn- ^{:stratum 1} write-session-checkpoint! [working-dir session-id]
  (when (and working-dir session-id)
    (let [f (io/file working-dir session-checkpoint-filename)]
      (io/make-parents f)
      (spit f session-id))))

(defn ^{:stratum 1} base-ref-candidates
  "Return candidate refs for computing the promoted task diff."
  [context]
  (let [base-sha (get-in context [:execution/environment-metadata :base-sha])
        base-branch (or (get-in context [:execution/environment-metadata :base-branch])
                        (get-in context [:execution/opts :branch])
                        (get-in context [:execution/input :branch]))
        resume-workspace (get-in context [:execution/opts :resume-workspace])
        original-commit (get-in context [:execution/input :context :git-commit])
        original-branch (get-in context [:execution/input :context :git-branch])
        original-refs (concat (when (and original-commit
                                         (not (str/blank? original-commit)))
                                [original-commit])
                              (when (and original-branch
                                         (not (str/blank? original-branch)))
                                [(str "refs/remotes/origin/" original-branch)
                                 (str "origin/" original-branch)
                                 (str "refs/heads/" original-branch)]))
        resume-ranges (when resume-workspace
                        (map #(str % "..." current-head-ref) original-refs))]
    (into []
          (distinct)
          (cond-> (vec resume-ranges)
            (and base-sha (not (str/blank? base-sha)))
            (conj base-sha)

            (and base-branch (not (str/blank? base-branch)))
            (into [(str "refs/remotes/origin/" base-branch)
                   (str "origin/" base-branch)
                   (str "refs/heads/" base-branch)])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} invoke-implementer-session
  "Session body for the implementer: cache files, build mcp-opts, call LLM."
  [session llm-client user-prompt effective-system-prompt config context on-chunk
   existing-files working-dir]
  (when (seq existing-files)
    (let [files-map (into {} (map (fn [f] [(:path f) (:content f)])
                                  existing-files))]
      (artifact-session/write-context-cache-for-session! session files-map)))
  (artifact-session/write-cursor-permissions-for-session! session implementer-disallowed-tools)
  (let [budget-usd (budget/resolve-cost-budget-usd :implementer config context)
        max-turns (get @impl-prompt/implementer-prompt-data :prompt/max-turns 10)
        resume-id (read-session-checkpoint working-dir)
        monitor (create-implementer-progress-monitor)
        mcp-opts (cond-> (artifact-session/session->mcp-opts session budget-usd max-turns)
                   true        (assoc :disallowed-tools implementer-disallowed-tools)
                   working-dir (assoc :workdir working-dir)
                   resume-id   (assoc :resume resume-id)
                   monitor     (assoc :progress-monitor monitor))
        result (if on-chunk
                 (llm/chat-stream llm-client user-prompt on-chunk
                                  (merge {:system effective-system-prompt} mcp-opts))
                 (llm/chat llm-client user-prompt
                           (merge {:system effective-system-prompt} mcp-opts)))]
    (when-let [new-session-id (:session-id result)]
      (write-session-checkpoint! working-dir new-session-id))
    result))
