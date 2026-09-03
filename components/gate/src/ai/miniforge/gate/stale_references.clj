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
   work, so HEAD is the pre-change state). Producers are the changed
   non-test files that declare a namespace, grouped by namespace family
   (`ai.miniforge.codex-gap` for `...codex-gap.ledger`). A family's
   candidate tokens are keywords and def'd names present in a
   producer's before and absent from THAT producer's after -- judged
   per file. A token that survives in another changed non-test file
   (of any family) is still a candidate (trap-bench rep ru3d: report.clj kept
   `:skipped` as a message-template key while ledger.clj renamed its
   result key, and a family-wide rule read that as a move and passed
   the sprung tree); the error names where it survives so the
   implementer can tell a same-name key from a reference to update.
   Each candidate is then searched in the family's
   importers -- files outside the changed set that require the family:
   the family name opened by a require vector's bracket or preceded by
   a quote, as a ns :require, a bb.edn :requires, or a quoted require
   write it (spelled out in `import-needles`; not repeated here, since
   this docstring would otherwise import every family it names) -- with
   namespaced keywords searched repo-wide; any hit is a stale reference
   and fails the gate with the token, its files, and each file's first
   matching line. Three kinds of file are never importers: prose that
   merely mentions the namespace, test files, and the producer's own
   component -- the last two are exercised by verify's tests, and this
   gate exists for the consumer no test covers. Trap-bench series 4:
   the denial listed the gate's own docstring, a test fixture and a
   same-component enum use of the keyword next to the real consumer;
   the implementer fixed the wrong files in rt1/rt2, and in rt3 fixed
   the right one and was still denied on the spurious ones.

   Why per-family and consumer-scoped (trap-bench repair series 3,
   rep rs1): the implementer renamed the ledger's :skipped but a changed
   TEST kept `{:status :skipped}` in another sense, so a global
   absent-from-every-changed-file rule never saw a removal -- allow, five
   iterations running. And an unscoped repo-wide search for a bare
   keyword like :skipped returns dozens of unrelated files, which is
   noise the implementer cannot act on. Tests are not producers and
   not a token's new home; consumers are the files that import you.

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

