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
(ns ai.miniforge.agent.artifact-session.parsing
  "Parsing helpers for artifact-session outputs: UUID and instant strings
   in artifact EDN, the worktree role files, the §7.7.2 answer log, and the
   capsule output markers."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private system-message-templates
  "System-localized stderr message templates for artifact-session I/O."
  {:warn/worktree-artifact-parse
   "WARN: failed to parse worktree artifact at %s — %s"

   :warn/capsule-worktree-artifact-parse
   "WARN: failed to parse capsule worktree artifact at %s — %s"

   :warn/no-artifact-found
   "WARN: no artifact found after session — checked MCP path %s and worktree role files under %s (roles: %s)"

   :warn/artifact-parse
   "WARN: failed to parse artifact at %s — %s"

   :warn/context-misses-parse
   "WARN: failed to parse context misses at %s — %s"

   :warn/context-reads-parse
   "WARN: failed to parse context reads at %s — %s"

   :warn/codex-answers-parse
   "WARN: failed to parse codex peg answers at %s — %s"

   :info/mcp-artifact-skipped
   "INFO: MCP artifact not submitted — worktree-promotion succeeded (workdir: %s)"})

(defn- ^{:stratum 0} artifact-filename
  "Canonical filename for a role artifact in .miniforge/."
  [role]
  (str (name role) ".edn"))

;; Artifact reading
(defn ^{:stratum 0} uuid-str?
  "Check if a value is a UUID-shaped string."
  [v]
  (and (string? v)
       (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" v)))

(defn ^{:stratum 0} instant-str?
  "Check if a value looks like an ISO instant string."
  [v]
  (and (string? v)
       (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*" v)))

(defn ^{:stratum 0} key-ends-with?
  "Check if a namespaced keyword ends with the given suffix."
  [k suffix]
  (and (keyword? k)
       (str/ends-with? (name k) suffix)))

(def ^{:stratum 0} worktree-roles
  "Roles whose `.miniforge/<role>.edn` files `run-session` probes for
   worktree-promoted artifacts. Single source of truth for both the
   scan and the no-artifact-found diagnostic."
  [:plan :implement :verify :review :release])

(def ^{:stratum 0} capsule-output-boundary
  "Sentinel line separating the concatenated session-output files in the
   single `read-capsule-session-outputs` executor round-trip.

   Collision assumption: the first three segments (context-misses.edn,
   context-reads.edn, answers.edn) are written by our own MCP server and
   contain record maps (paths, keywords, short answer strings,
   timestamps), so this sentinel is not expected to occur in them — a pathological workspace path containing
   it would corrupt the split. The artifact segment CAN contain arbitrary
   agent-authored strings, which is why it is deliberately placed LAST in
   the cat chain: the bounded `str/split` keeps any embedded occurrence
   inside the final segment intact."
  "===MINIFORGE-SESSION-OUTPUT-BOUNDARY===")

(def ^{:stratum 0} capsule-answers-present-marker
  "Sentinel line echoed BEFORE answers.edn's content when the file
   exists, so the capsule readers can tell a present-but-empty file (a
   write torn before any bytes landed — :unreadable, observations LOST)
   from a missing one (:absent). `cat` output alone cannot make that
   distinction; the host reader gets it from `.exists`. Same collision
   assumption as `capsule-output-boundary`: answers.edn is written by
   our own MCP server and is not expected to contain this line."
  "===MINIFORGE-ANSWERS-PRESENT===")

(defn ^{:stratum 0} read-role-entry
  "Read the worktree artifact for `role` via `read-role-artifact` and
   return a `[role artifact]` map entry, or nil if the role file was
   absent (so callers can use `keep` to drop misses)."
  [read-role-artifact role]
  (when-let [a (read-role-artifact role)]
    [role a]))

(defn ^{:stratum 0} answer-log-state
  "The §7.7.2 answer-log marker for one session's answers.edn: :absent
   when the MCP server wrote no file (it only writes when answers were
   recorded), :recorded when the file parsed, :unreadable when a file
   was present but did not parse. The last case means recorded
   observations were LOST — the durable record must keep it distinct
   from pegs that genuinely went unanswered, or a torn log silently
   inflates the telemetry's unanswered count."
  [present? parsed]
  (cond
    (not present?) :absent
    (nil? parsed)  :unreadable
    :else          :recorded))

(defn ^{:stratum 0} merge-answer-logs
  "One §7.7.2 answer-log marker over several sessions' logs (split
   reviewer sessions, an implementer's primary + recovery turn): any
   torn log means observations were lost somewhere (:unreadable wins),
   else any recorded log means the channel worked (:recorded), else
   :absent. Conservative on the mixed readable/torn case — the whole
   run is excluded from the telemetry's unanswered count rather than
   letting a partially lost record masquerade as a complete one."
  [logs]
  (cond
    (some #{:unreadable} logs) :unreadable
    (some #{:recorded} logs)   :recorded
    :else                      :absent))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} split-answers-segment
  "Split an answers.edn `cat` segment that was prefixed by the
   presence-marker echo into {:present? bool :content trimmed-str}.

   nil marks a TRUNCATED batched read: a normal missing file still
   produces every echoed boundary line and an empty (non-nil) segment,
   so a nil segment means the output itself was cut and the channel's
   state is unknown. Unknown must read as lost, never as the agent
   declining to answer — the nil path yields {:present? true
   :content \"\"}, which answer-log-state classifies :unreadable."
  [segment]
  (if (nil? segment)
    {:present? true :content ""}
    (let [trimmed (str/trim segment)
          present? (str/starts-with? trimmed capsule-answers-present-marker)]
      {:present? present?
       :content  (if present?
                   (str/trim (subs trimmed (count capsule-answers-present-marker)))
                   trimmed)})))

(defn ^{:stratum 1} emit-system-message!
  "Render a system-scoped stderr message from a shared template."
  [message-key & args]
  (binding [*out* *err*]
    (println (apply format (get system-message-templates message-key) args))))

(defn ^{:stratum 1} worktree-artifact-file
  "Resolve the canonical .miniforge artifact file for a role."
  [workdir role]
  (io/file workdir ".miniforge" (artifact-filename role)))

(defn ^{:stratum 1} parse-uuid-strings
  "Convert string UUIDs and ISO instant strings in an artifact map.
   The MCP server writes UUIDs and instants as strings since it runs in babashka.

   Pattern-based detection:
   - Any key ending in `/id` with a UUID-shaped string → java.util.UUID
   - Any key ending in `/created-at` with an ISO instant string → java.util.Date
   - Any vector of maps containing `:task/id` → recurse into those maps"
  [m]
  (cond
    (map? m)
    (into {}
          (map (fn [[k v]]
                 [k (cond
                      ;; Any key ending in /id with a UUID string
                      (and (key-ends-with? k "id") (uuid-str? v))
                      (java.util.UUID/fromString v)

                      ;; Any key ending in /created-at with an instant string
                      (and (key-ends-with? k "created-at") (instant-str? v))
                      (try (java.util.Date/from (java.time.Instant/parse v))
                           (catch Exception _ (java.util.Date.)))

                      ;; Vector of maps that may contain nested UUIDs/instants
                      (and (vector? v) (seq v) (map? (first v)))
                      (mapv (fn [item]
                              (parse-uuid-strings item))
                            v)

                      :else v)]))
          m)

    :else m))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-edn-content
  "Parse EDN content with a transform, logging failures as system messages."
  [content transform warning-key path]
  (try
    (-> content
        transform)
    (catch Exception e
      (emit-system-message! warning-key path (ex-message e))
      nil)))
