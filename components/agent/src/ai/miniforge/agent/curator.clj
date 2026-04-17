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
  "Curator agent — converts the implementer's environment writes into a
   structured code artifact.

   Background. In the environment-promotion model, the implementer writes
   files directly into the executor environment (capsule or worktree); the
   environment itself is the artifact. The curator reads the resulting
   file diff and produces the structured metadata that downstream phases
   (verify, review, release, PR-doc generation) expect: a :code/... map
   with summary, file list, scope validation, and test-addition detection.

   Why a separate agent. The implementer's job is to code. Expecting it to
   also emit a structured Clojure-map artifact via an MCP tool was brittle
   (observed: implementer exhausts turn budget exploring the codebase and
   never calls submit_code_artifact, producing no artifact at all). The
   curator runs after the implementer, is specialized for structured
   output, and fast-fails when no files were written — replacing silent
   multi-retry failure with a single clear terminal error.

   Two-layer design:
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
   [clojure.edn :as edn]
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

(defn collect-files
  "Resolve the file diff from available sources, in priority order:
   1. implementer-result has :code/files already (fast path)
   2. fresh collection via executor (capsule mode)
   3. fresh collection via local git status (non-capsule)

   Returns a vector (possibly empty) of file entries."
  [input]
  (vec (or (files-from-result (:implementer-result input))
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

(defn curate-implement-output
  "Take an implementer's result + environment state → structured code artifact.

   Fast-fails when no files were written (replacing the silent-retry failure
   mode where the implementer exhausts turns without producing output).

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
