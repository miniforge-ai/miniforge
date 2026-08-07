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
(ns ai.miniforge.compliance-scanner.named-constants-lexer
  "Char-lexer + numeric-literal classification, split out of
   `named-constants` (rule 210: a sixth real layer there is the signal
   to split it).

   A hand character-lexer tracks string / comment / char-literal state so a
   number inside a string or a `;` comment is never counted — only numeric
   tokens in code positions.

   Layer 0: Numeric-token pattern, token-char predicate, string-skipping,
            exempt-value set
   Layer 1: Magic-numeric? classification
   Layer 2: Content char-lexer scan"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private exempt-values
  "Numeric values that are structural sentinels, never magic: loop / index
   bounds (-1, 0, 1) and the doubling identity (2). Mirrors the rule's
   \"larger than 2 or smaller than -1\" threshold."
  #{-1.0 0.0 1.0 2.0})

;; Numeric classification
(def ^{:stratum 0} ^:private numeric-token-pattern
  "A token that is a Clojure numeric literal: integers, decimals (with optional
   exponent and M/N suffix), leading-dot decimals, hex, radix, and ratios."
  #"[-+]?(?:\d+\.?\d*(?:[eE][-+]?\d+)?[MN]?|\.\d+(?:[eE][-+]?\d+)?M?|0[xX][0-9a-fA-F]+|\d+r[0-9a-zA-Z]+|\d+/\d+)")

(def ^{:stratum 0} ^:private token-char?
  "Characters that may appear inside a symbol / number token. A token ends at
   whitespace or a structural delimiter."
  (complement #{\space \tab \newline \return \, \( \) \[ \] \{ \} \" \; \' \` \~ \@ \^}))

(defn- ^{:stratum 0} skip-string
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} magic-numeric?
  "True when `tok` is a numeric literal whose value is not a structural
   sentinel. Hex / radix / ratio literals (which Double/parseDouble cannot
   read) are treated as magic — they carry meaning worth naming."
  [tok]
  (and (boolean (re-matches numeric-token-pattern tok))
       (let [t (str/replace tok #"[MN]$" "")]
         (try
           (not (contains? exempt-values (Double/parseDouble t)))
           (catch Exception _ true)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-numeric-tokens
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

            ;; char literal — skip the backslash AND the whole char name, so a
            ;; unicode/octal literal's digits (e.g. u0030, o101) aren't
            ;; re-tokenized as a number. A named/unicode/octal literal is a run
            ;; of alphanumerics; a punctuation literal is a single char.
            (= c \\)
            (let [j (inc i)]
              (if (and (< j n) (Character/isLetterOrDigit (.charAt content j)))
                (let [end (loop [k j] (if (and (< k n) (Character/isLetterOrDigit (.charAt content k)))
                                        (recur (inc k)) k))]
                  (recur end line (+ col (- end i)) acc))
                (recur (min n (+ i 2)) line (+ col 2) acc)))

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
