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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.naming
  "Filename/slug/title derivation, part of the reference implementation
   for the MDC-to-Pack Rule Field Mapping acceptance spec (Sections 1
   and 2 of work/designs/mdc-to-pack-field-mapping.edn): deriving
   :rule/id and :rule/title from an .mdc filepath and its frontmatter.

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210: slice 4/4, final, of a stratum-lint SL003 split train — see
   `mdc-to-pack-mapping-test.dewey` for slice 1 and the full train
   rationale). `rule-id-from-filepath` and `derive-title` both call
   `slug-from-filename`; `derive-title` also calls `title-from-slug`.
   That's a 2-layer same-file chain, so this file was already within
   budget on its own — moving it out shrinks the parent file's bulk
   and (together with slice 6, which moves `compile-rule`) removes
   `derive-title`/`rule-id-from-filepath` from the parent's deepest
   chain.

   Layer 0: slug-from-filename, title-from-slug
   Layer 1: rule-id-from-filepath, derive-title"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} slug-from-filename
  "Strip .mdc extension from filename (not full path).
   'foundations/stratified-design.mdc' → 'stratified-design'"
  [filepath]
  (let [filename (last (str/split filepath #"/"))]
    (str/replace filename #"\.mdc$" "")))

(defn ^{:stratum 0} title-from-slug
  "Derive title from slug: hyphens → spaces, title-case each word.
   'pre-commit-discipline' → 'Pre Commit Discipline'"
  [slug]
  (->> (str/split slug #"-")
       (map str/capitalize)
       (str/join " ")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} rule-id-from-filepath
  "Derive :rule/id from .mdc filepath.
   Prefix slug with :std/ namespace.
   'foundations/stratified-design.mdc' → :std/stratified-design"
  [filepath]
  (keyword "std" (slug-from-filename filepath)))

(defn ^{:stratum 1} derive-title
  "Get :rule/title from frontmatter description or fallback to slug."
  [frontmatter filepath]
  (let [desc (get frontmatter "description")]
    (if (and desc (not (str/blank? desc)))
      desc
      (title-from-slug (slug-from-filename filepath)))))
