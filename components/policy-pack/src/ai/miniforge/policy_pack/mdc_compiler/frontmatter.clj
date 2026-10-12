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
(ns ai.miniforge.policy-pack.mdc-compiler.frontmatter
  "MDC frontmatter line-grammar: splitting a file into frontmatter/body,
   parsing the frontmatter block's lines (key: value, multi-line
   `- item` lists) into a string-keyed map, and reporting keys written
   in a form the grammar cannot represent (`nested-block-keys`,
   `commented-list-keys`, `interrupted-list-keys`).
   Split out of
   `ai.miniforge.policy-pack.mdc-compiler` (rule 210: slice 2/6 of the
   same split train as
   `ai.miniforge.policy-pack.mdc-compiler.frontmatter-values`, miniforge#1729
   — same approach as the dag-orchestrator split, miniforge#1485, and
   the workflow-runner split, miniforge#1662)."
  (:require
   [ai.miniforge.policy-pack.mdc-compiler.frontmatter-values :as frontmatter-values]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; MDC parsing — frontmatter and body extraction
(defn ^{:stratum 0} split-frontmatter
  "Split MDC content into frontmatter string and body string.

   Expects --- delimited YAML frontmatter at the top of the file.

   Arguments:
   - content - Full .mdc file content

   Returns:
   - {:frontmatter <string> :body <string>}"
  [content]
  (let [trimmed (str/trim (str content))]
    (if (str/starts-with? trimmed "---")
      (let [after-open (subs trimmed 3)
            close-idx (str/index-of after-open "---")]
        (if close-idx
          {:frontmatter (str/trim (subs after-open 0 close-idx))
           :body        (str/trim (subs after-open (+ close-idx 3)))}
          {:frontmatter ""
           :body        trimmed}))
      {:frontmatter ""
       :body        trimmed})))

(defn- ^{:stratum 0} parse-list-item
  "Parse a '- <value>' frontmatter list line to its string value."
  [trimmed]
  (frontmatter-values/strip-quotes (str/trim (subs trimmed 2))))

(defn- ^{:stratum 0} parse-kv-line
  "Parse a 'key: value' frontmatter line.
   Returns [new-current-key updated-acc]."
  [trimmed acc]
  (let [idx (str/index-of trimmed ":")
        k   (str/trim (subs trimmed 0 idx))
        v   (str/trim (subs trimmed (inc idx)))]
    (if (str/blank? v)
      [k (assoc acc k [])]
      [nil (assoc acc k (frontmatter-values/parse-frontmatter-value v))])))

;; ── Nested-block detection ──────────────────────────────────────────────────
;; The grammar below is flat. An author writing a nested YAML mapping gets no
;; parse error: `process-frontmatter-line` trims each line before deciding what
;; it is, so an indented `child: value` is indistinguishable from a top-level
;; one. The children land as separate top-level keys and the parent is left
;; holding `[]`, which `group-dotted-keys` then finds nothing to group. The rule
;; compiles to its defaults and reports success. `nested-block-keys` is the only
;; thing standing between that and a silent downgrade, so it belongs here beside
;; the grammar it guards rather than in the caller.
(def ^{:stratum 0} ^:private blank-valued-key-re
  "A `key:` line with no value — the opening of either a legitimate
   `- item` list or an (unsupported) nested mapping.

   A trailing `# comment` counts as no value, because that is what it is
   in YAML and because the `## Core Structure` template in
   `meta/rule-format` writes frontmatter comments. (`parse-kv-line` below
   disagrees — it captures `# comment` as the value — but a parent whose
   value is a comment string resolves no `key.*` entries either way, so
   the detector and the parser land on the same silent fallback.)"
  #"^([A-Za-z][A-Za-z0-9._-]*)[ \t]*:[ \t]*(?:#.*)?$")

(def ^{:stratum 0} ^:private indented-kv-re
  "An indented `child: ...` line. This is the nested-mapping signature.
   An indented `- item` line does NOT match, so list-valued keys —
   `globs:` and friends — are not reported.

   A dotted child (`  detection.mode: positive`) matches too. That shape
   does not fall back silently — `group-dotted-keys` throws \"Key must be
   integer\" trying to `assoc` onto the parent's `[]` — but the exception
   names nothing an author can act on, so the key is still worth
   reporting alongside it."
  #"^[ \t]+[A-Za-z][A-Za-z0-9._-]*[ \t]*:")

(def ^{:stratum 0} ^:private commented-key-re
  "A `key: # comment` line: a key whose only value is a comment."
  #"^([A-Za-z][A-Za-z0-9._-]*)[ \t]*:[ \t]*#.*$")

(def ^{:stratum 0} ^:private list-item-re
  "A `- item` line, indented or not. Indentation is NOT required:
   `process-frontmatter-line` trims every line before testing for `\"- \"`,
   so a flush-left list is a supported form and loses its items to a
   commented parent exactly the same way.

   This is the one place indentation does not matter. `indented-kv-re`
   above still requires it, because an unindented `key: value` is a
   legitimate top-level key rather than a nested child."
  #"^[ \t]*-[ \t]")

(def ^{:stratum 0} ^:private comment-line-re
  "A line carrying only a comment, at any indentation. Skipped when
   looking for a parent's first child, so a comment between the two does
   not hide the nesting."
  #"^[ \t]*#")

(def ^{:stratum 0} ^:private bare-key-re
  "A `key:` line with no value and NO trailing comment — the opening of
   a list the parser will actually collect. `blank-valued-key-re` above
   also admits the commented form; this one does not, because a
   commented parent never opens a list at all and is reported separately
   by `commented-list-keys`."
  #"^([A-Za-z][A-Za-z0-9._-]*)[ \t]*:[ \t]*$")

(def ^{:stratum 0} ^:private colon-comment-re
  "A comment line that contains a colon. Inside a list this is
   destructive: `process-frontmatter-line` trims the line, sees the
   colon, and routes it to `parse-kv-line`, which stores it as a key and
   clears `current-key`. Every `- item` after it is dropped."
  #"^[ \t]*#.*:")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} nested-block-keys
  "Frontmatter keys written as a nested YAML mapping, which this grammar
   cannot represent.

   A key qualifies when it has no inline value — a trailing comment counts
   as none — and its next line that is neither blank nor comment-only is an
   indented `child: value`. List-valued keys (`- item` children) and keys
   with no children at all do not qualify.

   Arguments:
   - frontmatter-str - Raw text between --- delimiters

   Returns:
   - Vector of parent key names in file order, or [] when the frontmatter
     is flat (the expected case)."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    []
    (let [lines      (vec (str/split-lines frontmatter-str))
          noise?     (fn [line] (or (str/blank? line)
                                    (re-find comment-line-re line)))
          next-child (fn [idx] (first (remove noise? (subvec lines (inc idx)))))]
      (into []
            (keep (fn [idx]
                    (when-let [[_ k] (re-matches blank-valued-key-re (lines idx))]
                      (when-let [child (next-child idx)]
                        (when (re-find indented-kv-re child)
                          k)))))
            (range (count lines))))))

(defn ^{:stratum 1} commented-list-keys
  "Frontmatter keys written as `key: # comment` over a `- item` list,
   where the comment is kept as the value and the list is discarded.

   `parse-kv-line` treats the comment as a non-blank value, so it never
   opens a list: it clears `current-key`, and every `- item` line after
   it is dropped on the floor. A `globs: # the shell ones` over two
   patterns compiles to `:file-globs [\"# the shell ones\"]` — a glob that
   matches nothing, on a rule that looks scoped.

   Distinct from `nested-block-keys`: a *bare* `key:` over a list is the
   one multi-line form the grammar supports and is never reported. Only
   the commented form loses data — in both the indented and the
   flush-left list style, since the parser trims before testing for
   `\"- \"`.

   Arguments:
   - frontmatter-str - Raw text between --- delimiters

   Returns:
   - Vector of key names in file order, or []."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    []
    (let [lines      (vec (str/split-lines frontmatter-str))
          noise?     (fn [line] (or (str/blank? line)
                                    (re-find comment-line-re line)))
          next-child (fn [idx] (first (remove noise? (subvec lines (inc idx)))))]
      (into []
            (keep (fn [idx]
                    (when-let [[_ k] (re-matches commented-key-re (lines idx))]
                      (when-let [child (next-child idx)]
                        (when (re-find list-item-re child)
                          k)))))
            (range (count lines))))))

(defn ^{:stratum 1} interrupted-list-keys
  "Keys whose `- item` list is cut short by a comment line containing a
   colon.

   `process-frontmatter-line` trims before classifying, so an indented
   `# note: the shell ones` is indistinguishable from a top-level
   `key: value`. It is stored as a key named `# note`, `current-key` is
   cleared, and every list item after it is dropped on the floor.

   Position decides how much is lost, and only items BELOW the comment
   are. Above every item, the whole list goes and the key compiles as
   `[]`. In the middle, the list is *truncated* — the rule keeps the
   globs written above the comment and silently loses the rest, which is
   the harder one to notice. Trailing the last item, nothing is lost:
   the parser has already collected them, and the comment only leaves an
   ignored junk key. That case is NOT reported — the condition is a list
   item somewhere after the first colon comment, not merely both in the
   same block.

   A comment without a colon is harmless and is not reported. Nor is a
   colon-bearing comment outside a list: it leaves a junk key behind but
   discards nothing.

   Arguments:
   - frontmatter-str - Raw text between --- delimiters

   Returns:
   - Vector of key names in file order, or []."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    []
    (let [lines (vec (str/split-lines frontmatter-str))]
      (into []
            (keep (fn [idx]
                    (when-let [[_ k] (re-matches bare-key-re (lines idx))]
                      (let [block (take-while (fn [l] (or (str/blank? l)
                                                          (re-find comment-line-re l)
                                                          (re-find list-item-re l)))
                                              (subvec lines (inc idx)))
                            ;; Only items BELOW the first colon comment are
                            ;; lost. A colon comment trailing the last item
                            ;; discards nothing — the parser has already
                            ;; collected them — so it must not be reported.
                            after (rest (drop-while #(not (re-find colon-comment-re %))
                                                    block))]
                        (when (some #(re-find list-item-re %) after)
                          k)))))
            (range (count lines))))))

(defn- ^{:stratum 1} process-frontmatter-line
  "Process one frontmatter line. Returns [current-key acc]."
  [trimmed current-key acc]
  (cond
    (str/blank? trimmed)
    [current-key acc]

    (and current-key (str/starts-with? trimmed "- "))
    [current-key (update acc current-key (fnil conj []) (parse-list-item trimmed))]

    (str/includes? trimmed ":")
    (parse-kv-line trimmed acc)

    :else [current-key acc]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-frontmatter
  "Parse YAML-like frontmatter text into a string-keyed map.

   Handles simple key: value pairs, inline arrays [a, b], and
   multi-line list items (- value) under a key.

   Arguments:
   - frontmatter-str - Raw text between --- delimiters

   Returns:
   - Map of {string-key parsed-value}, or empty map."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    {}
    (loop [[line & remaining] (str/split-lines frontmatter-str)
           current-key nil
           acc {}]
      (if (nil? line)
        acc
        (let [[current-key' acc'] (process-frontmatter-line (str/trim line) current-key acc)]
          (recur remaining current-key' acc'))))))
