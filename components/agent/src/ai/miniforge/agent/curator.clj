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
    multi-retry failure with a single clear error.

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
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; System prompt (lazy — only loaded when LLM enrichment is invoked)

(def curator-system-prompt
  "System prompt for the curator agent. Loaded from resources/prompts/curator.edn."
  (delay
    (try
      (prompts/load-prompt :curator)
      (catch Exception _
        ;; Prompt resource absent → deterministic-only mode.
        nil))))

;------------------------------------------------------------------------------ Layer 1
;; Deterministic helpers

(defn- test-file?
  "Heuristic: does the path look like a test file?"
  [path]
  (boolean
   (or (re-find #"(^|/)test/" path)
       (re-find #"_test\.[A-Za-z0-9]+$" path)
       (re-find #"\.test\.[A-Za-z0-9]+$" path)
       (re-find #"(^|/)spec/" path)
       (re-find #"_spec\.[A-Za-z0-9]+$" path))))

(defn- detect-tests-added
  "True when any file in the diff is a test file."
  [files]
  (boolean (some (comp test-file? :path) files)))

(defn- detect-scope-deviations
  "Return paths in `files` that are not in `intent-scope`.
   When `intent-scope` is nil/empty, returns []."
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
  "Infer the primary language from the most common file extension."
  [files]
  (let [extensions (->> files
                        (keep #(second (re-find #"\.([A-Za-z0-9]+)$" (:path % ""))))
                        frequencies)]
    (when (seq extensions)
      (key (apply max-key val extensions)))))

(defn- deterministic-summary
  "Produce a summary string from file counts and actions."
  [files]
  (let [n (count files)
        actions (frequencies (map :action files))
        by-action #(get actions % 0)]
    (format "%d file%s changed (%d created, %d modified, %d deleted)"
            n
            (if (= 1 n) "" "s")
            (by-action :create)
            (by-action :modify)
            (by-action :delete))))

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
   1. implementer-result has :output :code/files already (fast path)
   2. fresh collection via executor (capsule mode)
   3. fresh collection via local git status (non-capsule)

   Returns a vector (possibly empty) of file entries:
     [{:path string :content string :action :create|:modify|:delete} ...]"
  [input]
  (vec (or (files-from-result (:implementer-result input))
           (files-via-executor input)
           (files-via-local-snapshot input)
           [])))

;------------------------------------------------------------------------------ Layer 2
;; Optional LLM enrichment

(defn- build-llm-prompt
  "Build the curator user prompt from the diff + spec context."
  [files intent-scope spec-description]
  (str "The implementer agent just wrote files to a working directory. "
       "Produce a structured curator report.\n\n"
       "## Spec description\n\n"
       (or spec-description "(not provided)") "\n\n"
       "## Declared scope\n\n"
       (if (seq intent-scope)
         (str/join "\n" (map #(str "- " %) intent-scope))
         "(not specified)")
       "\n\n## Files changed (" (count files) ")\n\n"
       (str/join "\n"
                 (for [f (take 50 files)]
                   (str "- [" (name (:action f :modify)) "] " (:path f)
                        " (" (count (:content f "")) " chars)")))
       (when (> (count files) 50)
         (str "\n... (" (- (count files) 50) " more files omitted) ..."))
       "\n\n## Output\n\n"
       "Return a Clojure EDN map with these keys:\n"
       "  :summary           1-2 sentence natural-language description of what changed\n"
       "  :breaking-change?  boolean — is this likely a breaking API change?\n"
       "  :rationale         1 short paragraph on whether the changes match the spec\n\n"
       "Example:\n"
       "  {:summary \"Added retry-budget state machine; diagnoses before retry.\"\n"
       "   :breaking-change? false\n"
       "   :rationale \"Implements M1 of the Superpowers plan; no public API changes.\"}\n"))

(defn- parse-llm-response
  "Extract the curator's EDN report from a free-form LLM response.
   Returns nil on parse failure — caller falls back to deterministic values."
  [content]
  (when (string? content)
    (try
      (let [trimmed (str/trim content)
            candidate (or (when-let [m (re-find
                                        #"(?s)\{[^{}]*:summary[^{}]*\}"
                                        trimmed)]
                            m)
                          trimmed)
            parsed (edn/read-string candidate)]
        (when (map? parsed) parsed))
      (catch Exception _ nil))))

(defn- enrich-via-llm
  "Call the LLM backend for a curator report. Returns a map of enrichment
   fields on success, nil on any failure (deterministic path handles fallback).

   Accepts an already-resolved llm-client to keep this function
   dependency-light and easy to test."
  [llm-client logger files intent-scope spec-description]
  (when (and llm-client (seq files))
    (try
      (let [user-prompt (build-llm-prompt files intent-scope spec-description)
            sys-prompt (or @curator-system-prompt
                           "You are a code curator. Produce concise structured reports.")
            result (llm/chat llm-client user-prompt
                             {:system sys-prompt
                              :temperature 0.1
                              :max-tokens 800})]
        (when (llm/success? result)
          (when-let [parsed (parse-llm-response (llm/get-content result))]
            {:llm/summary (:summary parsed)
             :llm/breaking? (boolean (:breaking-change? parsed))
             :llm/rationale (:rationale parsed)})))
      (catch Exception e
        (when logger
          (log/warn logger :curator :curator/llm-enrichment-failed
                    {:data {:error (ex-message e)}}))
        nil))))

;------------------------------------------------------------------------------ Layer 3
;; Public API

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
  [{:keys [intent-scope spec-description llm-client logger] :as input}]
  (let [files (collect-files input)]
    (if (empty? files)
      (response/error
       "Curator: implementer wrote no files to the environment"
       {:data {:worktree-path (:worktree-path input)
               :env-id (:env-id input)
               :intent-scope intent-scope}})
      (let [tests-added? (detect-tests-added files)
            deviations (detect-scope-deviations files intent-scope)
            language (detect-language files)
            enrichment (enrich-via-llm llm-client logger files
                                       intent-scope spec-description)
            source (if enrichment :hybrid :deterministic)
            summary (or (not-empty (:llm/summary enrichment))
                        (deterministic-summary files))
            artifact {:code/id (random-uuid)
                      :code/files files
                      :code/summary summary
                      :code/tests-added? tests-added?
                      :code/scope-deviations deviations
                      :code/breaking-change? (boolean (:llm/breaking? enrichment))
                      :code/rationale (:llm/rationale enrichment)
                      :code/language language
                      :code/curated-at (java.util.Date.)
                      :code/curator-source source}]
        (response/success
         artifact
         {:metrics {:files-total (count files)
                    :files-created (count (filter #(= :create (:action %)) files))
                    :files-modified (count (filter #(= :modify (:action %)) files))
                    :files-deleted (count (filter #(= :delete (:action %)) files))
                    :scope-deviations (count deviations)
                    :tests-added? tests-added?
                    :curator-source source}})))))

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
