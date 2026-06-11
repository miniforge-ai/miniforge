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

(ns ai.miniforge.agent.implementer
  "Implementer agent implementation.
   Generates code from plans and task descriptions."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.agent.submission-recovery :as submission-recovery]
   [ai.miniforge.patterns.interface :as patterns]
   [ai.miniforge.repo-index.interface :as messages]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workspace.interface :as workspace]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Implementer-specific schemas

(def CodeFile
  [:map
   [:path [:string {:min 1}]]
   [:content [:string {:min 0}]]
   [:action [:enum :create :modify :delete]]])

(def CodeArtifact
  [:map
   [:code/id uuid?]
   [:code/files [:vector CodeFile]]
   [:code/dependencies-added {:optional true} [:vector [:string {:min 1}]]]
   [:code/tests-needed? {:optional true} boolean?]
   [:code/language {:optional true} [:string {:min 1}]]
   [:code/summary {:optional true} [:string {:min 1}]]
   [:code/created-at {:optional true} inst?]])

(def implementer-system-prompt
  "System prompt for the implementer agent.
   Loaded from EDN resource for configurability."
  (delay (prompts/load-prompt :implementer)))

(def ^:private implementer-prompt-data
  "Full prompt data map for the implementer agent."
  (delay (prompts/load-prompt-data :implementer)))

;------------------------------------------------------------------------------ Layer 0
;; Extension → language mapping (loaded from config)

(def ^:private impl-config-path "config/repo-index/implementer.edn")

(def ^:private current-head-ref
  "Git ref for the currently acquired promoted workspace."
  "HEAD")

(defn- load-ext->language []
  (if-let [res (io/resource impl-config-path)]
    (get-in (edn/read-string (slurp res)) [:repo-index/implementer :extension->language] {})
    {}))

(def ^:private ext->language (delay (load-ext->language)))

;------------------------------------------------------------------------------ Layer 1
;; Validation

(defn- find-empty-creates
  "Find :create files with empty content."
  [files]
  (filter (fn [f] (and (= :create (:action f)) (empty? (:content f)))) files))

