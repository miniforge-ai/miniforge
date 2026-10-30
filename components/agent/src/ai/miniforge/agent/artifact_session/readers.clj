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
(ns ai.miniforge.agent.artifact-session.readers
  "Reading what an artifact session produced: the MCP artifact, worktree
   role artifacts, context misses and reads, and recorded peg answers, on
   the host and inside a capsule."
  (:require
   [ai.miniforge.agent.artifact-session.parsing :as parsing]
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} collect-worktree-artifacts
  "Build the `:worktree-artifacts` map by probing every role file under
   `workdir`/.miniforge/. Returns nil when `workdir` is absent so callers
   can distinguish 'no worktree was threaded through' from 'worktree had
   no role files'."
  [read-role-artifact workdir]
  (when workdir
    (into {} (keep #(parsing/read-role-entry read-role-artifact %)) parsing/worktree-roles)))

(defn ^{:stratum 0} read-capsule-artifact
  "Read artifact EDN from inside the capsule via executor.
   Applies parse-uuid-strings to match host read-artifact behavior."
  [session]
  (let [exec!    (:exec! session)
        result   (exec! (:executor session) (:environment-id session)
                        (str "cat " (file-artifacts/shell-quote (:artifact-path session))) {:workdir (:workdir session)})
        content  (get-in result [:data :stdout] "")]
    (when (seq content)
      (try
        (-> content
            clojure.edn/read-string
            parsing/parse-uuid-strings)
        (catch Exception _ nil)))))

(defn ^{:stratum 0} any-worktree-files-exist?
  "Check if any .miniforge/<role>.edn file exists under workdir without parsing.

   Used in host-mode sessions to distinguish 'truly no files' from 'files existed
   but all failed to parse'. A parse failure emits :warn/worktree-artifact-parse and
   returns nil from read-role-artifact, which causes collect-worktree-artifacts to
   yield an empty map indistinguishable from the no-files case. This function
   inspects the filesystem directly so run-session can suppress the redundant
   :warn/no-artifact-found message when files were present but malformed.

   Not used in capsule mode — the executor would require an additional exec call
   per role to check existence, which is deferred to a future iteration."
  [workdir]
  (boolean
   (when workdir
     (some #(.exists (parsing/worktree-artifact-file workdir %))
           parsing/worktree-roles))))

(defn ^{:stratum 0} emit-no-artifact-warning!
  "Emit `:warn/no-artifact-found` after an explicit worktree scan confirms
   both the MCP path and the worktree role files came up empty. Separate fn
   so `run-session` reads as a sequence of named steps and the diagnostic
   stays testable.

   AUDIT NOTE (2026-05-21): An audit of bases/cli/src, components/workflow/src,
   and components/phase-software-factory/src confirmed that no code re-renders
   this WARN at ERROR level in terminal output. The message is emitted to stderr
   via `emit-system-message!` at WARN severity and is passed through as-is by
   the CLI rendering pipeline (workflow_runner/display.clj applies no
   artifact-specific coloring or severity amplification). The plan phase
   delegates artifact handling entirely to `agent/invoke` → `with-session` →
   `run-session`; there is no redundant artifact-presence check at the phase
   layer.

   However, because this message goes to stderr it may be perceived as a
   terminal-level error by users even though it is diagnostic WARN output.
   If the cause is not actionable by the user (e.g. the agent simply used the
   Write-based worktree submission path instead of the MCP submit_artifact
   tool), callers SHOULD suppress this warning — see the `any-file-existed?`
   and `(seq worktree-artifacts)` guards in `run-session` for the suppression
   logic already in place."
  [session workdir]
  (parsing/emit-system-message! :warn/no-artifact-found
                        (:artifact-path session)
                        (str (or workdir "<no-workdir>") "/.miniforge/")
                        (str/join ", " (map name parsing/worktree-roles))))

(defn- ^{:stratum 0} parse-edn-file
  "Read and parse an EDN file with a transform, logging failures consistently."
  [f transform warning-key]
  (parsing/parse-edn-content (slurp f) transform warning-key (.getPath f)))

(defn ^{:stratum 0} read-capsule-worktree-artifact
  "Read a role artifact from <workdir>/.miniforge/<role>.edn inside a capsule.

  Returns the parsed artifact map or nil. Missing files and parse failures are
   treated as non-fatal, matching host-mode read-worktree-artifact behavior."
  [session role]
  (when (and session role)
    (let [path (.getPath (parsing/worktree-artifact-file (:workdir session) role))
          result ((:exec! session) (:executor session) (:environment-id session)
                  (str "cat " (file-artifacts/shell-quote path)) {:workdir (:workdir session)})
          content (get-in result [:data :stdout] "")]
      (when (seq content)
        (parsing/parse-edn-content content
                           (comp parsing/parse-uuid-strings edn/read-string)
                           :warn/capsule-worktree-artifact-parse
                           path)))))

(defn ^{:stratum 0} read-capsule-session-outputs
  "Read artifact.edn, context-misses.edn, context-reads.edn and
   answers.edn from a capsule session in a SINGLE executor round-trip.

   The four files are concatenated by one `cat` chain with
   `capsule-output-boundary` sentinel lines between them, then split back
   apart here. This is how capsule sessions surface `:context-misses` and
   `:context-reads` (Codex SPEC §7.4.2 recorded flows) without adding
   per-file executor round-trips — the batched read costs the same one
   exec call the artifact read already paid.

   Missing files contribute empty segments (`cat` failures are sent to
   /dev/null and the `;` chain continues), which parse to nil. Malformed
   segments emit the same parse WARNs as the host-mode readers.

   The artifact segment is deliberately LAST in the chain: its content is
   agent-authored and may contain arbitrary strings, and the bounded
   split keeps an embedded boundary occurrence inside that final segment
   rather than corrupting the earlier server-written segments — see
   `capsule-output-boundary` for the collision assumption.

   Returns: {:artifact <map-or-nil>
             :context-misses <vector-or-nil>
             :context-reads <vector-or-nil>
             :codex-answers <vector-or-nil>
             :codex-answer-log :absent|:recorded|:unreadable}"
  [session]
  (let [dir          (:dir session)
        misses-path  (str dir "/context-misses.edn")
        reads-path   (str dir "/context-reads.edn")
        answers-path (str dir "/answers.edn")
        sep          (str "; echo; echo " parsing/capsule-output-boundary "; ")
        cmd          (str "cat " (file-artifacts/shell-quote misses-path) " 2>/dev/null"
                          sep
                          "cat " (file-artifacts/shell-quote reads-path) " 2>/dev/null"
                          sep
                          ;; Presence marker before the cat: a
                          ;; present-but-empty answers.edn (write torn
                          ;; before any bytes landed) must read as
                          ;; :unreadable, not :absent — cat output alone
                          ;; cannot tell the two apart.
                          "test -f " (file-artifacts/shell-quote answers-path)
                          " && echo " parsing/capsule-answers-present-marker
                          "; cat " (file-artifacts/shell-quote answers-path) " 2>/dev/null"
                          sep
                          "cat " (file-artifacts/shell-quote (:artifact-path session)) " 2>/dev/null")
        result      ((:exec! session) (:executor session) (:environment-id session)
                     cmd {:workdir (:workdir session)})
        stdout      (get-in result [:data :stdout] "")
        ;; Full-line anchor: the echoed sentinel always stands alone on its
        ;; own line, so a boundary substring embedded mid-line in EDN
        ;; content never splits a segment.
        boundary-re (re-pattern (str "(?m)^"
                                     (java.util.regex.Pattern/quote parsing/capsule-output-boundary)
                                     "$"))
        [misses-part reads-part answers-part artifact-part]
        (mapv str/trim (str/split stdout boundary-re 4))
        {answers-present? :present? answers-content :content}
        (parsing/split-answers-segment answers-part)
        codex-answers (when (seq answers-content)
                        (parsing/parse-edn-content answers-content edn/read-string
                                           :warn/codex-answers-parse answers-path))]
    {:artifact       (when (seq artifact-part)
                       (parsing/parse-edn-content artifact-part
                                          (comp parsing/parse-uuid-strings edn/read-string)
                                          :warn/artifact-parse
                                          (:artifact-path session)))
     :context-misses (when (seq misses-part)
                       (parsing/parse-edn-content misses-part edn/read-string
                                          :warn/context-misses-parse misses-path))
     :context-reads  (when (seq reads-part)
                       (parsing/parse-edn-content reads-part edn/read-string
                                          :warn/context-reads-parse reads-path))
     :codex-answers  codex-answers
     ;; §7.7.2 lost-vs-unanswered: no file is a server that recorded no
     ;; answers; a present file that is empty or fails to parse is a
     ;; torn log.
     :codex-answer-log (parsing/answer-log-state answers-present? codex-answers)}))

(defn ^{:stratum 0} read-capsule-codex-answers
  "Read only answers.edn from a capsule session — the §7.7.2 answer
   channel for read-only agents (the reviewer), whose sessions skip the
   full four-file output read because they promote no artifact. One
   executor round-trip for the one file the session can still have
   produced.

   Returns {:answers <vector-or-nil> :answer-log :absent|:recorded|:unreadable}."
  [session]
  (let [path    (str (:dir session) "/answers.edn")
        ;; Presence marker before the cat — same reasoning as the
        ;; batched reader: a present-but-empty file is a torn log
        ;; (:unreadable), not a missing one (:absent).
        cmd     (str "test -f " (file-artifacts/shell-quote path)
                     " && echo " parsing/capsule-answers-present-marker
                     "; cat " (file-artifacts/shell-quote path) " 2>/dev/null")
        result  ((:exec! session) (:executor session) (:environment-id session)
                 cmd {:workdir (:workdir session)})
        {:keys [present? content]}
        (parsing/split-answers-segment (get-in result [:data :stdout] ""))
        answers (when (seq content)
                  (parsing/parse-edn-content content edn/read-string
                                     :warn/codex-answers-parse path))]
    {:answers    answers
     :answer-log (parsing/answer-log-state present? answers)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-worktree-artifact
  "Read a role artifact from <workdir>/.miniforge/<role>.edn if present.

   Container-promotion pattern: an agent submits its phase artifact by
   writing a file into the worktree, and the runtime reads that file
   after the LLM invocation. This lives alongside the MCP-based
   artifact path (`read-artifact`) — the caller decides precedence.

   Arguments:
   - workdir — absolute path to the worktree (may be nil)
   - role    — keyword or string naming the artifact (e.g. :plan → plan.edn)

   Returns: parsed artifact map with UUID types, or nil (missing,
   unreadable, or parse failure — all logged to stderr, never thrown)."
  [workdir role]
  (when (and workdir role)
    (let [f (parsing/worktree-artifact-file workdir role)]
      (when (.exists f)
        (parse-edn-file f
                        (comp parsing/parse-uuid-strings edn/read-string)
                        :warn/worktree-artifact-parse)))))

(defn ^{:stratum 1} read-artifact
  "Read the artifact EDN file from a session directory.

   The MCP artifact is now an optional submission channel. A missing file
   is not an error on its own — the worktree-promoted path
   (.miniforge/<role>.edn) is the primary channel and is checked separately
   by run-session. Returns nil silently when the file is absent so callers
   (result-boundary/normalize-llm-result) can handle nil gracefully.

   Arguments:
   - session - Session map from create-session!

   Returns:
   - Parsed artifact map with proper UUID types, or nil if not found.
     Parse failures (malformed EDN) are still logged via :warn/artifact-parse."
  [session]
  (let [f (io/file (:artifact-path session))]
    (when (.exists f)
      (parse-edn-file f
                      (comp parsing/parse-uuid-strings edn/read-string)
                      :warn/artifact-parse))))

(defn ^{:stratum 1} read-context-misses
  "Read context cache misses from the session directory.

   The MCP server writes context-misses.edn on exit with records of
   every cache miss (files the agent needed but weren't pre-loaded).

   Returns: vector of miss records, or nil if no misses file."
  [session]
  (let [path (str (:dir session) "/context-misses.edn")
        f (io/file path)]
    (when (.exists f)
      (parse-edn-file f edn/read-string :warn/context-misses-parse))))

(defn ^{:stratum 1} read-context-reads
  "Read recorded context_read resolutions from the session directory.

   The MCP server writes context-reads.edn on exit with a record of every
   context_read the agent made, hit or miss — the recorded flows that make
   'did this agent consult its pinned codex landings' an answerable
   question (Codex SPEC §7.4.2).

   Reads the host-local session directory, same as `read-context-misses`.
   Capsule sessions surface the same file through
   `read-capsule-session-outputs`, which batches it into the artifact
   read's single executor round-trip.

   Returns: vector of read records, or nil if no reads file."
  [session]
  (let [path (str (:dir session) "/context-reads.edn")
        f (io/file path)]
    (when (.exists f)
      (parse-edn-file f edn/read-string :warn/context-reads-parse))))

(defn ^{:stratum 1} read-codex-answers
  "Read recorded answer_peg calls from the session directory — the
   explicit half of the Codex §7.7 answer channel (SPEC §7.7.2). The MCP
   server writes answers.edn on exit; no answers means no file.

   Returns {:answers <vector-or-nil> :answer-log :absent|:recorded|:unreadable}
   — the marker keeps a torn log (observations LOST) distinct from a
   session that answered nothing. Capsule sessions surface the same
   channel through `read-capsule-session-outputs` /
   `read-capsule-codex-answers`."
  [session]
  (let [path (str (:dir session) "/answers.edn")
        f (io/file path)
        exists? (.exists f)
        answers (when exists?
                  (parse-edn-file f edn/read-string :warn/codex-answers-parse))]
    {:answers    answers
     :answer-log (parsing/answer-log-state exists? answers)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} role-reader-for-mode
  "Return a 1-arg fn from role keyword to parsed worktree artifact, picked
   by execution mode. Capsule mode reads through the executor; host mode
   reads directly from `workdir`; an unknown mode yields a no-op reader."
  [mode session workdir]
  (case mode
    :capsule #(read-capsule-worktree-artifact session %)
    :host    #(read-worktree-artifact workdir %)
    (constantly nil)))
