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
(ns ai.miniforge.policy-pack.mdc-compiler
  "Compile .standards/*.mdc files into policy-pack rules.

   This namespace is the ONLY place in the codebase that knows about Dewey
   codes, MDC frontmatter format, and YAML-like parsing. After compilation
   the product works exclusively with standard pack rule maps.

   Designed to be called by the ETL task (bb standards:pack) at build time.

   Slice 2/6 of a rule 210 split train (SL003: this namespace measured
   9 real layers, max 3 — same approach as the dag-orchestrator split,
   miniforge#1485, and the workflow-runner split, miniforge#1662).
   Slice 1 (miniforge#1729) moved the frontmatter scalar-value grammar
   to `mdc-compiler.frontmatter-values`; this slice moves the
   remaining frontmatter line-grammar (splitting, key/value and list
   parsing) to `mdc-compiler.frontmatter`. The file still measures 7
   real layers — the frontmatter chain was never the sole bottleneck;
   the surviving critical path is now condense-to-length →
   extract-agent-behavior → mdc->rule → compile-standards-pack, which
   the next slices target directly.

   Layer 0: Parsing/config primitives — group-dotted-keys,
     build-exclude-context, build-detection-config, dewey-ranges,
     default-phases, slug->rule-id/title, agent-behavior
     paragraph/bullet helpers, format-pack-version,
     validate-no-duplicate-slugs, parse-mdc
   Layer 1: build-remediation-config, find-dewey-range, bullet-line?,
     condense-prose, export-canonical-taxonomy
   Layer 2: dewey->phases/category-id/category-label, condense-bullets
   Layer 3: condense-to-length, build-categories
   Layer 4: extract-agent-behavior
   Layer 5: mdc->rule
   Layer 6: compile-standards-pack

   Related:
     work/designs/mdc-to-pack-field-mapping.edn — authoritative field mapping spec
     components/policy-pack/src/.../schema.clj  — Rule schema (target format)
     .standards/                                 — source .mdc files (input)"
  (:require
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.policy-pack.mdc-compiler.frontmatter :as frontmatter]
   [ai.miniforge.policy-pack.schema-types :as schema-types]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Detection and remediation config builders
(defn- ^{:stratum 0} group-dotted-keys
  "Group dot-notation keys into nested maps.
   e.g., {\"detection.mode\" \"positive\" \"detection.pattern\" \"...\"} →
         {\"detection\" {\"mode\" \"positive\" \"pattern\" \"...\"}}"
  [fm]
  (reduce-kv
   (fn [acc k v]
     (if-let [dot-idx (str/index-of k ".")]
       (let [prefix (subs k 0 dot-idx)
             suffix (subs k (inc dot-idx))]
         (update acc prefix (fnil assoc {}) suffix v))
       (assoc acc k v)))
   {}
   fm))

(defn- ^{:stratum 0} build-exclude-context
  "Convert path/current exclude lists from MDC remediation config into
   ExcludeContext maps for the RuleRemediation schema."
  [remediation-map]
  (let [path-contains    (get remediation-map "excludePathContains")
        current-contains (get remediation-map "excludeCurrentContains")]
    (cond-> []
      (seq path-contains)
      (into (mapv (fn [p] {:path-contains p}) path-contains))

      (seq current-contains)
      (conj {:current-contains (vec current-contains)}))))

(defn- ^{:stratum 0} build-detection-config
  "Build :rule/detection map from grouped frontmatter detection block.
   Returns {:type :content-scan ...} when detection config is present,
   {:type :custom} otherwise."
  [detection-map]
  (if (and detection-map (get detection-map "pattern"))
    (cond-> {:type    :content-scan
             :pattern (get detection-map "pattern")
             :mode    (keyword (get detection-map "mode" "positive"))}
      (get detection-map "emailPattern")
      (assoc :email-pattern (get detection-map "emailPattern")))
    {:type :custom}))

(def ^{:stratum 0} ^:private valid-enforcement-actions
  "Enforcement actions a rule may opt into via frontmatter `enforcement.action`,
   derived from the ONE canonical source (`schema-types/enforcement-actions`) so the
   compiler can never accept an action the rule schema rejects. `:hard-halt`
   makes a pack-derived gate BLOCK; the rest are non-blocking. An unrecognized
   value falls back to the alwaysApply default — a typo can't produce garbage."
  (set schema-types/enforcement-actions))

;; Field mapping transforms
;; ── Dewey code → phases ─────────────────────────────────────────────────────
;;
;; This table lives ONLY in the compiler. After compilation, phases are plain
;; keyword sets on the rule map. The runtime product never sees Dewey codes.
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

;; ── Filename slug → rule ID ─────────────────────────────────────────────────
(defn ^{:stratum 0} slug->rule-id
  "Convert an MDC filename to a namespaced rule ID keyword.

   Directory path is NOT included — only the bare filename matters.
   All filenames in .standards/ MUST be unique across subdirectories.

   Arguments:
   - filename - Bare filename, e.g. \"stratified-design.mdc\"

   Returns:
   - Keyword, e.g. :std/stratified-design"
  [filename]
  (let [slug (str/replace filename #"\.mdc$" "")]
    (keyword "std" slug)))

(defn- ^{:stratum 0} slug->title
  "Derive a fallback title from a filename slug.
   Replaces hyphens with spaces and title-cases each word.

   \"pre-commit-discipline\" → \"Pre Commit Discipline\""
  [slug]
  (->> (str/split slug #"-")
       (map str/capitalize)
       (str/join " ")))

;; ── Agent behavior extraction ───────────────────────────────────────────────
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

(defn- ^{:stratum 0} keep-whole-sentences
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

;; ── Globs normalization ─────────────────────────────────────────────────────
(defn- ^{:stratum 0} normalize-globs
  "Normalize the globs frontmatter value to a vector of strings.
   Handles: nil, string, vector, other sequential."
  [globs-raw]
  (cond
    (nil? globs-raw)        nil
    (string? globs-raw)     [globs-raw]
    (sequential? globs-raw) (vec globs-raw)
    :else                   nil))

(defn- ^{:stratum 0} format-pack-version
  "Generate a DateVer version string (YYYY.MM) from the current date."
  []
  (let [date (java.time.LocalDate/now)]
    (format "%d.%02d" (.getYear date) (.getMonthValue date))))

;; ── Pack assembly ───────────────────────────────────────────────────────────
(defn- ^{:stratum 0} validate-no-duplicate-slugs
  "Check for duplicate filename slugs across directories.
   Returns nil if no duplicates, or a vector of error strings."
  [mdc-files]
  (let [slugs (map #(str/replace (.getName %) #"\.mdc$" "") mdc-files)
        slug-counts (frequencies slugs)
        duplicates (filterv (fn [[_ cnt]] (> cnt 1)) slug-counts)]
    (when (seq duplicates)
      [(str "Duplicate filename slugs detected: "
            (str/join ", " (map first duplicates))
            ". Rule IDs must be unique across all subdirectories.")])))

(defn ^{:stratum 0} parse-mdc
  "Parse an MDC file into its structured components.

   Arguments:
   - content - Full .mdc file content string

   Returns:
   - {:frontmatter {string-key value} :body string}"
  [content]
  (let [{:keys [frontmatter body]} (frontmatter/split-frontmatter content)]
    {:frontmatter (frontmatter/parse-frontmatter frontmatter)
     :body        body}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} build-remediation-config
  "Build :rule/remediation map from grouped frontmatter remediation block.
   Returns nil if no remediation config is present."
  [remediation-map]
  (when (and remediation-map (get remediation-map "strategy"))
    (let [excludes (build-exclude-context remediation-map)]
      (cond-> {:strategy (keyword (get remediation-map "strategy"))}
        (get remediation-map "type")
        (assoc :type (keyword (get remediation-map "type")))

        (get remediation-map "replacement")
        (assoc :replacement (get remediation-map "replacement"))

        (get remediation-map "template")
        (assoc :template (get remediation-map "template"))

        (some? (get remediation-map "autoFixable"))
        (assoc :auto-fixable-default (boolean (get remediation-map "autoFixable")))

        (seq excludes)
        (assoc :exclude-contexts excludes)))))

(defn- ^{:stratum 1} find-dewey-range
  "Find the dewey-ranges entry for a given Dewey code string.
   Returns the matching range map, or nil."
  [dewey-str]
  (when-let [code (coerce/safe-parse-int (str/trim (str dewey-str)))]
    (some (fn [{:keys [lo hi] :as entry}]
            (when (and (<= lo code) (<= code hi))
              entry))
          dewey-ranges)))

(defn- ^{:stratum 1} bullet-line?
  "True when `line` is a markdown list-item — `-`/`*` bullet OR
   numbered prefix."
  [line]
  (boolean (re-matches bullet-line-pattern line)))

(defn- ^{:stratum 1} condense-prose
  "Keep complete sentences up to target-length, falling back to hard truncation."
  [text target-length]
  (keep-whole-sentences (str/replace text #"\n+" " ") target-length))

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

(defn- ^{:stratum 2} condense-bullets
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

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} condense-to-length
  "Condense text to approximately target-length characters.
   Bullet lists (including numbered): keeps first 3 bullets.
   Prose: keeps complete sentences."
  [text target-length]
  (if (<= (count text) target-length)
    text
    (let [lines   (str/split-lines text)
          bullets (filterv bullet-line? lines)]
      (if (>= (count bullets) 2)
        (condense-bullets lines target-length)
        (condense-prose text target-length)))))

;; ── Category builder ────────────────────────────────────────────────────────
(defn- ^{:stratum 3} build-categories
  "Build PackCategory entries from compiled rules.

   Groups rules by Dewey-range-derived category and produces
   {:category/id :category/name :category/rules} entries.

   Arguments:
   - rules - Vector of compiled rule maps

   Returns:
   - Sorted vector of PackCategory maps."
  [rules]
  (let [by-cat (group-by (fn [rule]
                           (dewey->category-id (:rule/category rule)))
                         rules)]
    (->> by-cat
         (map (fn [[cat-id cat-rules]]
                {:category/id    cat-id
                 :category/name  (dewey->category-label
                                  (:rule/category (first cat-rules)))
                 :category/rules (mapv :rule/id cat-rules)}))
         (sort-by :category/id)
         vec)))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} extract-agent-behavior
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

;------------------------------------------------------------------------------ Layer 5

;; Rule compilation
(defn ^{:stratum 5} mdc->rule
  "Compile a single MDC file into a policy-pack rule map.

   Implements the field mapping from the design spec
   (work/designs/mdc-to-pack-field-mapping.edn).

   Arguments:
   - filename - Bare .mdc filename (e.g. \"stratified-design.mdc\")
   - content  - Full MDC file content string

   Returns:
   - {:success? true  :rule <rule-map>}
   - {:success? false :error <message> :filename <string>}"
  [filename content]
  (try
    (let [{:keys [frontmatter body]} (parse-mdc content)
          fm           (group-dotted-keys frontmatter)

          ;; ── Identity ────────────────────────────────────────────────────
          slug         (str/replace filename #"\.mdc$" "")
          rule-id      (keyword "std" slug)
          dewey        (get fm "dewey" "000")
          title        (or (get fm "description")
                           (slug->title slug))
          description  (str "Engineering standard (" dewey "): " title)
          always-apply (true? (get fm "alwaysApply"))

          ;; ── Applicability ───────────────────────────────────────────────
          phases       (dewey->phases dewey)
          globs        (normalize-globs (get fm "globs"))
          applies-to   (cond-> {:phases phases}
                         (seq globs) (assoc :file-globs globs))

          ;; ── Detection & remediation ─────────────────────────────────────
          detection    (build-detection-config (get fm "detection"))
          remediation  (build-remediation-config (get fm "remediation"))

          ;; ── Content extraction ──────────────────────────────────────────
          body-trimmed    (when-not (str/blank? body) (str/trim body))
          agent-behavior  (extract-agent-behavior body)

          ;; ── Build rule map ──────────────────────────────────────────────
          ;; Enforcement action: a rule MAY opt into a stronger action via
          ;; frontmatter `enforcement.action` — notably `hard-halt`, which
          ;; makes a pack-derived gate (:policy-verify/:policy-review) BLOCK
          ;; rather than only warn. Without an override, alwaysApply rules warn
          ;; and advisory rules audit (both non-blocking).
          enforcement-fm (get fm "enforcement")
          fm-action      (some-> enforcement-fm (get "action") str str/trim
                                 not-empty keyword)
          action         (cond
                           (contains? valid-enforcement-actions fm-action) fm-action
                           always-apply :warn
                           :else :audit)
          ;; A rule that opts into blocking is at least :high (canonical scale).
          severity       (cond
                           (= action :hard-halt) :high
                           always-apply :high
                           :else :low)
          enforcement-msg (or (some-> enforcement-fm (get "message") str str/trim
                                      not-empty)
                              (str "Standard: " title))

          rule (cond->
                 {:rule/id          rule-id
                  :rule/title       title
                  :rule/description description
                  :rule/severity    severity
                  :rule/category    dewey
                  :rule/applies-to  applies-to
                  :rule/detection   detection
                  :rule/enforcement {:action  action
                                    :message enforcement-msg}}

                 always-apply
                 (assoc :rule/always-inject? true)

                 remediation
                 (assoc :rule/remediation remediation)

                 body-trimmed
                 (assoc :rule/knowledge-content body-trimmed)

                 agent-behavior
                 (assoc :rule/agent-behavior agent-behavior))]

      (schema-validation/success :rule rule {}))

    (catch Exception e
      (merge (schema-validation/failure :rule (.getMessage e))
             {:filename filename}))))

;------------------------------------------------------------------------------ Layer 6

(defn ^{:stratum 6} compile-standards-pack
  "Compile all .mdc files from a standards directory into a pack manifest.

   Discovers all .mdc files recursively, compiles each via mdc->rule,
   assembles into a complete PackManifest.

   Fails with a clear error if duplicate filename slugs are detected.
   Logs warnings for files that fail to compile but continues with others.

   Arguments:
   - standards-dir - Path to .standards/ directory (string or File)

   Returns:
   - {:success? true  :pack <PackManifest> :warnings [...]
      :compiled-count <int> :failed-count <int>}
   - {:success? false :errors [...]}"
  [standards-dir]
  (let [dir (io/file standards-dir)]
    (if-not (.isDirectory dir)
      (schema-validation/failure-with-errors :pack [(str "Standards directory not found: " standards-dir)])

      (let [mdc-files (->> (file-seq dir)
                           (filter #(.isFile %))
                           (filter #(str/ends-with? (.getName %) ".mdc"))
                           (sort-by #(.getName %)))

            ;; Check for duplicate slugs (hard error)
            dup-errors (validate-no-duplicate-slugs mdc-files)]

        (if dup-errors
          (schema-validation/failure-with-errors :pack dup-errors)

          (let [results (mapv (fn [f]
                               (let [content (slurp f)
                                     filename (.getName f)]
                                 (assoc (mdc->rule filename content)
                                        :source-path (str f))))
                             mdc-files)

                successes (filterv schema-validation/succeeded? results)
                failures  (filterv (complement schema-validation/succeeded?) results)
                rules     (mapv :rule successes)
                sorted-rules (vec (sort-by (comp str :rule/id) rules))

                categories (build-categories sorted-rules)
                now        (java.time.Instant/now)

                pack {:pack/id           "miniforge/standards"
                      :pack/name         "Miniforge Engineering Standards"
                      :pack/version      (format-pack-version)
                      :pack/description  "Shared engineering standards compiled from .standards/ MDC files"
                      :pack/author       "miniforge.ai"
                      :pack/license      "Apache-2.0"
                      :pack/trust-level  :trusted
                      :pack/authority    :authority/instruction
                      :pack/taxonomy-ref {:taxonomy/id      :miniforge/dewey
                                          :taxonomy/min-version "1.0.0"}
                      :pack/categories   categories
                      :pack/rules        sorted-rules
                      :pack/created-at   now
                      :pack/updated-at   now}

                ;; Collect warnings
                warnings (cond-> []
                           (seq failures)
                           (into (mapv #(str "Failed to compile "
                                             (:filename %) ": " (:error %))
                                       failures))

                           ;; Warn about always-inject + empty phases (meta range)
                           (some (fn [r]
                                   (and (:rule/always-inject? r)
                                        (empty? (get-in r [:rule/applies-to :phases]))))
                                 sorted-rules)
                           (conj "Some always-inject rules have no applicable phases (Dewey 900 meta range)"))]

            (schema-validation/success :pack pack {:warnings       warnings
                                        :compiled-count (count successes)
                                        :failed-count   (count failures)})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; ── Parse MDC frontmatter ───────────────────────────────────────────────
  (parse-mdc "---\ndewey: \"001\"\ndescription: Stratified Design\nalwaysApply: true\n---\n\n# Body here")
  ;; => {:frontmatter {"dewey" "001", "description" "Stratified Design", "alwaysApply" true}
  ;;     :body "# Body here"}

  ;; ── Dewey → phases ──────────────────────────────────────────────────────
  (dewey->phases "001")  ;; => #{:plan :implement :review :verify :release}
  (dewey->phases "210")  ;; => #{:implement :review}
  (dewey->phases "900")  ;; => #{}
  (dewey->phases "xyz")  ;; => #{:implement :review}  (default fallback)

  ;; ── Slug → rule ID ──────────────────────────────────────────────────────
  (slug->rule-id "stratified-design.mdc")       ;; => :std/stratified-design
  (slug->rule-id "clojure.mdc")                 ;; => :std/clojure
  (slug->rule-id "pre-commit-discipline.mdc")   ;; => :std/pre-commit-discipline

  ;; ── Agent behavior extraction ───────────────────────────────────────────
  (extract-agent-behavior
   "# Title\n\nSome intro text.\n\n## Agent behavior\n\n- Do this first.\n- Then do that.")
  ;; => "- Do this first.\n- Then do that."

  (extract-agent-behavior
   "# Title\n\nFirst paragraph used as fallback.\n\nSecond paragraph ignored.")
  ;; => "First paragraph used as fallback."

  ;; ── Compile a single MDC file ───────────────────────────────────────────
  (mdc->rule
   "stratified-design.mdc"
   "---\ndewey: \"001\"\ndescription: Stratified Design — enforce one-way dependencies\nalwaysApply: true\n---\n\n# Stratified Design (ALWAYS)\n\nUse a DAG.\n\n## Agent behavior\n\n- Output a stratified plan before writing code.")
  ;; => {:success? true
  ;;     :rule {:rule/id :std/stratified-design
  ;;            :rule/title "Stratified Design — enforce one-way dependencies"
  ;;            :rule/description "Engineering standard (001): Stratified Design — enforce one-way dependencies"
  ;;            :rule/severity :info
  ;;            :rule/category "001"
  ;;            :rule/always-inject? true
  ;;            :rule/applies-to {:phases #{:plan :implement :review :verify :release}}
  ;;            :rule/detection {:type :custom}
  ;;            :rule/enforcement {:action :audit :message "Standard: ..."}
  ;;            :rule/agent-behavior "- Output a stratified plan before writing code."
  ;;            :rule/knowledge-content "# Stratified Design (ALWAYS)\n\nUse a DAG.\n\n## Agent behavior\n\n- Output a stratified plan before writing code."}}

  ;; ── Compile full pack from .standards/ directory ────────────────────────
  ;; (compile-standards-pack ".standards")

  :leave-this-here)
