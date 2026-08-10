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
(ns ai.miniforge.policy-pack.mdc-compiler.agent-behavior
  "Extraction of a concise agent-behavior directive from an MDC body:
   prefers a '## Agent behavior' section, falls back to the first
   non-heading paragraph, condensed to a prompt-injection-friendly
   length via `mdc-compiler.condense`. Split out of
   `ai.miniforge.policy-pack.mdc-compiler` (rule 210: slice 5/6 of the
   same split train as `mdc-compiler.frontmatter-values`/
   `mdc-compiler.frontmatter`/`mdc-compiler.condense`/`mdc-compiler.dewey`,
   miniforge#1729/#1732/#1733/#1740 — same approach as the
   dag-orchestrator split, miniforge#1485, and the workflow-runner
   split, miniforge#1662). One of two independent chains feeding
   `mdc->rule` (the other being the rule-config builders, slice 6);
   both must move before the parent namespace drops to budget."
  (:require
   [ai.miniforge.policy-pack.mdc-compiler.condense :as condense]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private behavior-condensation-target 500)

(defn- ^{:stratum 0} extract-agent-behavior-section
  "Extract content from a \"## Agent behavior\" section in the MDC body.

   Scans for a heading matching /^## Agent behavior/i, extracts everything
   from that heading to the next ## heading or end-of-file, strips the
   heading line itself.

   Returns the section content string, or nil if no such heading exists."
  [body]
  (let [lines (str/split-lines body)
        heading-idx (first
                     (keep-indexed
                      (fn [i line]
                        (when (re-matches #"(?i)^##\s+Agent\s+behavior\s*$"
                                          (str/trim line))
                          i))
                      lines))]
    (when heading-idx
      (let [after-heading (drop (inc heading-idx) lines)
            section-lines (take-while
                           (fn [line]
                             (not (re-matches #"^##\s+.*" (str/trim line))))
                           after-heading)
            content (str/trim (str/join "\n" section-lines))]
        (when-not (str/blank? content)
          content)))))

(defn- ^{:stratum 0} extract-first-paragraph
  "Extract the first non-heading paragraph from the MDC body.

   Skips any leading # headings and blank lines, then takes text
   until the next blank line.

   Returns the paragraph string, or nil."
  [body]
  (let [lines (str/split-lines body)
        content-lines (drop-while
                       (fn [line]
                         (let [trimmed (str/trim line)]
                           (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))))
                       lines)
        paragraph-lines (take-while
                         (fn [line] (not (str/blank? (str/trim line))))
                         content-lines)
        content (str/trim (str/join "\n" paragraph-lines))]
    (when-not (str/blank? content)
      content)))

(defn- ^{:stratum 0} condense-to-length
  "Condense text to approximately target-length characters.
   Bullet lists (including numbered): keeps first 3 bullets.
   Prose: keeps complete sentences."
  [text target-length]
  (if (<= (count text) target-length)
    text
    (let [lines   (str/split-lines text)
          bullets (filterv condense/bullet-line? lines)]
      (if (>= (count bullets) 2)
        (condense/condense-bullets lines target-length)
        (condense/condense-prose text target-length)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} extract-agent-behavior
  "Extract a concise agent behavior directive from an MDC body.

   Priority 1: If the body contains a '## Agent behavior' section,
               extract and condense its content.
   Priority 2: If no such section, use the first non-heading paragraph.

   Result is condensed to ~500 chars for prompt injection.

   Arguments:
   - body - MDC body text (everything after frontmatter)

   Returns:
   - Behavior string, or nil if no meaningful content."
  [body]
  (when-not (str/blank? body)
    (let [section (extract-agent-behavior-section body)
          content (or section (extract-first-paragraph body))]
      (when content
        (condense-to-length content behavior-condensation-target)))))
