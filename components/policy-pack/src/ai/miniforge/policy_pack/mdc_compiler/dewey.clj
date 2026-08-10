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
(ns ai.miniforge.policy-pack.mdc-compiler.dewey
  "Dewey-decimal-inspired category table and its lookups: range
   definitions, phase/category/label mapping, and the N4 taxonomy
   export. This table lives ONLY here — after compilation, phases are
   plain keyword sets on the rule map, and the runtime product never
   sees Dewey codes. Split out of `ai.miniforge.policy-pack.mdc-compiler`
   (rule 210: slice 4/6 of the same split train as
   `mdc-compiler.frontmatter-values`/`mdc-compiler.frontmatter`/
   `mdc-compiler.condense`, miniforge#1729/#1732/#1733 — same approach
   as the dag-orchestrator split, miniforge#1485, and the
   workflow-runner split, miniforge#1662). This chain was the parent
   namespace's bottleneck after slice 3: `find-dewey-range` fed
   `dewey->phases`/`category-id`/`category-label`, which in turn fed
   `build-categories`/`mdc->rule` and then `compile-standards-pack`."
  (:require
   [ai.miniforge.coerce.interface :as coerce]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; ── Dewey code → phases ─────────────────────────────────────────────────────
(def ^{:stratum 0} ^:private dewey-ranges
  "Dewey ranges with category metadata and applicable phase sets.
   Each entry: {:lo <int> :hi <int> :id <string> :label <string> :phases <set>}"
  [{:lo 0   :hi 99  :id "foundations"   :label "Foundations & Core Principles"     :phases #{:plan :implement :review :verify :release}}
   {:lo 100 :hi 199 :id "tools"         :label "Development Environment & Tools"   :phases #{:implement :review}}
   {:lo 200 :hi 299 :id "languages"     :label "Languages"                         :phases #{:implement :review}}
   {:lo 300 :hi 399 :id "frameworks"    :label "Frameworks & Platforms"             :phases #{:plan :implement :review}}
   {:lo 400 :hi 499 :id "testing"       :label "Testing & Quality"                 :phases #{:implement :verify}}
   {:lo 500 :hi 599 :id "operations"    :label "Operations & Infrastructure"       :phases #{:implement :review}}
   {:lo 600 :hi 699 :id "documentation" :label "Documentation"                     :phases #{:implement :review}}
   {:lo 700 :hi 799 :id "workflows"     :label "Workflows & Processes"             :phases #{:plan :implement :review :verify :release}}
   {:lo 800 :hi 899 :id "project"       :label "Project-Specific"                  :phases #{:implement :review}}
   {:lo 900 :hi 999 :id "meta"          :label "Meta & Templates"                  :phases #{}}])

(def ^{:stratum 0} ^:private default-phases
  "Fallback phases when Dewey code is outside defined ranges or unparseable."
  #{:implement :review})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} find-dewey-range
  "Find the dewey-ranges entry for a given Dewey code string.
   Returns the matching range map, or nil."
  [dewey-str]
  (when-let [code (coerce/safe-parse-int (str/trim (str dewey-str)))]
    (some (fn [{:keys [lo hi] :as entry}]
            (when (and (<= lo code) (<= code hi))
              entry))
          dewey-ranges)))

;; Canonical taxonomy export
(defn ^{:stratum 1} export-canonical-taxonomy
  "Export the compiler's dewey-ranges as a first-class Taxonomy artifact.

   This bridges the compiler's internal category table to the N4 four-artifact
   model. The exported taxonomy is the authoritative source of truth; the
   bundled EDN resource at resources/taxonomies/miniforge-dewey-1.0.0.edn
   should match this output.

   Returns:
   - A valid Taxonomy map per taxonomy/Taxonomy schema."
  []
  {:taxonomy/id      :miniforge/dewey
   :taxonomy/version "1.0.0"
   :taxonomy/title   "Miniforge Dewey Taxonomy"
   :taxonomy/description
   "Dewey-decimal-inspired category tree for miniforge engineering standards.
    Ten top-level ranges (000-999) covering foundations, tools, languages,
    frameworks, testing, operations, documentation, workflows, project, and meta."
   :taxonomy/categories
   (mapv (fn [{:keys [lo id label]}]
           {:category/id    (keyword "mf.cat" id)
            :category/code  (format "%03d-%03d" lo (+ lo 99))
            :category/title label
            :category/order lo})
         dewey-ranges)
   :taxonomy/aliases
   (mapv (fn [{:keys [id]}]
           {:alias/name   (keyword id)
            :alias/target (keyword "mf.cat" id)})
         dewey-ranges)})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} dewey->phases
  "Map a Dewey code string to a set of applicable workflow phases.

   Arguments:
   - dewey-str - Dewey code string, e.g. \"001\", \"210\", \"900\"

   Returns:
   - Set of phase keywords, e.g. #{:plan :implement :review}"
  [dewey-str]
  (if-let [entry (find-dewey-range dewey-str)]
    (:phases entry)
    default-phases))

(defn ^{:stratum 2} dewey->category-id
  "Map a Dewey code to its category ID string.

   Arguments:
   - dewey-str - Dewey code string

   Returns:
   - Category ID string (e.g. \"foundations\", \"languages\"), or \"other\"."
  [dewey-str]
  (if-let [entry (find-dewey-range dewey-str)]
    (:id entry)
    "other"))

(defn ^{:stratum 2} dewey->category-label
  "Map a Dewey code to its human-readable category label.

   Arguments:
   - dewey-str - Dewey code string

   Returns:
   - Category label string, or \"Other\"."
  [dewey-str]
  (if-let [entry (find-dewey-range dewey-str)]
    (:label entry)
    "Other"))