(defn- find-duplicate-paths
  "Find file paths that appear more than once."
  [files]
  (->> (map :path files)
       frequencies
       (filter #(> (val %) 1))))

(defn validate-code-artifact
  [artifact]
  (if-not (m/validate CodeArtifact artifact)
    (schema/invalid (schema/explain CodeArtifact artifact)
                    {:errors (schema/explain CodeArtifact artifact)})
    (let [files (:code/files artifact)
          empty-creates (find-empty-creates files)
          duplicate-paths (find-duplicate-paths files)]
      (cond
        (seq empty-creates)
        (schema/invalid (str "Empty content for :create files: " (mapv :path empty-creates))
                        {:errors {:files (str "Empty content for :create files: "
                                              (mapv :path empty-creates))}})
        (seq duplicate-paths)
        (schema/invalid (str "Duplicate file paths: " (keys duplicate-paths))
                        {:errors {:files (str "Duplicate file paths: " (keys duplicate-paths))}})
        :else
        (schema/valid)))))

;------------------------------------------------------------------------------ Layer 1
;; Language detection

(defn- extract-extension
  "Extract file extension from a path."
  [path]
  (last (re-find #"\.(\w+)$" path)))

(defn extract-language
  "Extract the programming language from file paths or context."
  [files context]
  (let [extensions (->> files
                        (map :path)
                        (keep extract-extension)
                        frequencies)
        most-common (when (seq extensions)
                      (key (apply max-key val extensions)))]
    (or (:language context)
        (get @ext->language most-common most-common))))

;------------------------------------------------------------------------------ Layer 1
;; Prompt formatting

(defn format-repo-map
  "Format a repo map for inclusion in the user prompt."
  [repo-map-text]
  (when (and repo-map-text (not (str/blank? repo-map-text)))
    (str "\n\n## " (messages/t :prompt/repo-map-section-title) "\n\n"
         (messages/t :prompt/repo-map-header)
         "\n\n" repo-map-text)))

(defn- format-file-block
  "Format a single file as a markdown code block."
  [{:keys [path content truncated?]}]
  (str "\n### " path
       (when truncated? " (truncated)")
       "\n```\n" content "\n```"))

(defn format-existing-files
  "Format existing file contents for inclusion in the user prompt."
  [files]
  (when (seq files)
    (str "\n\n## " (messages/t :prompt/existing-files-section-title) "\n\n"
         (messages/t :prompt/existing-files-header) "\n"
         (->> files (map format-file-block) (str/join "\n")))))

;------------------------------------------------------------------------------ Layer 1
;; Task → text conversion

(defn- format-plan-section
  "Format the plan as a markdown section."
  [plan]
  (str "\n\n## " (messages/t :prompt/plan-header) "\n\n"
       (if (string? plan) plan (pr-str plan))))

(defn- format-intent-section
  "Format the intent as a markdown section."
  [intent]
  (str "\n\n## " (messages/t :prompt/intent-header) "\n\n"
       (pr-str intent)))

(defn- format-review-section
  "Format review feedback as a markdown section."
  [review-feedback]
  (str "\n\n## " (messages/t :prompt/review-feedback-header) "\n\n"
       (messages/t :prompt/review-feedback-intro) "\n\n"
       (if (string? review-feedback) review-feedback (pr-str review-feedback))))

(defn- phase-handoff-findings
  [phase-handoff]
  (get-in phase-handoff [:frame/body :repair/findings]))

(defn- format-phase-handoff-detail
  [label-key value]
  (when value
    (let [label (messages/t label-key)]
      (messages/t :prompt/phase-handoff-detail-line
                  {:label label
                   :value value}))))

(defn- finding-location
  [{:finding/keys [file line]}]
  (cond
    (and file line)
    (messages/t :prompt/phase-handoff-location-value {:file file :line line})
    file
    file))

(defn- format-phase-handoff-finding
  [{:finding/keys [summary group-id severity suggestion] :as finding}]
  (let [location (finding-location finding)
        detail-lines (keep identity
                           [(format-phase-handoff-detail
                             :prompt/phase-handoff-group-label group-id)
                            (format-phase-handoff-detail
                             :prompt/phase-handoff-severity-label severity)
                            (format-phase-handoff-detail
                             :prompt/phase-handoff-location-label location)
                            (format-phase-handoff-detail
                             :prompt/phase-handoff-suggestion-label suggestion)])
        summary-line (messages/t :prompt/phase-handoff-finding-line
                                 {:summary summary})]
    (str summary-line
         (when (seq detail-lines)
           (str "\n" (str/join "\n" detail-lines))))))

(defn- format-phase-handoff-findings
  [findings]
  (if (seq findings)
    (str/join "\n" (map format-phase-handoff-finding findings))
    (messages/t :prompt/phase-handoff-no-findings)))

(defn- format-phase-handoff-section
  "Format a structured phase handoff as a prominent repair section."
  [phase-handoff]
  (str "\n\n## " (messages/t :prompt/phase-handoff-header) "\n\n"
       (messages/t :prompt/phase-handoff-intro) "\n\n"
       (format-phase-handoff-findings (phase-handoff-findings phase-handoff))))

(defn- format-verify-section
  "Format verify failures as a markdown section."
  [verify-failures]
  (str "\n\n## " (messages/t :prompt/test-failures-header) "\n\n"
       (messages/t :prompt/test-failures-intro) "\n\n"
       (if-let [test-results (:test-results verify-failures)]
         (str (messages/t :prompt/test-results-label) "\n" (pr-str test-results))
         (str (messages/t :prompt/verify-details-label) "\n" (pr-str verify-failures)))
       (when-let [test-output (:test-output verify-failures)]
         (str "\n\n" (messages/t :prompt/test-output-label) "\n" test-output))))

(defn- format-prior-attempts-section
  "Format prior attempt context as a prominent warning section."
  [{:keys [attempt-number prior-error instruction]}]
  (str "\n\n## ⚠️ RETRY — Attempt " attempt-number "\n\n"
       "**Previous attempt failed:** " prior-error "\n\n"
       "**" instruction "**\n"))

(defn task->text
  "Convert a task to text for the LLM.
   Includes plan and intent when available for richer context."
  [task]
  (cond
    (string? task) task
    (map? task) (let [desc (or (:task/description task) (:description task) (:content task))
                      plan (:task/plan task)
                      intent (:task/intent task)
                      phase-handoff (:task/phase-handoff task)
                      review-feedback (:task/review-feedback task)
                      verify-failures (:task/verify-failures task)
                      prior-attempts (:task/prior-attempts task)
                      parts (cond-> []
                              prior-attempts
                              (conj (format-prior-attempts-section prior-attempts))
                              phase-handoff
                              (conj (format-phase-handoff-section phase-handoff))
                              desc
                              (conj desc)
                              plan
                              (conj (format-plan-section plan))
                              (and intent (map? intent))
                              (conj (format-intent-section intent))
                              review-feedback
                              (conj (format-review-section review-feedback))
                              verify-failures
                              (conj (format-verify-section verify-failures)))]
                  (if (seq parts)
                    (str/join "" parts)
                    (pr-str task)))
    :else (str task)))

;------------------------------------------------------------------------------ Layer 1
;; Response parsing

(defn- try-parse-edn-block
  "Try to extract an EDN map from a fenced code block."
  [text]
  (when-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" text)]
    (let [parsed (edn/read-string (second match))]
      (when (map? parsed) parsed))))

(defn- try-parse-raw-edn
  "Try to parse the entire text as EDN, returning a map or nil."
  [text]
  (try
    (let [r (edn/read-string text)]
      (when (map? r) r))
    (catch Exception _ nil)))

(defn- try-parse-inline-status
  "Scan for an inline {:status :already-implemented ...} EDN map."
  [text]
  (when-let [match (re-find #"\{:status\s+:already-implemented[^}]*\}" text)]
    (edn/read-string match)))

(defn parse-code-response
  "Parse the LLM response to extract code artifact.
   Handles EDN in code blocks, plain EDN, and inline EDN maps.
   Returns nil if no parseable map is found."
  [response-content]
  (try
    (when-let [parsed (or (try-parse-edn-block response-content)
                          (try-parse-raw-edn response-content)
                          (try-parse-inline-status response-content))]
      (when (map? parsed) parsed))
    (catch Exception _
      nil)))

(defn- extract-path-from-context
  "Try to extract a file path from text immediately preceding a code block.
   Looks for common patterns like '### path/to/file.clj' or '**path/to/file.clj**'
   or 'File: path/to/file.clj' in the preceding lines."
  [text block-start]
  (let [preceding (subs text 0 block-start)
        ;; Take last 3 lines before the code block
        lines (take-last 3 (str/split-lines preceding))]
    (some (fn [line]
            (or
             (second (re-find patterns/md-heading-file-path line))
             (second (re-find patterns/md-delimited-file-path line))
             (second (re-find patterns/md-label-file-path line))))
          lines)))

(defn extract-code-blocks
  "Extract code blocks from markdown response and convert to file list.
   Tries to detect file paths from markdown headings before each block.

   Pathless blocks are not repo artifacts. Treating them as generated
   filenames lets narrative snippets pass implement, verify, and review
   without producing a releasable diff."
  [response-content]
  (let [pattern patterns/md-code-block
        matcher (re-matcher pattern response-content)]
    (loop [results [] idx 0]
      (if (.find matcher)
        (let [content (.group matcher 2)
              block-start (.start matcher)
              path (extract-path-from-context response-content block-start)]
          (if path
            (recur (conj results {:path path
                                  :content (str/trim content)
                                  :action :create})
                   (inc idx))
            nil))
        (when (seq results) results)))))

;------------------------------------------------------------------------------ Layer 1
;; Artifact repair

(defn- ensure-required-fields
  "Ensure a file entry has content and action. Drops entries with no path."
  [f]
  (when (:path f)
    (cond-> f
      (nil? (:content f)) (assoc :content "")
      (not (:action f))   (assoc :action :create))))

(defn- fill-empty-creates
  "Add placeholder content to empty :create files."
  [f]
  (if (and (= :create (:action f)) (empty? (:content f)))
    (assoc f :content ";; TODO: Implement\n")
    f))

(defn- deduplicate-files
  "Deduplicate file entries by path, keeping last occurrence."
  [files]
  (let [seen (atom #{})
        deduped (reduce (fn [acc f]
                          (if (contains? @seen (:path f))
                            acc
                            (do (swap! seen conj (:path f))
                                (conj acc f))))
                        []
                        (reverse files))]
    (vec (reverse deduped))))

(defn repair-code-artifact
  "Attempt to repair a code artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (-> artifact
                     (update :code/id #(or % (random-uuid)))
                     (update :code/files #(or % []))
                     (update :code/files #(filterv some? (map ensure-required-fields %)))
                     (update :code/files #(mapv fill-empty-creates %))
                     (update :code/files deduplicate-files))]
    {:status :success
     :output repaired
     :repairs-made (when (not= artifact repaired)
                     {:original-errors errors})}))

;------------------------------------------------------------------------------ Layer 2
;; LLM invocation helpers

(defn- build-user-prompt
  "Build the user prompt for the implementer."
  [task-text input]
  (str (messages/t :prompt/implement-task-prefix) "\n\n"
       task-text
       (format-repo-map (:task/repo-map input))
       (format-existing-files (:task/existing-files input))
       "\n\n" (messages/t :prompt/output-instruction)
       "\n\n" (messages/t :prompt/already-impl-instruction) "\n"
       (messages/t :prompt/already-impl-template)))

(defn- build-effective-system-prompt
  "Append behavior addendum to the base system prompt."
  [input]
  (str @implementer-system-prompt
       (get input :task/behavior-addendum "")))

(defn- build-already-implemented-response
  "Build the response map for an already-implemented task."
  [parsed tokens cost-usd]
  {:status :already-implemented
   :output {:code/id (random-uuid)
            :code/files []
            :code/summary (:summary parsed)
            :code/language nil
            :code/tests-needed? false
            :code/created-at (java.util.Date.)}
   :summary (:summary parsed)
   :metrics {:tokens tokens
             :cost-usd cost-usd
             :files-created 0
             :skipped-reason :already-implemented}})

(defn- repair-attempt?
  "Return true when the implementer is handling prior review or verify feedback."
  [input]
  (or (:task/review-feedback input)
      (:task/verify-failures input)))

(defn- unverified-already-implemented-response
  "Reject an :already-implemented claim when there is no artifact evidence on a repair attempt."
  [input tokens]
  (response/error
   (messages/t :error/unverified-already-implemented)
   {:tokens tokens
    :data {:code :implementer/unverified-already-implemented
           :review-feedback? (boolean (:task/review-feedback input))
           :verify-failures? (boolean (:task/verify-failures input))}}))

(defn- count-files-by-action
  "Count files matching a given action in an artifact."
  [action files]
  (count (filter #(= action (:action %)) files)))

(defn- build-code-response
  "Build the success response for a parsed code artifact."
  [code context tokens cost-usd]
  (let [code-with-meta (-> code
                           (update :code/id #(or % (random-uuid)))
                           (assoc :code/language (extract-language (:code/files code) context))
                           (assoc :code/created-at (java.util.Date.)))
        files (:code/files code-with-meta)]
    (response/success code-with-meta
                      {:tokens tokens
                       :metrics {:files-created (count-files-by-action :create files)
                                 :files-modified (count-files-by-action :modify files)
                                 :files-deleted (count-files-by-action :delete files)
                                 :language (:code/language code-with-meta)
                                 :tokens tokens
                                 :cost-usd cost-usd}})))

(defn- code-artifact?
  "True when the structured artifact is a code artifact payload."
  [artifact]
  (and (map? artifact)
       (contains? artifact :code/files)))

(defn- metadata-only-submit?
  "True when MCP submit produced metadata but no file payload."
  [artifact-source structured-artifact]
  (and (= :mcp artifact-source)
       (map? structured-artifact)
       (not (code-artifact? structured-artifact))))

(defn- code-from-blocks
  "Build a code artifact map from extracted markdown code blocks."
  [content]
  (when-let [files (extract-code-blocks content)]
    {:code/id (random-uuid)
     :code/files files
     :code/tests-needed? true
     :code/summary "Implementation from code blocks"
     :code/created-at (java.util.Date.)}))

(defn- log-implementer-rejection
  "Emit a structured :implementer/response-rejected event when the
   implementer can't build a success response from the LLM turn.
   Surfaces WHY each iteration failed so the next dogfood reads the
   reject pattern (parse-failed, unverified-already-implemented,
   etc.) inline instead of forcing a deep-dive through the trace.

   Two reject paths today; both wrap response/error. The reject
   reason is a keyword the caller picks before invoking — keeps
   the event taxonomy stable as new reject paths get added.

   `input` is read for :repair-attempt? — that's the strongest
   signal for distinguishing 'agent stalled mid-narration' from
   'agent retried after rejection and still didn't ship.'"
  [logger reason input artifact-source content tools]
  (when logger
    (log/warn logger :implementer :implementer/response-rejected
              {:data {:reject/reason reason
                      :artifact-source artifact-source
                      :tools-called tools
                      :content-length (count (or content ""))
                      :repair-attempt? (boolean (repair-attempt? input))}})))

(defn- process-llm-response
  "Normalize structured implementer outputs from any submission channel:
   worktree metadata, MCP artifact, file fallback, or parseable stdout.

   Emits a structured :implementer/response-rejected event with
   `:reject/reason` on both reject paths — when no channel yields a
   usable artifact (:parse-failed) AND when a repair attempt's
   `:already-implemented` claim lacks artifact evidence
   (:unverified-already-implemented). Surfaces WHY each iteration
   failed so the next dogfood reads the reject pattern inline
   instead of forcing a deep-dive trace read."
  [{:keys [content structured-artifact parsed-content derived-artifact
           artifact-source tokens cost-usd tools-called]}
   context logger input]
  (let [parsed (or structured-artifact parsed-content)
        tools tools-called
        metadata-only? (metadata-only-submit? artifact-source structured-artifact)]
    (when (nil? artifact-source)
      (log/warn logger :implementer :implementer/mcp-tool-not-called
                {:data {:content-length (count (or content ""))
                        :tools-called tools
                        :has-code-blocks? (boolean (re-find #"```" (or content "")))}}))
    (cond
      (code-artifact? structured-artifact)
      (build-code-response structured-artifact context tokens cost-usd)

      (= :already-implemented (:status parsed))
      (if (repair-attempt? input)
        (do (log-implementer-rejection
             logger :unverified-already-implemented
             input artifact-source content tools)
            (unverified-already-implemented-response input tokens))
        (build-already-implemented-response parsed tokens cost-usd))

      metadata-only?
      (do
        (log-implementer-rejection
         logger :metadata-only-submit
         input artifact-source content tools)
        (response/error (messages/t :error/parse-failed)
                        {:tokens tokens
                         :data {:reject/reason :metadata-only-submit
                                :artifact-source artifact-source}}))

      :else
      ;; Prefer derived-artifact (code blocks extracted from text) over a
      ;; non-code parsed map. An agent that narrates "I created X" but never
      ;; calls Write produces a summary EDN with no :code/files key; treating
      ;; that as a code artifact yields a success with 0 files and silently
      ;; discards the work. Only fall back to parsed when it IS a code artifact.
      (let [code (or derived-artifact
                     (when (code-artifact? parsed) parsed))]
        (if code
          (build-code-response code context tokens cost-usd)
          (let [reject-reason (if (and (some? parsed) (not (code-artifact? parsed)))
                                :narrative-only-response
                                :parse-failed)]
            (log-implementer-rejection
             logger reject-reason
             input artifact-source content tools)
            (response/error (messages/t :error/parse-failed)
                            {:tokens tokens
                             :data   (cond-> {:reject/reason reject-reason}
                                       artifact-source
                                       (assoc :artifact-source artifact-source))})))))))

(def ^:private session-checkpoint-filename ".miniforge/session-id")

(defn- read-session-checkpoint
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

(defn- write-session-checkpoint! [working-dir session-id]
  (when (and working-dir session-id)
    (let [f (io/file working-dir session-checkpoint-filename)]
      (io/make-parents f)
      (spit f session-id))))

(defn- invoke-implementer-session
  "Session body for the implementer: cache files, build mcp-opts, call LLM."
  [session llm-client user-prompt effective-system-prompt config context on-chunk
   existing-files working-dir]
  (when (seq existing-files)
    (let [files-map (into {} (map (fn [f] [(:path f) (:content f)])
                                  existing-files))]
      (artifact-session/write-context-cache-for-session! session files-map)))
  (let [budget-usd (budget/resolve-cost-budget-usd :implementer config context)
        max-turns (get @implementer-prompt-data :prompt/max-turns 10)
        resume-id (read-session-checkpoint working-dir)
        mcp-opts (cond-> (artifact-session/session->mcp-opts session budget-usd max-turns)
                   working-dir (assoc :workdir working-dir)
                   resume-id   (assoc :resume resume-id))
        result (if on-chunk
                 (llm/chat-stream llm-client user-prompt on-chunk
                                  (merge {:system effective-system-prompt} mcp-opts))
                 (llm/chat llm-client user-prompt
                           (merge {:system effective-system-prompt} mcp-opts)))]
    (when-let [new-session-id (:session-id result)]
      (write-session-checkpoint! working-dir new-session-id))
    result))

(defn- base-ref-candidates
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

(defn- collect-promoted-artifact
  "Collect code files from the current promoted worktree/container state."
  [context session-mode working-dir]
  (let [base-refs (base-ref-candidates context)]
    (if (= :capsule session-mode)
      (when-let [exec! (or (:execution/execute-fn context)
                           @(requiring-resolve 'ai.miniforge.dag-executor.executor/execute!))]
        (file-artifacts/collect-worktree-files-via-executor
         exec!
         (:execution/executor context)
         (:execution/environment-id context)
         working-dir
         base-refs))
      (file-artifacts/collect-worktree-files working-dir base-refs))))

(defn- collect-session-artifact
  "Collect a file artifact from the promoted or written working tree."
  [context session-mode working-dir pre-session-snapshot]
  (or (collect-promoted-artifact context session-mode working-dir)
      (if (= :capsule session-mode)
        (file-artifacts/collect-written-files-via-executor
         pre-session-snapshot
         @(requiring-resolve 'ai.miniforge.dag-executor.executor/execute!)
         (:execution/executor context)
         (:execution/environment-id context)
         working-dir)
        (file-artifacts/collect-written-files pre-session-snapshot
                                              working-dir))))

(defn- submission-retry-prompt
  "Submission-only retry prompt: re-feed the task + the prior (prose) output
   and ask the implementer to deliver the actual file writes now."
  [task-text prior-content]
  (prompts/render-template (get @implementer-prompt-data :prompt/submission-retry-template)
                           {:task-text task-text :prior-content prior-content}))

(defn- normalize-implementer-result
  "Re-normalize a session's raw channels into the implementer result shape,
   re-collecting the file artifact from what was written this turn. Used by the
   submission-recovery turn (the main turn normalizes inline so it can also
   surface the file artifact for its fallback log)."
  [{:keys [llm-result artifact worktree-artifacts pre-session-snapshot session-mode]}
   context working-dir]
  (let [file-artifact (collect-session-artifact context session-mode working-dir
                                                pre-session-snapshot)]
    (result-boundary/normalize-llm-result
     {:role :implement
      :response llm-result
      :worktree-artifacts worktree-artifacts
      :artifact artifact
      :fallback-artifact file-artifact
      :parse-response parse-code-response
      :derive-artifact code-from-blocks})))

(defn- recover-implementer-submission
  "When the main turn produced usable analysis but NO artifact, run one short
   submission-only retry that asks the implementer to write the files now, then
   re-normalize. Returns the recovered normalized map, or nil when recovery
   produced no artifact either. Generalizes PR #997's planner recovery."
  [{:keys [llm-client config context on-chunk working-dir effective-system-prompt
           input normalized logger]}]
  (log/info logger :implementer :implementer/submission-retry
            {:data {:llm-success? (llm/success? (:response normalized))
                    :content-length (count (:content normalized))}})
  (let [raw (submission-recovery/run-recovery-session
             {:context          context
              :llm-client       llm-client
              :on-chunk         on-chunk
              :effective-system effective-system-prompt
              :retry-prompt     (submission-retry-prompt (task->text input)
                                                         (:content normalized))
              :mcp-opts-fn      (fn [session]
                                  (let [budget-usd      (budget/resolve-cost-budget-usd
                                                         :implementer config context)
                                        retry-max-turns (get @implementer-prompt-data
                                                             :prompt/submission-retry-max-turns 6)]
                                    (cond-> (artifact-session/session->mcp-opts
                                             session budget-usd retry-max-turns)
                                      working-dir (assoc :workdir working-dir))))})
        recovered (normalize-implementer-result raw context working-dir)]
    (when (or (:structured-artifact recovered)
              (:parsed-content recovered)
              (:derived-artifact recovered))
      recovered)))

(defn- invoke-with-llm
  "Invoke the implementer via the LLM backend."
  [llm-client user-prompt effective-system-prompt config context on-chunk logger
   existing-files input]
  (let [working-dir (workspace/resolve-execution-workdir context "implement")
        {:keys [llm-result artifact worktree-artifacts context-misses
                pre-session-snapshot session-mode]}
        (artifact-session/with-session context
          #(invoke-implementer-session % llm-client user-prompt effective-system-prompt
                                       config context on-chunk existing-files working-dir))
        response llm-result
        file-artifact (collect-session-artifact context
                                                session-mode
                                                working-dir
                                                pre-session-snapshot)
        normalized (result-boundary/normalize-llm-result
                    {:role :implement
                     :response response
                     :worktree-artifacts worktree-artifacts
                     :artifact artifact
                     :fallback-artifact file-artifact
                     :parse-response parse-code-response
                     :derive-artifact code-from-blocks})]
    (when (seq context-misses)
      (log/info logger :implementer :implementer/context-cache-misses
                {:data {:miss-count (count context-misses)
                        :misses context-misses}}))
    (when file-artifact
      (log/warn logger :implementer :implementer/file-artifact-fallback
                {:data {:file-count (count (:code/files file-artifact))
                        :tools-called (get response :tools-called [])}}))
    (log/info logger :implementer :implementer/llm-called
              {:data (cond-> {:success (llm/success? response)
                              :tokens (:tokens normalized)
                              :streaming? (boolean on-chunk)
                              :artifact-source (or (:artifact-source normalized) :none)
                              ;; Dir the agent wrote to / collection scanned —
                              ;; surfaces the worktree-path-vs-CWD resolution so
                              ;; a write/scan dir mismatch is diagnosable.
                              :working-dir working-dir
                              :tools-called (:tools-called normalized)}
                       (:stop-reason response)
                       (assoc :stop-reason (:stop-reason response))
                       (:num-turns response)
                       (assoc :num-turns (:num-turns response)))})
    ;; Container-promotion precedence: if the agent wrote files into
    ;; the worktree (file-artifact via collect-written-files) or
    ;; submitted via the MCP artifact path, honor that even when the
    ;; CLI itself terminated with a timeout/error. Iter-20 of the
    ;; planner-convergence dogfood showed Claude stalling AFTER a
    ;; successful stream of edits; the old path discarded a real
    ;; artifact because the LLM response was classified as failure.
    ;; Mirrors the planner fix in `(or worktree-plan (llm/success? …))`.
    ;; Submission recovery: if the turn produced usable analysis but NO
    ;; artifact (no files, no MCP submit, no parseable code) — the same
    ;; intermittent failure PR #997 fixed for the planner — run ONE short
    ;; submission-only retry that asks the implementer to write the files now.
    ;; Without this, a prose-only implement turn fails "no artifact found",
    ;; which is what stalled redirect-implements in the review-redirect churn.
    (let [submitted (:structured-artifact normalized)
          ;; Fold the derived artifact (code extracted from final-message
          ;; fences) into the "artifact already exists" guard so a turn that
          ;; produced usable code blocks does NOT trigger an unnecessary
          ;; recovery LLM call.
          parsed    (or (:parsed-content normalized) (:derived-artifact normalized))
          recovered (when (submission-recovery/submission-retry?
                           response submitted parsed (:content normalized))
                      (recover-implementer-submission
                       {:llm-client llm-client :config config :context context
                        :on-chunk on-chunk :working-dir working-dir
                        :effective-system-prompt effective-system-prompt
                        :input input :normalized normalized :logger logger}))
          final     (or recovered normalized)]
      ;; Container-promotion precedence: if the agent wrote files into
      ;; the worktree (file-artifact via collect-written-files) or
      ;; submitted via the MCP artifact path, honor that even when the
      ;; CLI itself terminated with a timeout/error. Iter-20 of the
      ;; planner-convergence dogfood showed Claude stalling AFTER a
      ;; successful stream of edits; the old path discarded a real
      ;; artifact because the LLM response was classified as failure.
      (if (result-boundary/usable-content? final)
        (process-llm-response final context logger input)
        ;; LLM call failed, no artifact (even after recovery) — preserve the
        ;; full llm-error shape into :data so the phase-completed event carries
        ;; :type / :stderr / :stdout / :timeout / :exit-code for post-mortem.
        (result-boundary/error-response final
                                        (messages/t :error/llm-failed))))))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-implementer
  "Create an Implementer agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        config (->> (merge (role-config/agent-llm-default :implementer)
                           (:config opts))
                    (model/apply-default-model :implementer)
                    (budget/apply-default-budget :implementer))]
    (specialized/create-base-agent
     {:role :implementer
      :system-prompt @implementer-system-prompt
      :config config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :implementer
                          (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              task-text (task->text input)
              effective-system-prompt (build-effective-system-prompt input)
              user-prompt (build-user-prompt task-text input)]
          (if llm-client
            (invoke-with-llm llm-client user-prompt effective-system-prompt
                             config context on-chunk logger
                             (:task/existing-files input)
                             input)
            (response/error (messages/t :error/no-llm-backend)))))

      :validate-fn validate-code-artifact

      :repair-fn repair-code-artifact})))

(defn code-summary
  [artifact]
  {:id (:code/id artifact)
   :file-count (count (:code/files artifact))
   :actions (frequencies (map :action (:code/files artifact)))
   :language (:code/language artifact)
   :tests-needed? (:code/tests-needed? artifact)
   :dependencies-added (count (:code/dependencies-added artifact []))})

(defn files-by-action
  [artifact]
  (group-by :action (:code/files artifact)))

(defn total-lines
  [artifact]
  (->> (:code/files artifact)
       (remove #(= :delete (:action %)))
       (map :content)
       (map #(count (str/split-lines %)))
       (reduce + 0)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def impl (create-implementer))

  (specialized/invoke impl
               {:language "clojure"
                :suggested-path "src/ai/miniforge/auth/login.clj"}
               {:task/description "Implement user login with email verification"
                :task/type :implement})

  (validate-code-artifact
   {:code/id (random-uuid)
    :code/files [{:path "src/example.clj"
                  :content "(ns example)"
                  :action :create}]})

  (code-summary {:code/id (random-uuid)
                 :code/files [{:path "a.clj" :content "" :action :create}
                              {:path "b.clj" :content "" :action :modify}]
                 :code/language "clojure"})

  :leave-this-here)
