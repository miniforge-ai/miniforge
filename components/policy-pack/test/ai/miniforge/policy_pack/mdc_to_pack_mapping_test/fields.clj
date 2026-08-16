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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.fields
  "Independent field-derivation helpers, part of the reference
   implementation for the MDC-to-Pack Rule Field Mapping acceptance
   spec (Sections 3, 5, 6, and 9 of
   work/designs/mdc-to-pack-field-mapping.edn): description text,
   :rule/always-inject?, :rule/enforcement, glob normalization, and
   ## Agent behavior / first-paragraph extraction from the MDC body.

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210: slice 2/7 of a stratum-lint SL003 split train — see
   `mdc-to-pack-mapping-test.dewey` for slice 1 and the full train
   rationale). Every function here is a single-layer leaf: none of
   them call each other or anything else in the parent namespace, so
   this file was always a layer-0 island — moving it out only removes
   its bulk from the parent file, it doesn't unwind any call chain.

   Layer 0: derive-description, normalize-globs, build-always-inject,
     build-enforcement, extract-agent-behavior-section,
     extract-first-paragraph"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} derive-description
  "Generate :rule/description from category and title.
   Format: 'Engineering standard (<category>): <title>'"
  [category title]
  (str "Engineering standard (" category "): " title))

(defn ^{:stratum 0} normalize-globs
  "Wrap string globs in a vector; pass vectors through; nil → nil."
  [globs]
  (cond
    (nil? globs)    nil
    (string? globs) [globs]
    (vector? globs) globs
    (sequential? globs) (vec globs)
    :else [globs]))

(defn ^{:stratum 0} build-always-inject
  "Derive :rule/always-inject? map fragment.
   true → {:rule/always-inject? true}, false/nil → {}"
  [always-apply]
  (if (true? always-apply)
    {:rule/always-inject? true}
    {}))

(defn ^{:stratum 0} build-enforcement
  "Build :rule/enforcement from title and alwaysApply flag."
  [title always-apply]
  {:action  (if always-apply :warn :audit)
   :message (str "Standard: " title)})

(defn ^{:stratum 0} extract-agent-behavior-section
  "Extract ## Agent behavior section content from body.
   Returns nil if section not found."
  [body]
  (when body
    (let [lines (str/split-lines body)
          start-idx (some (fn [[i line]]
                           (when (re-matches #"(?i)^##\s+Agent\s+behavior.*" line)
                             i))
                         (map-indexed vector lines))]
      (when start-idx
        (let [rest-lines (drop (inc start-idx) lines)
              content-lines (take-while
                             (fn [line]
                               (not (re-matches #"^##\s+.*" line)))
                             rest-lines)
              content (str/trim (str/join "\n" content-lines))]
          (when (not (str/blank? content))
            content))))))

(defn ^{:stratum 0} extract-first-paragraph
  "Extract first non-heading paragraph from body."
  [body]
  (when body
    (let [lines (str/split-lines body)
          ;; Skip leading headings and blank lines
          content-lines (drop-while
                         (fn [line]
                           (or (str/blank? line)
                               (str/starts-with? line "#")))
                         lines)
          ;; Take until blank line
          para-lines (take-while (complement str/blank?) content-lines)
          para (str/trim (str/join " " para-lines))]
      (when (not (str/blank? para))
        para))))
