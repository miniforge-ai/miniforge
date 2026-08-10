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

   Final slice (6/6) of a rule 210 split train (SL003: this namespace
   originally measured 9 real layers, max 3 — same approach as the
   dag-orchestrator split, miniforge#1485, and the workflow-runner
   split, miniforge#1662). Slices 1-2 (miniforge#1729/#1732) moved the
   frontmatter grammar out; slice 3 (miniforge#1733) moved the
   text-condensation chain out; slice 4 (miniforge#1740) moved the
   Dewey-range chain out; slice 5 (miniforge#1742) moved agent-behavior
   extraction out. This slice moves the remaining rule-config builders
   (`build-exclude-context`, `build-detection-config`,
   `valid-enforcement-actions`, `build-remediation-config`) to
   `mdc-compiler.rule-config` — the second of the two independent
   chains feeding `mdc->rule`. With both chains moved, this namespace
   is now within budget at 3 real layers.

   Layer 0: Parsing/config primitives — group-dotted-keys,
     slug->rule-id/title, normalize-globs, format-pack-version,
     validate-no-duplicate-slugs, parse-mdc, export-canonical-taxonomy,
     build-categories
   Layer 1: mdc->rule
   Layer 2: compile-standards-pack

   Related:
     work/designs/mdc-to-pack-field-mapping.edn — authoritative field mapping spec
     components/policy-pack/src/.../schema.clj  — Rule schema (target format)
     .standards/                                 — source .mdc files (input)"
  (:require
   [ai.miniforge.policy-pack.mdc-compiler.agent-behavior :as agent-behavior]
   [ai.miniforge.policy-pack.mdc-compiler.dewey :as dewey]
   [ai.miniforge.policy-pack.mdc-compiler.frontmatter :as frontmatter]
   [ai.miniforge.policy-pack.mdc-compiler.rule-config :as rule-config]
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

;; Field mapping transforms
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

;; Canonical taxonomy export — re-exported here (not just from `dewey`)
;; because `ai.miniforge.policy-pack.interface` and
;; `taxonomy_test.clj` require this namespace directly and reference
;; `mdc-compiler/export-canonical-taxonomy` by name; moving the
;; definition without this delegating var would be a public-API break
;; disguised as an internal reorganization.
(def ^{:stratum 0} export-canonical-taxonomy
  "Export the compiler's dewey-ranges as a first-class Taxonomy artifact.

   This bridges the compiler's internal category table to the N4 four-artifact
   model. The exported taxonomy is the authoritative source of truth; the
   bundled EDN resource at resources/taxonomies/miniforge-dewey-1.0.0.edn
   should match this output.

   Returns:
   - A valid Taxonomy map per taxonomy/Taxonomy schema.

   Delegates to ai.miniforge.policy-pack.mdc-compiler.dewey/export-canonical-taxonomy."
  dewey/export-canonical-taxonomy)

;; ── Category builder ────────────────────────────────────────────────────────
(defn- ^{:stratum 0} build-categories
  "Build PackCategory entries from compiled rules.

   Groups rules by Dewey-range-derived category and produces
   {:category/id :category/name :category/rules} entries.

   Arguments:
   - rules - Vector of compiled rule maps

   Returns:
   - Sorted vector of PackCategory maps."
  [rules]
  (let [by-cat (group-by (fn [rule]
                           (dewey/dewey->category-id (:rule/category rule)))
                         rules)]
    (->> by-cat
         (map (fn [[cat-id cat-rules]]
                {:category/id    cat-id
                 :category/name  (dewey/dewey->category-label
                                  (:rule/category (first cat-rules)))
                 :category/rules (mapv :rule/id cat-rules)}))
         (sort-by :category/id)
         vec)))

;------------------------------------------------------------------------------ Layer 1

;; Rule compilation
(defn ^{:stratum 1} mdc->rule
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
          phases       (dewey/dewey->phases dewey)
          globs        (normalize-globs (get fm "globs"))
          applies-to   (cond-> {:phases phases}
                         (seq globs) (assoc :file-globs globs))

          ;; ── Detection & remediation ─────────────────────────────────────
          detection    (rule-config/build-detection-config (get fm "detection"))
          remediation  (rule-config/build-remediation-config (get fm "remediation"))

          ;; ── Content extraction ──────────────────────────────────────────
          body-trimmed    (when-not (str/blank? body) (str/trim body))
          agent-behavior  (agent-behavior/extract-agent-behavior body)

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
                           (contains? rule-config/valid-enforcement-actions fm-action) fm-action
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compile-standards-pack
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
  (dewey/dewey->phases "001")  ;; => #{:plan :implement :review :verify :release}
  (dewey/dewey->phases "210")  ;; => #{:implement :review}
  (dewey/dewey->phases "900")  ;; => #{}
  (dewey/dewey->phases "xyz")  ;; => #{:implement :review}  (default fallback)

  ;; ── Slug → rule ID ──────────────────────────────────────────────────────
  (slug->rule-id "stratified-design.mdc")       ;; => :std/stratified-design
  (slug->rule-id "clojure.mdc")                 ;; => :std/clojure
  (slug->rule-id "pre-commit-discipline.mdc")   ;; => :std/pre-commit-discipline

  ;; ── Agent behavior extraction ───────────────────────────────────────────
  (agent-behavior/extract-agent-behavior
   "# Title\n\nSome intro text.\n\n## Agent behavior\n\n- Do this first.\n- Then do that.")
  ;; => "- Do this first.\n- Then do that."

  (agent-behavior/extract-agent-behavior
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
