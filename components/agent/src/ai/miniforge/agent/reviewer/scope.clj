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

(ns ai.miniforge.agent.reviewer.scope
  "Scope resolution and per-file matching for the reviewer agent's
   scope-bound verdict (PR #1037 + #1039).

   The reviewer LLM emits findings across the whole artifact; this
   namespace resolves the task's effective scope from the spec / DAG
   sub-task / dispatch layer and partitions findings into in-scope
   (drives the verdict) and out-of-scope (advisory).

   Scope is REQUIRED. The 2026-06-04 eliminate-requiring-resolve
   dogfood failed 20/20 sub-tasks because the reviewer held narrow
   refactors to the full standards bar across all changed files. A
   missing-scope fallback ('review every file') would silently
   reintroduce that pathology, so a missing scope is a programmer
   error — `effective-review-scope` raises rather than returning nil."
  (:require [ai.miniforge.agent.messages :as msg]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Resolution

(defn- clean-scope-paths
  "Drop blank and non-string entries, trim whitespace, dedupe."
  [paths]
  (->> paths
       (keep #(when (string? %) (not-empty (str/trim %))))
       distinct
       vec
       not-empty))

(defn effective-review-scope
  "Resolve the effective scope vector for a review task.

   Strict priority — uses the most specific signal available and ignores
   broader ones underneath it. Order:

   1. `:task/scope` — set explicitly by the dispatch layer for this task.
   2. `:task/files-in-scope` — a DAG sub-task's per-task scope from the
      planner (`dag-orchestrator/task-sub-input`).
   3. `(:scope task/intent)` — the spec-level scope, when intent is an
      EDN map carrying it.

   Merging across levels would dilute per-task narrowness with the broader
   spec scope, which defeats the purpose. Returns a deduped vector of
   non-blank strings.

   Raises `IllegalArgumentException` when no scope signal is present —
   spec authors must declare `:spec/intent {:scope [...]}` or callers
   must supply per-task scope. The pre-#1039 fallback ('nil → review
   every file') silently reintroduced the 2026-06-04 scope-creep
   pathology, so the contract is required-scope at this boundary."
  [input]
  (let [intent (:task/intent input)
        resolved (or (clean-scope-paths (:task/scope input))
                     (clean-scope-paths (:task/files-in-scope input))
                     (when (map? intent) (clean-scope-paths (:scope intent))))]
    (or resolved
        (throw (IllegalArgumentException. (msg/t :reviewer.scope/missing))))))

;------------------------------------------------------------------------------ Layer 1
;; Per-file matching

(defn- normalize-scope-entry
  "Strip trailing slashes so a scope entry can be matched without producing
   `dir//file` when we prepend the boundary `/` in `entry-matches-file?`.
   The boundary check itself (and therefore the `dir-suffix/x.clj` false-
   positive prevention) lives in `entry-matches-file?`."
  [s]
  (str/replace s #"/+$" ""))

(defn- entry-matches-file?
  "True when `file` equals `entry` (exact file match) or sits under it as
   a child path (`entry + \"/\"` is a prefix of `file`). The trailing `/`
   is the boundary check that prevents `\"components/foo\"` from matching
   `\"components/foobar/x.clj\"`."
  [entry file]
  (let [trimmed (normalize-scope-entry entry)]
    (or (= file trimmed)
        (str/starts-with? file (str trimmed "/")))))

(defn in-scope-issue?
  "True when a `ReviewIssue`'s `:file` falls inside `scope`.

   - `:file` blank → true (file-level concern with no path attribution;
     conservatively kept so the LLM can still surface a real concern
     when it didn't name a file).
   - Otherwise → at least one scope entry matches the file per
     `entry-matches-file?`."
  [scope issue]
  (let [file (some-> (:file issue) str str/trim not-empty)]
    (if (nil? file)
      true
      (boolean (some #(entry-matches-file? % file) scope)))))

(defn partition-issues-by-scope
  "Split `issues` against `scope` and return
   `{:in-scope [...] :out-of-scope [...]}`."
  [scope issues]
  (let [{in true out false} (group-by #(in-scope-issue? scope %) issues)]
    {:in-scope     (vec in)
     :out-of-scope (vec out)}))

;------------------------------------------------------------------------------ Layer 2
;; Telemetry

(defn filter-applied-event
  "Build the canonical `:reviewer.scope/filter-applied` log payload for a
   single filter pass. Extracted so the initial-parse and enumeration-
   retry sites in the reviewer share one shape (rule 006-style factory
   for a repeated map)."
  [{:keys [scope filtered-count kept-count retry-path]}]
  {:data {:scope          scope
          :filtered-count filtered-count
          :kept-count     kept-count
          :retry-path     retry-path
          :message        (msg/t :reviewer.scope/filter-applied
                                 {:filtered-count filtered-count
                                  :kept-count     kept-count})}})