(def ^{:stratum 0} ns-pattern
  "The name bound by a file's (ns ...) form."
  #"\(ns\s+(?:\^\S+\s+|\^\{[^}]*\}\s+)*([A-Za-z][\w.*+!?<>=-]*)")

(defn ^{:stratum 0} test-path?
  "Test files are neither producers nor a token's new home. Public for
   tests."
  [path]
  (boolean (re-find #"(?:^|/)test/|[_-]test\.clj[cs]?$" (str path))))

(defn ^{:stratum 0} namespace-family
  "A namespace with its last segment dropped -- `ai.miniforge.codex-gap`
   for `ai.miniforge.codex-gap.ledger` -- which is the string every
   consumer of the component writes when it requires the interface. A
   single-segment namespace is its own family. Public for tests."
  [ns-name]
  (let [s (str ns-name)
        i (str/last-index-of s ".")]
    (if i (subs s 0 i) s)))

(defn ^{:stratum 0} component-dir
  "The brick directory -- `components/<c>/` or `bases/<b>/` -- for a
   Polylith path, else nil. Public for tests."
  [path]
  (second (re-find #"^((?:components|bases)/[^/]+/)" (str path))))

(defn ^{:stratum 0} import-needles
  "The literal forms a file uses to require a namespace family: the
   opening of a require vector and a quoted symbol. Public for tests."
  [family]
  [(str "[" family) (str "'" family)])

(defn ^{:stratum 0} namespaced-keyword?
  "`:ledger/skipped` -- precise enough to search the whole repo for."
  [token]
  (let [s (str token)]
    (and (str/starts-with? s ":") (str/includes? s "/"))))

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

(defn ^{:stratum 1} ns-name-of
  "The namespace a Clojure file declares, or nil -- edn, bb.edn and
   scripts without an ns form are data, not producers. Public for tests."
  [content]
  (second (re-find ns-pattern (str content))))

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

(defn- ^{:stratum 1} worktree-changed-paths
  "Every path the worktree has changed against HEAD — tracked
   modifications AND untracked files — regardless of which implement
   attempt produced them. A retry that changes nothing must still be
   judged on the state the previous attempt left behind; judging only
   the attempt's own artifact let an empty-diff retry pass vacuously
   while the stale rename persisted (trap-bench REPAIR DEMONSTRATION,
   reps rq1-rq3: every run's final iteration was decision :allow on
   zero files)."
  [worktree]
  (let [tracked (str (git worktree "diff" "--name-only" "HEAD" "--"))
        untracked (str (git worktree "ls-files" "--others" "--exclude-standard"))]
    (->> (concat (str/split-lines tracked) (str/split-lines untracked))
         (map str/trim)
         (remove str/blank?)
         distinct
         vec)))

(defn- ^{:stratum 1} referencing-files
  "Repo files outside `changed-paths` mentioning `needle`. Always a
   whole-repo `git grep` -- cheap, and the argv stays constant-size;
   scoping to importers is a set intersection in the caller, never a
   pathspec list that could outgrow the OS argument limit."
  [worktree changed-paths needle]
  ;; -e makes the needle an explicit pattern — it can never be parsed
  ;; as an option or pathspec regardless of its first character.
  (when-let [out (git worktree "grep" "-I" "-l" "-F" "-e" needle "--")]
    (->> (str/split-lines out)
         (remove str/blank?)
         (remove (set changed-paths))
         vec)))

(defn- ^{:stratum 1} first-hit
  "`{:file :line :text}` for the first line of `path` mentioning `token`
   -- the evidence an implementer can act on without re-running the
   search. Nil when git reports nothing."
  [worktree token path]
  (when-let [out (git worktree "grep" "-n" "-I" "-F" "-e" token "--" path)]
    (when-let [line (first (remove str/blank? (str/split-lines out)))]
      ;; git grep -n prints path:lineno:text; the path may itself
      ;; contain ":" only on exotic filesystems, so split from the
      ;; known prefix rather than on the first two colons.
      (let [rest (subs line (min (count line) (inc (count path))))
            [lineno text] (str/split rest #":" 2)]
        {:file path
         :line (some-> lineno str/trim parse-long)
         :text (str/trim (str text))}))))

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

(defn ^{:stratum 2} producer-families
  "The changed non-test files that declare a namespace, grouped by
   namespace family: {family {:befores [content ...] :paths [path ...]}},
   befores and paths in the same order. `before-of` maps a path to its
   pre-change content. Public for tests."
  [paths before-of]
  (reduce (fn [acc path]
            (let [before (get before-of path)
                  ns-name (ns-name-of before)]
              (if (and ns-name (not (test-path? path)))
                (-> acc
                    (update-in [(namespace-family ns-name) :befores] (fnil conj []) before)
                    (update-in [(namespace-family ns-name) :paths] (fnil conj []) path))
                acc)))
          {}
          paths))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} removed-per-file
  "Tokens removed from any single producer: present in a file's before
   and absent from that same file's after. `after-of` maps path to
   after-content; a producer with no after-content -- deleted, or
   unreadable -- has lost every token it had. Longest first,
   deduplicated. Public for tests."
  [befores paths after-of]
  (->> (map (fn [before path]
              (removed-tokens [before] [(get after-of path "")]))
            befores paths)
       (apply concat)
       distinct
       (sort-by (comp - count))
       (take max-tokens)
       vec))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} check-stale-references
  "Gate check (see ns docstring). `artifact` is the implement phase
   output; changed files ride on :code/files ({:path :content :action})
   with :code/file-paths as the path fallback.

   `before-of`/`after-of`/`stale` are computed up front, unconditionally
   -- each is nil-safe on its own (a nil `worktree` or empty `paths`
   just flows through as empty results) and the work is cheap, so
   guarding each behind its own nesting level bought nothing but extra
   conditional depth."
  [artifact ctx]
  (let [worktree (get ctx :execution/worktree-path)
        files (:code/files artifact)
        git-ok? (and worktree (some? (git worktree "rev-parse" "--git-dir")))
        artifact-paths (or (seq (keep :path files)) (:code/file-paths artifact))
        ;; CUMULATIVE: the attempt's artifact plus everything the worktree
        ;; already carries against HEAD — see worktree-changed-paths.
        paths (vec (distinct (concat artifact-paths
                                     (when git-ok? (worktree-changed-paths worktree)))))
        artifact-content (into {} (map (juxt :path :content)) files)
        before-of (when (and git-ok? (seq paths))
                    (into {} (keep (fn [path]
                                     (when-let [b (before-content worktree path)]
                                       [path b])))
                          paths))
        after-of (when (seq paths)
                   (into {} (keep (fn [path]
                                    (when-let [a (or (get artifact-content path)
                                                     (try (slurp (str worktree "/" path))
                                                          (catch Exception _ nil)))]
                                      [path a])))
                         paths))
        stale (into []
                    (for [[family {:keys [befores] producer-paths :paths}] (producer-families paths before-of)
                          ;; A family nobody names any more (renamed out of
                          ;; the worktree) has NO consumers -- an empty set,
                          ;; so its bare tokens match nothing.
                          :let [own-component (some component-dir producer-paths)
                                consumers (delay (into #{}
                                                       (comp (mapcat #(referencing-files worktree paths %))
                                                             (remove test-path?)
                                                             (remove #(and own-component
                                                                           (str/starts-with? % own-component))))
                                                       (import-needles family)))]
                          token (removed-per-file befores producer-paths after-of)
                          :let [in-scope? (if (namespaced-keyword? token)
                                            (constantly true)
                                            @consumers)
                                files (->> (referencing-files worktree paths token)
                                           (filter in-scope?)
                                           (take max-files-per-token)
                                           vec)
                                hits (into [] (keep #(first-hit worktree token %)) files)
                                survives-in (->> after-of
                                                 (keep (fn [[path a]]
                                                         (when (and (not (test-path? path))
                                                                    (token-present? a token))
                                                           path)))
                                                 sort
                                                 vec)]
                          :when (seq files)]
                      {:type :stale-reference
                       :token token
                       :family family
                       :files files
                       :hits hits
                       :survives-in survives-in
                       :message (str (messages/t :stale-references/stale
                                                 {:token token
                                                  :files (str/join ", " files)})
                                     (when (seq survives-in)
                                       (str " " (messages/t :stale-references/survives
                                                            {:files (str/join ", " survives-in)})))
                                     (str/join "" (map #(str "\n" (messages/t :stale-references/hit %))
                                                       hits)))}))]
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

      (empty? after-of)
      {:passed? true
       :warnings [{:type :stale-references-skipped
                   :message (messages/t :stale-references/no-content)}]}

      (seq stale)
      {:passed? false :errors stale}

      :else
      {:passed? true})))

;------------------------------------------------------------------------------ Layer 5

(defmethod ^{:stratum 5} registry/get-gate :stale-references
  [_]
  {:name :stale-references
   :description (messages/t :stale-references/description)
   :check check-stale-references
   :repair nil})

;; Registry
(registry/register-gate! :stale-references)
