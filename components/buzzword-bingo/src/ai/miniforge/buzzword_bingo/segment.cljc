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
(ns ai.miniforge.buzzword-bingo.segment
  "Blank the regions of a document that are not authored prose.

   Masked characters become spaces and newlines are kept, so the mask
   is the same length as the source and indices, lines and columns
   derived from it still address the source correctly."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Blanking
(defn- ^{:stratum 0} blanked [match]
  ;; A replace callback receives a bare string without capture groups and a
  ;; [whole & groups] vector with them.
  (let [text (if (vector? match) (first match) match)]
    (str/replace text #"[^\n]" " ")))

;; The non-prose catalog
(def ^{:stratum 0} non-prose-patterns
  "Regions carrying no authored prose, in application order: fenced
   blocks precede inline code so fence backticks are already blank, and
   link targets precede bare URLs so a markdown link is consumed whole.

   Lines anchor with `(?:^|\\n)` and `[\\s\\S]` stands in for DOTALL,
   because JavaScript has no inline regex modifiers."
  [;; HTML and markdown comments
   #"<!--[\s\S]*?-->"
   ;; Fenced code, opening fence through matching closing fence
   #"(?:^|\n)[ \t]*(`{3,}|~{3,})[^\n]*\n[\s\S]*?\n[ \t]*\1[^\n]*"
   ;; Fenced code left unterminated at end of document
   #"(?:^|\n)[ \t]*(?:`{3,}|~{3,})[\s\S]*$"
   ;; Inline code spans
   #"`[^`\n]*`"
   ;; Markdown link and image targets, brackets included
   #"\]\([^)\n]*\)"
   ;; Bare URLs
   #"(?:https?|ftp)://[^\s<>()\[\]\"']+"
   #"www\.[^\s<>()\[\]\"']+"
   ;; Rooted and relative filesystem paths
   #"(?:^|\s)(?:\.{1,2}/|~/|/)[\w./~@+-]+"
   ;; Bare paths carrying a file extension, e.g. components/foo/deps.edn
   #"(?:^|\s)[\w.~@-]+/[\w./~@+-]*\.[A-Za-z]{1,6}\b"
   ;; HTML tags, attributes included
   #"</?[A-Za-z][^>\n]*>"])

(def ^{:stratum 0} quotation-pattern
  "Markdown blockquote lines — prose, but somebody else's."
  #"(?:^|\n)[ \t]*>[^\n]*")

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} mask-pattern [text pattern]
  (str/replace text pattern blanked))

;------------------------------------------------------------------------------ Layer 2

;; Public masking
(defn ^{:stratum 2} prose-only
  "Return `text` with every non-prose region blanked to spaces.

   Blockquoted lines are blanked too unless `:score-quotes?` is truthy:
   quoting somebody else's marketing copy is not writing marketing
   copy, and charging the author for it penalises citation."
  ([text] (prose-only text nil))
  ([text {:keys [score-quotes?]}]
   (let [patterns (if score-quotes?
                    non-prose-patterns
                    (conj non-prose-patterns quotation-pattern))]
     (reduce mask-pattern text patterns))))

(comment
  (prose-only "Use `robust` here.\n\n```\nrobust code\n```\n\n> a robust quote")
  (prose-only "See https://example.com/seamless-integration for more."))
