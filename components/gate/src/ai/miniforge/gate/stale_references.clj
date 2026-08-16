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
(ns ai.miniforge.gate.stale-references
  "Deterministic contract-drift gate: when a change removes a keyword or
   a def'd name from a file, every remaining reference to it elsewhere
   in the repo is a silently-broken consumer.

   This gate is a Thesium Codex peg migrated into a mechanism (SPEC
   §4.4.2.a/§4.5 — the best outcome a peg can have). It enforces the
   problem node `contract-drift-is-silent` (situation
   changing-one-side-of-a-boundary; scars knight-capital-flag-reuse,
   round-format-drift): the trap bench measured eight of eight
   implementer runs renaming a producer and missing an untested
   consumer, with the prose warning delivered and read. Prose did not
   change the outcome; this gate does not rely on prose.

   Mechanics: for each changed file, the before-content comes from
   `git show HEAD:<path>` in the worktree (implement runs on uncommitted
   work, so HEAD is the pre-change state). Candidate tokens are keywords
   and def'd names present in a before and absent from EVERY changed
   file's after-content. Each candidate is then searched repo-wide
   (`git grep -F`); any hit outside the changed set is a stale
   reference and fails the gate with the token and its files.

   Fail-open guards, each surfaced as a warning: no worktree path, no
   changed-file content, git unavailable. The gate never guesses."
  (:require [ai.miniforge.gate.messages :as messages]
            [ai.miniforge.gate.registry :as registry]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} min-token-length
  "Tokens shorter than this are too collision-prone to grep for."
  4)

(def ^{:stratum 0} max-tokens
  "Upper bound of removed tokens checked per run — a mass refactor
   should fail on its first few stale tokens, not grep for hundreds."
  24)

(def ^{:stratum 0} max-files-per-token
  "Upper bound of stale files reported per token."
  8)

(def ^{:stratum 0} keyword-pattern
  "Clojure keyword literals, namespaced or bare."
  #"(?<![\w:]):[A-Za-z][A-Za-z0-9*+!?_<>=./-]*")

(def ^{:stratum 0} def-name-pattern
  "Names bound by def/defn/defn-/defmacro/defmulti at any nesting."
  #"\(def(?:n-?|macro|multi)?\s+(?:\^\S+\s+|\^\{[^}]*\}\s+)*([A-Za-z][A-Za-z0-9*+!?_<>=<-]*[A-Za-z0-9*+!?_<>=-])")

(defn- ^{:stratum 0} git
  "Trimmed stdout of a git command in `dir`, or nil on any failure."
  [dir & args]
  ;; Plain try, class-only catch at an absolute boundary (std 211 ex. a):
  ;; a missing git binary must degrade to the gate's fail-open warning.
  (try
    (let [{:keys [exit out]} (apply shell/sh "git" "-C" (str dir) args)]
      (when (zero? exit) (str out)))
    (catch Exception _ nil)))

(defn ^{:stratum 0} token-present?
  "Whole-token occurrence of `token` in `blob` — boundary-anchored so
   :skip is not counted present because :skipped survives. Public for
   tests."
  [blob token]
  (boolean
   (re-find (re-pattern (str "(?<![\\w:*+!?<>=./-])"
                             (java.util.regex.Pattern/quote token)
                             "(?![\\w*+!?<>=./-])"))
            (str blob))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} content-tokens
  "The keyword literals and def'd names in `content`. Public for tests."
  [content]
  ;; keyword-pattern has no capture group, so re-seq yields the match
  ;; strings directly; def-name-pattern captures the bound name.
  (into (set (re-seq keyword-pattern (str content)))
        (map second (re-seq def-name-pattern (str content)))))

(defn- ^{:stratum 1} before-content
  "The pre-change content of `path`, or nil when the file is new."
  [worktree path]
  (git worktree "show" (str "HEAD:" path)))

(defn- ^{:stratum 1} stale-files
  "Repo files outside `changed-paths` still referencing `token`."
  [worktree changed-paths token]
  ;; -e makes the token an explicit pattern — a token can never be
  ;; parsed as an option or pathspec regardless of its first character.
  (when-let [out (git worktree "grep" "-I" "-l" "-F" "-e" token "--")]
    (->> (str/split-lines out)
         (remove str/blank?)
         (remove (set changed-paths))
         (take max-files-per-token)
         vec)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} removed-tokens
  "Tokens present in some before-content and absent (as whole tokens)
   from every after-content, longest first (a rename's long form is the
   real contract; its fragments are noise). Public for tests."
  [befores afters]
  (let [before-tokens (reduce into #{} (map content-tokens befores))
        after-blob (str/join "\n" (map str afters))]
    (->> before-tokens
         (remove #(< (count %) min-token-length))
         (remove #(token-present? after-blob %))
         (sort-by (comp - count))
         (take max-tokens)
         vec)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} check-stale-references
  "Gate check (see ns docstring). `artifact` is the implement phase
   output; changed files ride on :code/files ({:path :content :action})
   with :code/file-paths as the path fallback.

   `befores`/`afters`/`stale` are computed up front, unconditionally --
   each is nil-safe on its own (a nil `worktree` or empty `paths` just
   flows through as empty results) and the work is cheap, so guarding
   each behind its own nesting level bought nothing but extra
   conditional depth."
  [artifact ctx]
  (let [worktree (get ctx :execution/worktree-path)
        files (:code/files artifact)
        paths (or (seq (keep :path files)) (:code/file-paths artifact))
        git-ok? (and worktree (some? (git worktree "rev-parse" "--git-dir")))
        befores (when (and git-ok? (seq paths)) (keep #(before-content worktree %) paths))
        afters (when (seq paths)
                 (if (seq files)
                   (keep :content files)
                   (keep #(try (slurp (str worktree "/" %))
                               (catch Exception _ nil))
                         paths)))
        stale (into []
                    (keep (fn [token]
                            (let [hits (stale-files worktree paths token)]
                              (when (seq hits)
                                {:type :stale-reference
                                 :token token
                                 :files hits
                                 :message (messages/t :stale-references/stale
                                                      {:token token
                                                       :files (str/join ", " hits)})}))))
                    (removed-tokens befores afters))]
    (cond
      (empty? paths)
      {:passed? true}

      (nil? worktree)
      {:passed? true
       :warnings [{:type :stale-references-skipped
                   :message (messages/t :stale-references/no-worktree)}]}

      (not git-ok?)
      {:passed? true
       :warnings [{:type :stale-references-skipped
                   :message (messages/t :stale-references/git-unavailable)}]}

      (empty? afters)
      {:passed? true
       :warnings [{:type :stale-references-skipped
                   :message (messages/t :stale-references/no-content)}]}

      (seq stale)
      {:passed? false :errors stale}

      :else
      {:passed? true})))

;------------------------------------------------------------------------------ Layer 4

(defmethod ^{:stratum 4} registry/get-gate :stale-references
  [_]
  {:name :stale-references
   :description (messages/t :stale-references/description)
   :check check-stale-references
   :repair nil})

;; Registry
(registry/register-gate! :stale-references)
