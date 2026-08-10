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
(ns ai.miniforge.policy-pack.mdc-compiler.condense
  "Text-condensation primitives shared by the MDC compiler's prose and
   bullet-list trimming: whole-sentence truncation, bullet-line
   detection, and per-shape (prose vs. bulleted) condensation to a
   target length. Split out of `ai.miniforge.policy-pack.mdc-compiler`
   (rule 210: slice 3/6 of the same split train as
   `mdc-compiler.frontmatter-values`/`mdc-compiler.frontmatter`,
   miniforge#1729/#1732 — same approach as the dag-orchestrator split,
   miniforge#1485, and the workflow-runner split, miniforge#1662).
   This chain — not the frontmatter grammar — was the parent
   namespace's real bottleneck: `condense-to-length` sat 3 layers deep
   on its own, and everything downstream (`extract-agent-behavior`,
   `mdc->rule`, `compile-standards-pack`) rode on top of that depth."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} keep-whole-sentences
  "Largest prefix of `text` made of whole sentences (split on `.!?` +
   whitespace) that fits within `target-length`, rejoined with single
   spaces. Falls back to a hard character cut ONLY when the first
   sentence alone already overflows — so a normal directive never ends
   mid-word."
  [text target-length]
  (let [condensed (reduce (fn [acc s]
                            (let [candidate (if (str/blank? acc) s (str acc " " s))]
                              (if (> (count candidate) target-length) (reduced acc) candidate)))
                          ""
                          (str/split text #"(?<=[.!?])\s+"))]
    (if (str/blank? condensed)
      (subs text 0 (min (count text) target-length))
      condensed)))

(def ^{:stratum 0} ^:private bullet-line-pattern
  "Markdown list-item line: leading whitespace, then either `-` / `*`
   or a numbered prefix like `1.` / `42.`, then a space. Numbered
   prefixes are recognized so MDC authors can write `1. … 2. …` lists
   in `## Agent behavior` sections without the compiler silently
   falling through to prose-mode condensation and truncating
   mid-sentence (regression observed on the dewey-211
   `clojure-exception-handling` rule, copilot review on
   miniforge#765)."
  #"^\s*(?:[-*]|\d+\.)\s+.*")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} bullet-line?
  "True when `line` is a markdown list-item — `-`/`*` bullet OR
   numbered prefix."
  [line]
  (boolean (re-matches bullet-line-pattern line)))

(defn ^{:stratum 1} condense-prose
  "Keep complete sentences up to target-length, falling back to hard truncation."
  [text target-length]
  (keep-whole-sentences (str/replace text #"\n+" " ") target-length))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} condense-bullets
  "Keep the first 3 bullets. If the joined result exceeds target-length,
   trim to whole sentences so the directive never ends mid-word (a raw
   char cut here once shipped a truncated `named-constants` directive
   ending \"The on\" — copilot review on miniforge#1302)."
  [lines target-length]
  (let [bullets (filterv bullet-line? lines)
        result  (str/trim (str/join "\n" (take 3 bullets)))]
    (if (<= (count result) target-length)
      result
      (keep-whole-sentences result target-length))))
