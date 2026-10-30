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
(ns ai.miniforge.policy-pack.mdc-compiler.frontmatter-values
  "MDC frontmatter scalar-value parsing: quote-stripping, backslash-escape
   expansion for literal-text fields, and the YAML-like value grammar
   (booleans, quoted strings, inline arrays, bare strings). Split out of
   `ai.miniforge.policy-pack.mdc-compiler`
   (rule 210: the parent namespace measured 9 real layers, max 3; each
   concern moves to its own layer-coherent file — same approach as the
   dag-orchestrator split, miniforge#1485, and the workflow-runner
   split, miniforge#1662)."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} strip-quotes
  "Strip surrounding single or double quotes from a string."
  [s]
  (cond
    (and (str/starts-with? s "\"") (str/ends-with? s "\"")) (subs s 1 (dec (count s)))
    (and (str/starts-with? s "'")  (str/ends-with? s "'"))  (subs s 1 (dec (count s)))
    :else s))

(def ^{:stratum 0} ^:private escape-sequence-re
  "One backslash plus the character it escapes. `.` excludes a newline, so a
   value's trailing lone backslash simply does not match and survives as-is."
  #"\\(.)")

(defn- ^{:stratum 0} expand-escape
  "Expand one `escape-sequence-re` match into the character it denotes.

   `\\n`, `\\t` and `\\\\` are the recognized escapes. Every other sequence —
   `\\s`, `\\d`, `\\b`, `\\(`, `\\.` — is returned verbatim, including its
   backslash, so a value that is regex source passes through byte-for-byte."
  [[match escaped-char]]
  (case escaped-char
    "n"  "\n"
    "t"  "\t"
    "\\" "\\"
    match))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unescape-literal-text
  "Expand `\\n`, `\\t` and `\\\\` escapes in a frontmatter value that is literal
   text rather than regex source.

   MDC frontmatter has no block-scalar (`|`) syntax, so a multi-line literal can
   only be written as a single-line quoted scalar carrying `\\n`. Without this,
   `remediation.template` reached the patcher as a backslash followed by an `n`
   and a prepend wrote the whole header on one line — which, for 810
   `project/header-copyright`, still contained both strings the rule's own
   negative detection looks for, so the corrupted file then scanned as compliant
   (miniforge-standards#129).

   Call this ONLY on fields whose value is literal text. `detection.pattern`,
   `detection.emailPattern` and `remediation.replacement` must NOT be routed
   through it: the first two are regex source and the third is a regex
   replacement string with `$1`-style group references. Expanding escapes there
   would rewrite the pattern a rule matches on. The field-by-field decision
   lives with the callers that know which key they are reading
   (`mdc-compiler.rule-config`), not here — this namespace parses values and
   has no field context.

   A single left-to-right scan gives escape-the-escape for free: in `\\\\n` the
   leading `\\\\` is consumed as one escape, so the trailing `n` stays an ordinary
   character and the result is a literal backslash followed by `n`.

   Arguments:
   - s - Parsed frontmatter value; non-strings (booleans, vectors) pass through

   Returns:
   - The string with recognized escapes expanded, or `s` unchanged."
  [s]
  (if (string? s)
    (str/replace s escape-sequence-re expand-escape)
    s))

(defn- ^{:stratum 1} parse-inline-array
  "Parse a JSON/YAML-style inline array string: [\"a\", \"b\", c].

   Arguments:
   - s - String starting with [ and ending with ]

   Returns:
   - Vector of parsed string items, or empty vector for empty array."
  [s]
  (let [inner (-> s
                  (str/replace #"^\[" "")
                  (str/replace #"\]$" "")
                  str/trim)]
    (if (str/blank? inner)
      []
      (->> (str/split inner #",")
           (mapv (comp strip-quotes str/trim))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-frontmatter-value
  "Parse a single YAML-like frontmatter value string into a Clojure value.

   Handles: booleans, quoted strings, inline arrays, bare strings."
  [raw]
  (let [v (str/trim raw)]
    (cond
      (= v "true")               true
      (= v "false")              false
      (str/starts-with? v "[")   (parse-inline-array v)
      :else                      (strip-quotes v))))
