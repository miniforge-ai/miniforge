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

(ns ai.miniforge.compliance-scanner.named-constants
  "Named-constants locator (Dewey 006) — deterministic MEASUREMENT tool.

   Locates magic NUMERIC literals in Clojure source: numbers whose value is
   not a structural sentinel. It is a repeatable, LLM-free replacement for the
   semantic judge when counting/locating the named-constants backlog. It does
   NOT fix (extraction needs a name + intent docstring — a judgment call), and
   it is a LOCATOR, not a gate: it deliberately over-reports (it does not model
   the rule's every semantic exemption — math identities, self-documenting
   keywords), so its output is a triage list.

   A hand character-lexer tracks string / comment / char-literal state so a
   number inside a string or a `;` comment is never counted — only numeric
   tokens in code positions. Structured-string magic literals (paths, format
   placeholders) are out of scope here; the judge still covers those.

   Layer 0: char-lexer + numeric classification
   Layer 1: file + repo scan"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def rule-id :std/named-constants)
(def rule-category "006")

(def ^:private exempt-values
  "Numeric values that are structural sentinels, never magic: loop / index
   bounds (-1, 0, 1) and the doubling identity (2). Mirrors the rule's
   \"larger than 2 or smaller than -1\" threshold."
  #{-1.0 0.0 1.0 2.0})

;------------------------------------------------------------------------------ Layer 0
;; Numeric classification

(def ^:private numeric-token-pattern
  "A token that is a Clojure numeric literal: integers, decimals (with optional
   exponent and M/N suffix), leading-dot decimals, hex, radix, and ratios."
  #"[-+]?(?:\d+\.?\d*(?:[eE][-+]?\d+)?[MN]?|\.\d+(?:[eE][-+]?\d+)?M?|0[xX][0-9a-fA-F]+|\d+r[0-9a-zA-Z]+|\d+/\d+)")

(defn- numeric-token? [tok]
  (boolean (re-matches numeric-token-pattern tok)))

(defn- magic-numeric?
  "True when `tok` is a numeric literal whose value is not a structural
   sentinel. Hex / radix / ratio literals (which Double/parseDouble cannot
   read) are treated as magic — they carry meaning worth naming."
  [tok]
  (and (numeric-token? tok)
       (let [t (str/replace tok #"[MN]$" "")]
         (try
           (not (contains? exempt-values (Double/parseDouble t)))
           (catch Exception _ true)))))

(def ^:private token-char?
  "Characters that may appear inside a symbol / number token. A token ends at
   whitespace or a structural delimiter."
  (complement #{\space \tab \newline \return \, \( \) \[ \] \{ \} \" \; \' \` \~ \@ \^}))

(defn- skip-string
  "Given `content` and the index just AFTER an opening quote, return
   `[end-index end-line end-col]` positioned just after the closing quote,
   counting newlines so later line numbers stay correct."
  [^String content start line col]
  (let [n (count content)]
    (loop [j start, l line, cl col]
      (cond
        (>= j n) [j l cl]
        (= (.charAt content j) \\) (recur (+ j 2) l (+ cl 2))
        (= (.charAt content j) \newline) (recur (inc j) (inc l) 1)
        (= (.charAt content j) \") [(inc j) l (inc cl)]
        :else (recur (inc j) l (inc cl))))))

(defn scan-numeric-tokens
  "Char-lex `content`, returning `[{:line :col :token}]` for every numeric
   literal token in a CODE position (outside strings, comments, char-literals)."
  [^String content]
  (let [n (count content)]
    (loop [i 0, line 1, col 1, acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (let [c (.charAt content i)]
          (cond
            (= c \newline) (recur (inc i) (inc line) 1 acc)

            ;; line comment — skip to end of line
            (= c \;)
            (let [eol (let [j (str/index-of content "\n" i)] (or j n))]
              (recur eol line (+ col (- eol i)) acc))

            ;; char literal \x — skip the backslash and the next char
            (= c \\)
            (recur (min n (+ i 2)) line (+ col 2) acc)

            ;; string literal — skip to the closing unescaped quote
            (= c \")
            (let [[j l cl] (skip-string content (inc i) line (inc col))]
              (recur j l cl acc))

            (not (token-char? c)) (recur (inc i) line (inc col) acc)

            ;; start of a token — consume the maximal token run
            :else
            (let [end (loop [j i] (if (and (< j n) (token-char? (.charAt content j))) (recur (inc j)) j))
                  tok (subs content i end)]
              (recur end line (+ col (- end i))
                     (if (magic-numeric? tok)
                       (conj! acc {:line line :col col :token tok})
                       acc)))))))))

;------------------------------------------------------------------------------ Layer 1
;; File + repo scan

(defn analyze-content
  "Return violation maps for magic numeric literals in `content`.
   `rel` is the repo-relative path used in the record."
  [rel ^String content]
  (mapv (fn [{:keys [line col token]}]
          {:rule/id  rule-id
           :rule/category rule-category
           :file     rel
           :line     line
           :column   col
           :current  token
           :kind     :magic-numeric})
        (scan-numeric-tokens content)))

(defn- normalize-separators [^String p] (str/replace p "\\" "/"))

(defn- target-file? [rel]
  (and (str/ends-with? rel ".clj")
       (or (re-find #"^components/[^/]+/src/" rel)
           (re-find #"^bases/[^/]+/src/" rel))))

(defn- list-target-files [repo-root]
  (let [root (io/file repo-root)
        root-len (inc (count (.getAbsolutePath root)))]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (map (fn [^java.io.File f]
                (normalize-separators (subs (.getAbsolutePath f) root-len))))
         (filter target-file?)
         vec)))

(defn scan-repo
  "Scan `repo-root` for magic numeric literals. Returns
   {:violations [...] :files-scanned N :rule/id :std/named-constants
    :counts {:magic-numeric N}}. Pure locator: no writes, no throw, no exit."
  ([repo-root] (scan-repo repo-root nil))
  ([repo-root changed-files]
   (let [files (cond->> (list-target-files repo-root)
                 changed-files (filterv (set changed-files)))
         violations (vec (mapcat (fn [rel]
                                   (analyze-content rel (slurp (io/file repo-root rel))))
                                 files))]
     {:violations    violations
      :files-scanned (count files)
      :rule/id       rule-id
      :counts        {:magic-numeric (count violations)}})))
