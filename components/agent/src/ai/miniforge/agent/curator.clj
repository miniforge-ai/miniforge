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

(ns ai.miniforge.agent.curator
  "Curator agent — extension surface for validating what an agent did
   in the executor environment.

   Background. The implement-style flow has the agent writing directly
   into the executor environment (capsule or worktree); the environment
   itself is the artifact. The curator runs after the agent, reads the
   resulting state, and produces either a structured artifact (the
   `:implement` path) or a verdict on the agent's iteration progress
   (the `:merge-resolution` path added in v2 Stage 2 per spec §6.1.2).

   Multimethod surface. `curate` dispatches on `:curator/kind` so each
   agent flow gets its own check pipeline without forcing a one-size-
   fits-all shape on every call site:

   - `:implement`         (default) — produces a CuratedArtifact;
                          terminal `:curator/no-files-written` on empty
                          diff.
   - `:merge-resolution`  — validates the resolution agent's iteration:
                          terminal `:curator/markers-not-resolved` when
                          conflict markers remain in the worktree;
                          `:curator/recurring-conflict` when the
                          conflicted path set hasn't changed from the
                          prior iteration (the agent is stuck and the
                          loop should terminate before exhausting the
                          full budget).

   `curate-implement-output` is kept as a deprecated alias to
   `(curate (assoc input :curator/kind :implement))` so existing
   callers (`phase-software-factory.implement`) keep working unchanged.

   Two-layer design (the `:implement` path):
   - Layer 1 is deterministic (git-diff parsing, path heuristics). This
     path alone produces a valid artifact and never fails when a diff
     exists.
   - Layer 2 is optional LLM enrichment (natural-language summary,
     breaking-change inference). When absent or failing, the deterministic
     values are used.

   Attribution. The two-stage 'generator + curator' pattern is inspired
   by the subagent-driven-development skill from obra/superpowers (MIT,
   Jesse Vincent); the methodology integration is tracked in docs/design."
  (:require
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.repo-index.interface :as messages]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.schema.interface :as schema]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas — the artifact shape is the single source of truth. No map
;; literals with these keys outside of build-curated-artifact.

(def FileEntry
  "A single file entry in the code artifact."
  [:map
   [:path [:string {:min 1}]]
   [:content :string]
   [:action [:enum :create :modify :delete]]])

(def CuratedArtifact
  "Schema for the curator's structured output. Consumed by verify,
   review, release, and PR-doc generation phases."
  [:map
   [:code/id :uuid]
   [:code/files [:vector FileEntry]]
   [:code/summary [:string {:min 1}]]
   [:code/tests-added? :boolean]
   [:code/scope-deviations [:vector :string]]
   [:code/breaking-change? :boolean]
   [:code/rationale {:optional true} [:maybe :string]]
   [:code/language {:optional true} [:maybe :string]]
   [:code/curated-at inst?]
   [:code/curator-source [:enum :deterministic :hybrid]]])

(defn validate-curated-artifact
  "Validate a CuratedArtifact, returning schema/valid or schema/invalid."
  [artifact]
  (if (m/validate CuratedArtifact artifact)
    (schema/valid)
    (schema/invalid (schema/explain CuratedArtifact artifact)
                    {:errors (schema/explain CuratedArtifact artifact)})))

