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

(def ^{:stratum 0} ^:private blank-valued-key-re
  "A `key:` line with no inline value. A trailing `# comment` counts as
   no value."
  #"^([A-Za-z][A-Za-z0-9._-]*)[ \t]*:[ \t]*(?:#.*)?$")

(def ^{:stratum 0} ^:private indented-kv-re
  "An indented `child: ...` line, dotted children included. An indented
   `- item` line does not match."
  #"^[ \t]+[A-Za-z][A-Za-z0-9._-]*[ \t]*:")

(def ^{:stratum 0} ^:private commented-key-re
  "A `key: # comment` line: a key whose only value is a comment."
  #"^([A-Za-z][A-Za-z0-9._-]*)[ \t]*:[ \t]*#.*$")

(def ^{:stratum 0} ^:private list-item-re
  "A `- item` line, indented or flush-left."
  #"^[ \t]*-[ \t]")

(def ^{:stratum 0} ^:private comment-line-re
  "A comment-only line, at any indentation."
  #"^[ \t]*#")

(def ^{:stratum 0} ^:private bare-key-re
  "A `key:` line with no value and no trailing comment."
  #"^([A-Za-z][A-Za-z0-9._-]*)[ \t]*:[ \t]*$")

(def ^{:stratum 0} ^:private colon-comment-re
  "A comment-only line that contains a colon."
  #"^[ \t]*#.*:")

(defn- ^{:stratum 0} frontmatter-lines
  "Lines of `frontmatter-str` as a vector; empty when it is blank."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    []
    (vec (str/split-lines frontmatter-str))))

(defn- ^{:stratum 0} adjacent-pairs
  "Each element of `coll` paired with the element after it, in order."
  [coll]
  (map vector coll (rest coll)))

(defn- ^{:stratum 0} line-tails
  "Each suffix of the `lines` vector, in order: a line followed by every
   line below it."
  [lines]
  (map #(subvec lines %) (range (count lines))))

(defn- ^{:stratum 0} key-over-child
  "Key captured by `key-re` on `parent` when `child` matches `child-re`,
   else nil."
  [key-re child-re [parent child]]
  (when (re-find child-re child)
    (second (re-matches key-re parent))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} noise-line?
  "True when `line` is blank or comment-only."
  [line]
  (boolean (or (str/blank? line)
               (re-find comment-line-re line))))

(defn- ^{:stratum 1} list-block-line?
  "True when `line` is blank, comment-only, or a `- item` line."
  [line]
  (boolean (or (str/blank? line)
               (re-find comment-line-re line)
               (re-find list-item-re line))))

(defn- ^{:stratum 1} item-below-colon-comment?
  "True when a `- item` line comes after the first colon-bearing comment
   in `block`."
  [block]
  (->> block
       (drop-while #(not (re-find colon-comment-re %)))
       rest
       (some #(re-find list-item-re %))
       boolean))

(defn- ^{:stratum 1} bare-key
  "Key name when `line` is a bare `key:` line, else nil."
  [line]
  (second (re-matches bare-key-re line)))

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

(defn ^{:stratum 2} nested-block-keys
  "Keys written as a nested YAML mapping, which this grammar cannot
   represent.

   A key qualifies when it has no inline value (a trailing comment counts
   as none) and its next line that is neither blank nor comment-only is
   an indented `child: value`. Returns a vector of key names in file
   order, [] when none qualify."
  [frontmatter-str]
  (->> (frontmatter-lines frontmatter-str)
       (remove noise-line?)
       adjacent-pairs
       (keep #(key-over-child blank-valued-key-re indented-kv-re %))
       vec))

(defn ^{:stratum 2} commented-list-keys
  "Keys written as `key: # comment` over a `- item` list.

   A key qualifies when its only value is a comment and its next line
   that is neither blank nor comment-only is a `- item` line, indented or
   flush-left. Returns a vector of key names in file order, [] when none
   qualify."
  [frontmatter-str]
  (->> (frontmatter-lines frontmatter-str)
       (remove noise-line?)
       adjacent-pairs
       (keep #(key-over-child commented-key-re list-item-re %))
       vec))

(defn ^{:stratum 2} interrupted-list-keys
  "Keys whose `- item` list has items below a comment line containing a
   colon.

   A key qualifies when it is a bare `key:` line and, in the run of
   blank, comment-only and `- item` lines directly below it, a `- item`
   line comes after the first colon-bearing comment. A colon-bearing
   comment below the last item does not qualify. Returns a vector of key
   names in file order, [] when none qualify."
  [frontmatter-str]
  (vec (for [[opener & below] (line-tails (frontmatter-lines frontmatter-str))
             :let  [k     (bare-key opener)
                    block (take-while list-block-line? below)]
             :when (and k (item-below-colon-comment? block))]
         k)))

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