(def curator-system-prompt
  "System prompt for the curator agent. Loaded from resources/prompts/curator.edn.
   Lazy so deterministic-only mode works without the prompt resource."
  (delay
    (try
      (prompts/load-prompt :curator)
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 1
;; Deterministic helpers — pure, table-driven where possible

(def ^:private test-path-patterns
  "Regex set — any match flags a path as test-code for :code/tests-added?.
   Kept as data so the detection is a single `some` over a table, not a
   growing cond ladder."
  [#"(^|/)test/"
   #"(^|/)spec/"
   #"_test\.[A-Za-z0-9]+$"
   #"\.test\.[A-Za-z0-9]+$"
   #"_spec\.[A-Za-z0-9]+$"])

(defn- test-path?
  "True when `path` matches any of the test-path patterns."
  [path]
  (boolean (some #(re-find % path) test-path-patterns)))

(defn- detect-tests-added
  "True when any file in the diff is a test file."
  [files]
  (boolean (some (comp test-path? :path) files)))

(defn- detect-scope-deviations
  "Return paths in `files` that are not in `intent-scope`. When
   `intent-scope` is nil/empty, returns []."
  [files intent-scope]
  (if (empty? intent-scope)
    []
    (let [scope-set (set intent-scope)]
      (->> files
           (map :path)
           (remove scope-set)
           sort
           vec))))

(defn- detect-language
  "Infer the primary language from the most common file extension.
   Returns nil when no file has a recognizable extension."
  [files]
  (let [extensions (->> files
                        (keep #(second (re-find #"\.([A-Za-z0-9]+)$" (:path % ""))))
                        frequencies)]
    (when (seq extensions)
      (key (apply max-key val extensions)))))

(defn- action-counts
  "Return a map of action → file count for the given files.
   Canonical action-counting so callers don't scatter `filter #(= :X (:action %))`."
  [files]
  (-> {:create 0 :modify 0 :delete 0}
      (merge (frequencies (map :action files)))))

(defn- deterministic-summary
  "Produce a summary string from file counts and actions using the
   :curator/deterministic-summary template in the messages catalog."
  [files]
  (let [n (count files)
        counts (action-counts files)]
    (messages/t :curator/deterministic-summary
                {:total n
                 :s (if (= 1 n) "" "s")
                 :created (:create counts)
                 :modified (:modify counts)
                 :deleted (:delete counts)})))

;------------------------------------------------------------------------------ Layer 1
;; Diff collection — resolves files from multiple possible sources

(defn- files-from-result
  "Extract :code/files from the implementer's result, if already present.
   (The existing file-fallback path may have populated this.)"
  [implementer-result]
  (get-in implementer-result [:output :code/files]))

(defn- files-via-executor
  "Collect files via capsule executor (governed mode)."
  [{:keys [pre-session-snapshot executor execute-fn env-id worktree-path]}]
  (when (and pre-session-snapshot executor execute-fn env-id worktree-path)
    (get (file-artifacts/collect-written-files-via-executor
          pre-session-snapshot execute-fn executor env-id worktree-path)
         :code/files)))

(defn- files-via-local-snapshot
  "Collect files via local git status (non-capsule fallback)."
  [{:keys [pre-session-snapshot worktree-path]}]
  (when (and pre-session-snapshot worktree-path)
    (get (file-artifacts/collect-written-files
          pre-session-snapshot worktree-path)
         :code/files)))

(def non-substantive-paths
  "Paths the curator drops before assessing 'files written.'

   These are runtime/session artifacts miniforge writes as side effects,
   not implementer work. A diff consisting only of these MUST be treated
   as empty — iter 23 receipt: the implementer produced no source, only
   `.miniforge-session-id` got bumped, curator blessed it as an artifact,
   reviewer rejected, release opened an empty-diff PR anyway.

   Add entries here when a new runtime artifact would otherwise count as
   'implementation.'"
  #{".miniforge-session-id"})

(defn substantive-file?
  "True when the file entry represents real implementer work, not a
   runtime/session side-effect. Exposed via the agent interface so
   other phases (e.g. release) can filter the same way."
  [{:keys [path]}]
  (and (string? path)
       (not (contains? non-substantive-paths path))
       (not (.startsWith ^String path ".miniforge/"))))

(defn collect-files
  "Resolve the file diff from available sources, in priority order:
   1. implementer-result has :code/files already (fast path)
   2. fresh collection via executor (capsule mode)
   3. fresh collection via local git status (non-capsule)

   Filters non-substantive files (session markers, miniforge runtime
   artifacts) — these shouldn't count as implementer output.

   Returns a vector (possibly empty) of file entries."
  [input]
  (filterv substantive-file?
           (or (files-from-result (:implementer-result input))
               (files-via-executor input)
               (files-via-local-snapshot input)
               [])))

;------------------------------------------------------------------------------ Layer 2
;; Optional LLM enrichment

(defn- build-llm-prompt
  "Build the curator user prompt by assembling message-catalog templates.
   No raw prompt strings in this namespace — all text lives in the
   repo-index message catalog (see resources/config/repo-index/messages/en-US.edn)."
  [files intent-scope spec-description]
  (let [total (count files)
        shown (take 50 files)
        overflow (- total 50)
        section (fn [header body] (str "\n\n## " header "\n\n" body))
        files-list (str/join "\n"
                             (for [f shown]
                               (str "- [" (name (:action f :modify)) "] " (:path f)
                                    " (" (count (:content f "")) " chars)")))]
    (str (messages/t :prompt/curator-intro)
         (section (messages/t :prompt/curator-spec-header)
                  (or spec-description (messages/t :prompt/curator-spec-unknown)))
         (section (messages/t :prompt/curator-scope-header)
                  (if (seq intent-scope)
                    (str/join "\n" (map #(str "- " %) intent-scope))
                    (messages/t :prompt/curator-scope-unknown)))
         (section (str (messages/t :prompt/curator-files-header) " (" total ")")
                  (cond-> files-list
                    (pos? overflow)
                    (str "\n" (messages/t :prompt/curator-files-overflow
                                          {:count overflow}))))
         (section (messages/t :prompt/curator-output-header)
                  (str (messages/t :prompt/curator-output-instruction)
                       "\n\n"
                       (messages/t :prompt/curator-output-example))))))

(defn- parse-llm-response
  "Extract the curator's EDN report from a free-form LLM response.
   Returns nil on parse failure — caller falls back to deterministic values."
  [content]
  (when (string? content)
    (try
      (let [trimmed (str/trim content)
            candidate (or (re-find #"(?s)\{[^{}]*:summary[^{}]*\}" trimmed)
                          trimmed)
            parsed (edn/read-string candidate)]
        (when (map? parsed) parsed))
      (catch Exception _ nil))))

(defn- try-llm-chat
  "Call the LLM backend; return parsed EDN response or nil on any failure.
   Isolates the try/catch so the caller has a linear happy-path shape."
  [llm-client system-prompt user-prompt]
  (try
    (let [result (llm/chat llm-client user-prompt
                           {:system system-prompt
                            :temperature 0.1
                            :max-tokens 800})]
      (when (llm/success? result)
        (parse-llm-response (llm/get-content result))))
    (catch Exception _ nil)))

(defn- llm-enrichment-fields
  "Project a parsed LLM response onto the :llm/... namespace used by the
   artifact constructor. Driven by a small key-map so additions don't
   grow the conditional tree."
  [parsed]
  (when (map? parsed)
    {:llm/summary   (:summary parsed)
     :llm/breaking? (boolean (:breaking-change? parsed))
     :llm/rationale (:rationale parsed)}))

(defn- enrich-via-llm
  "Call the LLM backend for a curator report. Returns enrichment fields
   on success, nil otherwise. Deterministic path is the single fallback."
  [llm-client files intent-scope spec-description]
  (when (and llm-client (seq files))
    (let [sys-prompt (or @curator-system-prompt
                         (messages/t :prompt/curator-system-fallback))
          user-prompt (build-llm-prompt files intent-scope spec-description)]
      (llm-enrichment-fields (try-llm-chat llm-client sys-prompt user-prompt)))))

;------------------------------------------------------------------------------ Layer 3
;; Artifact construction — single source of truth for CuratedArtifact shape

(defn- build-curated-artifact
  "Produce a validated CuratedArtifact from already-computed fields.
   This is the ONLY place the artifact map literal lives — downstream
   code that needs these keys consumes the output of this function."
  [{:keys [files summary tests-added? deviations language enrichment source]}]
  {:code/id (random-uuid)
   :code/files files
   :code/summary summary
   :code/tests-added? tests-added?
   :code/scope-deviations deviations
   :code/breaking-change? (boolean (:llm/breaking? enrichment))
   :code/rationale (:llm/rationale enrichment)
   :code/language language
   :code/curated-at (java.util.Date.)
   :code/curator-source source})

(defn- artifact-metrics
  "Standard metrics payload derived from the same action-counts table."
  [files deviations tests-added? source]
  (let [counts (action-counts files)]
    {:files-total (count files)
     :files-created (:create counts)
     :files-modified (:modify counts)
     :files-deleted (:delete counts)
     :scope-deviations (count deviations)
     :tests-added? tests-added?
     :curator-source source}))

;------------------------------------------------------------------------------ Layer 4
;; Public API

(defn- empty-diff-error
  "Build the terminal 'no files written' error. The :code keyword is the
   stable contract the phase runner branches on to stop retrying."
  [input]
  (response/error
   (messages/t :error/curator-no-files-written)
   {:data {:code :curator/no-files-written
           :worktree-path (:worktree-path input)
           :env-id (:env-id input)
           :intent-scope (:intent-scope input)}}))

(defmulti curate
  "Curator entry point. Dispatches on `:curator/kind` so each agent
   flow gets its own check pipeline. See namespace docstring for the
   full method roster."
  (fn [input] (or (:curator/kind input) :implement)))

(defmethod curate :implement
  [{:keys [intent-scope spec-description llm-client] :as input}]
  (let [files (collect-files input)]
    (if (empty? files)
      (empty-diff-error input)
      (let [tests-added? (detect-tests-added files)
            deviations (detect-scope-deviations files intent-scope)
            language (detect-language files)
            enrichment (enrich-via-llm llm-client files intent-scope spec-description)
            source (if enrichment :hybrid :deterministic)
            summary (or (not-empty (:llm/summary enrichment))
                        (deterministic-summary files))
            artifact (build-curated-artifact
                      {:files files
                       :summary summary
                       :tests-added? tests-added?
                       :deviations deviations
                       :language language
                       :enrichment enrichment
                       :source source})]
        (response/success
         artifact
         {:metrics (artifact-metrics files deviations tests-added? source)})))))

;------------------------------------------------------------------------------ Layer 4.5
;; Merge-resolution method (v2 Stage 2 — spec §6.1.2)
;;
;; The resolution sub-workflow runs an implementer-style agent against a
;; conflicted worktree. Between iterations, this curator method validates
;; whether the agent's edits actually resolved the conflicts and whether
;; it's making progress. The two terminal codes match the spec's curator
;; contract:
;;
;; - :curator/markers-not-resolved — git's conflict markers
;;   (`<<<<<<<`, `=======`, `>>>>>>>`) are still present in the worktree.
;;   The agent terminated but didn't finish the job. The loop above
;;   should re-prompt or, on persistent recurrence, terminate.
;; - :curator/recurring-conflict — the conflicted path set this
;;   iteration matches the prior iteration's path set, meaning the
;;   agent isn't making progress. The loop above should terminate
;;   instead of burning the rest of the budget.
;;
;; Both checks operate on pure-data inspections of the worktree state
;; (file scan + path-set comparison). They don't need the agent's
;; narration to fire.

(def ^:private conflict-marker-re
  "Match a line that begins (column 0, no leading whitespace per git's
   default emit format) with a run of 7+ identical conflict-marker
   characters. The three valid tokens are <<<<<<<, =======, >>>>>>>;
   we match each as a single-character run rather than `[<>=]{7,}`
   so a stray `<>=<>=<` mid-line on a non-marker line doesn't false-
   positive. The 7+ length matches git's default (exactly 7) plus the
   variable-length form git's parser also accepts."
  #"(?m)^(?:<{7,}|={7,}|>{7,})")

(defn- line-matches-marker?
  "True when `line` matches the conflict-marker pattern. Pulled out so
   the file-streaming reducer below stays focused on the streaming
   plumbing."
  [line]
  (boolean (re-find conflict-marker-re line)))

(defn- file-has-conflict-markers?
  "True when `path`'s contents contain a git conflict marker. Streams
   the file line-by-line and short-circuits on the first match — the
   first marker line answers the question, so we never load large
   files into memory just to scan them. Returns false on any read
   failure (file gone, permission denied, binary garbage) since the
   curator can only judge what it can read."
  [path]
  (try
    (and (fs/regular-file? path)
         (with-open [r (io/reader (str path))]
           (boolean (some line-matches-marker? (line-seq r)))))
    (catch Throwable _ false)))

(defn- conflicted-paths-via-git-grep
  "Use `git grep` to find tracked files containing conflict markers.
   When the worktree is a git tree (always true for v2 merge-resolution
   runs — `merge-parent-branches!` creates the worktree via
   `git worktree add`), this is much faster than walking the
   filesystem: git grep skips ignored / untracked files, uses its own
   indexed grep, and avoids slurping vendor / build dirs.

   Returns the set of relative paths on success, or nil to signal
   'fall back to the file walk' (worktree isn't a git tree, git grep
   isn't available, or some other failure mode)."
  [worktree-path]
  (let [r (try
            (shell/sh "git" "-C" (str worktree-path)
                      "grep" "--no-color" "-lE"
                      "^(<<<<<<<|=======|>>>>>>>)")
            (catch Throwable _ nil))]
    (cond
      ;; git grep convention: exit 0 = matches found; 1 = no matches.
      ;; Both are valid 'this worktree is grep-scannable' outcomes.
      (and r (zero? (:exit r)))
      (->> (str/split-lines (str (:out r)))
           (remove str/blank?)
           set)

      (and r (= 1 (:exit r)))
      #{}

      ;; Anything else — not a git tree, or git binary missing — bails
      ;; to the caller's fallback path.
      :else nil)))

(defn- conflicted-paths-via-file-walk
  "Fallback when git grep can't run (non-git worktree, etc.). Walks
   the worktree and streams each file looking for markers. Slower
   than git grep on large trees but correct."
  [worktree-path]
  (let [root (fs/canonicalize worktree-path)]
    (->> (fs/glob root "**" {:hidden false})
         (filter file-has-conflict-markers?)
         (map (fn rel-path [p] (str (fs/relativize root p))))
         set)))

(defn- scan-conflicted-paths
  "Return the set of relative paths in `worktree-path` whose contents
   contain a git conflict marker. Returns nil when `worktree-path` is
   not an existing directory — the caller (`:merge-resolution`
   curator) treats nil as a fault and surfaces a typed error rather
   than silently reporting 'no markers'."
  [worktree-path]
  (when (and (some? worktree-path) (fs/directory? worktree-path))
    (or (conflicted-paths-via-git-grep worktree-path)
        (conflicted-paths-via-file-walk worktree-path))))

(defn- worktree-missing-error
  "Terminal error when `worktree-path` is missing or not a directory.
   Surfacing this prevents the silent-success failure mode where a
   misconfigured input would report `:resolution/markers-cleared? true`
   purely because the scanner couldn't find any files (it never
   looked)."
  [worktree-path]
  (response/error
   (messages/t :error/curator-worktree-missing
               {:path (or worktree-path "<nil>")})
   {:data {:code :curator/worktree-missing
           :worktree-path worktree-path}}))

(defn- markers-not-resolved-error
  "Terminal error when conflict markers remain after the agent's
   iteration. `paths` is the set returned by `scan-conflicted-paths`."
  [worktree-path paths]
  (response/error
   (messages/t :error/curator-markers-not-resolved
               {:file-count (count paths)
                :s (if (= 1 (count paths)) "" "s")})
   {:data {:code :curator/markers-not-resolved
           :worktree-path worktree-path
           :conflicted-paths (vec (sort paths))}}))

(defn- recurring-conflict-error
  "Terminal error when the conflict path set is identical to the
   prior iteration's. Signals that the agent has stopped making
   progress; the resolution loop should terminate before exhausting
   the full budget."
  [worktree-path paths]
  (response/error
   (messages/t :error/curator-recurring-conflict
               {:path-count (count paths)
                :s (if (= 1 (count paths)) "" "s")})
   {:data {:code :curator/recurring-conflict
           :worktree-path worktree-path
           :conflicted-paths (vec (sort paths))}}))

(defmethod curate :merge-resolution
  [{:keys [worktree-path prior-conflicted-paths]
    :as input}]
  (let [current-paths (scan-conflicted-paths worktree-path)
        ;; Coerce prior-conflicted-paths to a set so callers can feed
        ;; the previous iteration's :conflicted-paths (a sorted vector
        ;; in our error data) back in directly without a type
        ;; mismatch hiding recurrence detection.
        prior-set (when (some? prior-conflicted-paths)
                    (set prior-conflicted-paths))]
    (cond
      ;; Worktree missing or not a directory — fault, not "markers
      ;; cleared". Surface explicitly so a misconfigured input doesn't
      ;; silently report success.
      (nil? current-paths)
      (worktree-missing-error worktree-path)

      ;; No markers remain — the agent did its job for this iteration.
      ;; The verify gate above will then check whether tests pass; the
      ;; curator's role is just the marker check.
      (empty? current-paths)
      (response/success
       {:resolution/markers-cleared? true}
       {:metrics {:conflicted-paths 0
                  :curator-source :deterministic}})

      ;; Markers still present AND the path set hasn't changed since
      ;; the prior iteration — agent is stuck. Terminate early.
      (and prior-set (= prior-set current-paths))
      (recurring-conflict-error worktree-path current-paths)

      ;; Markers still present but the path set has changed (some
      ;; resolved, others surfaced, or the conflict shape shifted).
      ;; The loop should re-prompt; this is a "keep going" terminal
      ;; for THIS iteration, not the whole loop.
      :else
      (markers-not-resolved-error worktree-path current-paths))))

;------------------------------------------------------------------------------ Layer 5
;; Backward-compatible entry point

(defn curate-implement-output
  "Take an implementer's result + environment state → structured code artifact.

   Fast-fails when no files were written (replacing the silent-retry failure
   mode where the implementer exhausts turns without producing output).

   This is a thin wrapper around `(curate (assoc input :curator/kind :implement))`.
   Existing callers (e.g. `phase-software-factory.implement`) keep working
   unchanged; new callers should prefer `curate` directly so the dispatch
   key is explicit.

   Input keys:
     :implementer-result    (required)  the map returned by the implementer agent
     :worktree-path         (required)  path to the environment's working dir
     :pre-session-snapshot              enables accurate diff (recommended)
     :env-id, :executor, :execute-fn    required for capsule-mode diff
     :intent-scope                      vec of paths declared in :spec/intent :scope
     :spec-description                  for LLM context
     :llm-client                        optional pre-resolved LLM client
     :logger                            optional

   Output:
     response/success with :output being a CuratedArtifact, or
     response/error when the implementer wrote no files."
  [input]
  (curate (assoc input :curator/kind :implement)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Deterministic-only curate (no LLM)
  (curate-implement-output
   {:implementer-result
    {:output
     {:code/files [{:path "src/foo/bar.clj"
                    :content "(ns foo.bar)"
                    :action :create}
                   {:path "test/foo/bar_test.clj"
                    :content "(ns foo.bar-test)"
                    :action :create}]}}
    :worktree-path "/tmp/wt"
    :intent-scope ["src/foo/bar.clj" "test/foo/bar_test.clj"]
    :spec-description "Add a bar function."})

  ;; Empty-diff fast fail
  (curate-implement-output
   {:implementer-result {:output {:code/files []}}
    :worktree-path "/tmp/wt"})

  :leave-this-here)
