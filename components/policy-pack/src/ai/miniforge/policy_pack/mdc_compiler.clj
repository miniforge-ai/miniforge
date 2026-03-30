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

   Layer 0: MDC parsing (frontmatter + body extraction)
   Layer 1: Field mapping transforms (Dewey→phases, slug→id, behavior extraction)
   Layer 2: Rule compilation (single-file + pack assembly)

   Related:
     work/designs/mdc-to-pack-field-mapping.edn — authoritative field mapping spec
     components/policy-pack/src/.../schema.clj  — Rule schema (target format)
     .standards/                                 — source .mdc files (input)"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; MDC parsing — frontmatter and body extraction

(defn- parse-inline-array
  "Parse a JSON/YAML-style inline array string: [\"a\", \"b\", c].

   Arguments:
   - s - String starting with [ and ending with ]

   Returns:
   - Vector of parsed string items, or empty vector for empty array."
  [s]
  (let [inner (-> s
                  (str/replace #"^\[" "")
                  (str/replace #"\]$" "")
                  str/trim)]
    (if (str/blank? inner)
      []
      (->> (str/split inner #",")
           (mapv (fn [item]
                   (let [trimmed (str/trim item)]
                     (cond
                       ;; Double-quoted
                       (and (str/starts-with? trimmed "\"") (str/ends-with? trimmed "\""))
                       (subs trimmed 1 (dec (count trimmed)))
                       ;; Single-quoted
                       (and (str/starts-with? trimmed "'") (str/ends-with? trimmed "'"))
                       (subs trimmed 1 (dec (count trimmed)))
                       ;; Bare value
                       :else trimmed))))))))

(defn- parse-frontmatter-value
  "Parse a single YAML-like frontmatter value string into a Clojure value.

   Handles: booleans, quoted strings, inline arrays, bare strings."
  [raw]
  (let [v (str/trim raw)]
    (cond
      (= v "true")  true
      (= v "false") false
      ;; Double-quoted string
      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (subs v 1 (dec (count v)))
      ;; Single-quoted string
      (and (str/starts-with? v "'") (str/ends-with? v "'"))
      (subs v 1 (dec (count v)))
      ;; Inline array
      (str/starts-with? v "[")
      (parse-inline-array v)
      ;; Bare string
      :else v)))

(defn- parse-frontmatter
  "Parse YAML-like frontmatter text into a string-keyed map.

   Handles simple key: value pairs, inline arrays [a, b], and
   multi-line list items (- value) under a key.

   Arguments:
   - frontmatter-str - Raw text between --- delimiters

   Returns:
   - Map of {string-key parsed-value}, or empty map."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    {}
    (let [lines (str/split-lines frontmatter-str)]
      (loop [remaining lines
             current-key nil
             acc {}]
        (if (empty? remaining)
          acc
          (let [line (first remaining)
                trimmed (str/trim line)]
            (cond
              ;; Blank line — skip
              (str/blank? trimmed)
              (recur (rest remaining) current-key acc)

              ;; Multi-line list item under current key
              (and current-key (str/starts-with? trimmed "- "))
              (let [item-raw (str/trim (subs trimmed 2))
                    item (cond
                           (and (str/starts-with? item-raw "\"")
                                (str/ends-with? item-raw "\""))
                           (subs item-raw 1 (dec (count item-raw)))

                           (and (str/starts-with? item-raw "'")
                                (str/ends-with? item-raw "'"))
                           (subs item-raw 1 (dec (count item-raw)))

                           :else item-raw)]
                (recur (rest remaining) current-key
                       (update acc current-key (fnil conj []) item)))

              ;; Key: value pair
              (str/includes? trimmed ":")
              (let [idx (str/index-of trimmed ":")
                    k (str/trim (subs trimmed 0 idx))
                    v (str/trim (subs trimmed (inc idx)))]
                (if (str/blank? v)
                  ;; Value on subsequent lines (multi-line list)
                  (recur (rest remaining) k (assoc acc k []))
                  ;; Value on same line
                  (recur (rest remaining) nil
                         (assoc acc k (parse-frontmatter-value v)))))

              ;; Unrecognized line — skip
              :else
              (recur (rest remaining) current-key acc))))))))

(defn split-frontmatter
  "Split MDC content into frontmatter string and body string.

   Expects --- delimited YAML frontmatter at the top of the file.

   Arguments:
   - content - Full .mdc file content

   Returns:
   - {:frontmatter <string> :body <string>}"
  [content]
  (let [trimmed (str/trim (str content))]
    (if (str/starts-with? trimmed "---")
      (let [after-open (subs trimmed 3)
            close-idx (str/index-of after-open "---")]
        (if close-idx
          {:frontmatter (str/trim (subs after-open 0 close-idx))
           :body        (str/trim (subs after-open (+ close-idx 3)))}
          {:frontmatter ""
           :body        trimmed}))
      {:frontmatter ""
       :body        trimmed})))

(defn parse-mdc
  "Parse an MDC file into its structured components.

   Arguments:
   - content - Full .mdc file content string

   Returns:
   - {:frontmatter {string-key value} :body string}"
  [content]
  (let [{:keys [frontmatter body]} (split-frontmatter content)]
    {:frontmatter (parse-frontmatter frontmatter)
     :body        body}))

;------------------------------------------------------------------------------ Layer 1
;; Field mapping transforms

;; ── Dewey code → phases ─────────────────────────────────────────────────────
;;
;; This table lives ONLY in the compiler. After compilation, phases are plain
;; keyword sets on the rule map. The runtime product never sees Dewey codes.

(def ^:private dewey-ranges
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

(def ^:private default-phases
  "Fallback phases when Dewey code is outside defined ranges or unparseable."
  #{:implement :review})

(defn- find-dewey-range
  "Find the dewey-ranges entry for a given Dewey code string.
   Returns the matching range map, or nil."
  [dewey-str]
  (when-let [code (try (Integer/parseInt (str/trim (str dewey-str)))
                       (catch Exception _ nil))]
    (some (fn [{:keys [lo hi] :as entry}]
            (when (and (<= lo code) (<= code hi))
              entry))
          dewey-ranges)))

(defn dewey->phases
  "Map a Dewey code string to a set of applicable workflow phases.

   Arguments:
   - dewey-str - Dewey code string, e.g. \"001\", \"210\", \"900\"

   Returns:
   - Set of phase keywords, e.g. #{:plan :implement :review}"
  [dewey-str]
  (if-let [entry (find-dewey-range dewey-str)]
    (:phases entry)
    default-phases))

(defn dewey->category-id
  "Map a Dewey code to its category ID string.

   Arguments:
   - dewey-str - Dewey code string

   Returns:
   - Category ID string (e.g. \"foundations\", \"languages\"), or \"other\"."
  [dewey-str]
  (if-let [entry (find-dewey-range dewey-str)]
    (:id entry)
    "other"))

(defn dewey->category-label
  "Map a Dewey code to its human-readable category label.

   Arguments:
   - dewey-str - Dewey code string

   Returns:
   - Category label string, or \"Other\"."
  [dewey-str]
  (if-let [entry (find-dewey-range dewey-str)]
    (:label entry)
    "Other"))

;; ── Filename slug → rule ID ─────────────────────────────────────────────────

(defn slug->rule-id
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

(defn- slug->title
  "Derive a fallback title from a filename slug.
   Replaces hyphens with spaces and title-cases each word.

   \"pre-commit-discipline\" → \"Pre Commit Discipline\""
  [slug]
  (->> (str/split slug #"-")
       (map str/capitalize)
       (str/join " ")))

;; ── Agent behavior extraction ───────────────────────────────────────────────

(def ^:private behavior-condensation-target 500)

(defn- extract-agent-behavior-section
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

(defn- extract-first-paragraph
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

(defn- condense-to-length
  "Condense text to approximately target-length characters.

   For bullet lists: keeps the first 3 bullets.
   For prose: keeps complete sentences up to the limit.
   Falls back to hard truncation if no natural break found.

   Arguments:
   - text          - Source text
   - target-length - Maximum character count

   Returns:
   - Condensed string."
  [text target-length]
  (if (<= (count text) target-length)
    text
    (let [lines (str/split-lines text)
          bullet-lines (filterv #(re-matches #"^\s*[-*]\s+.*" %) lines)]
      (if (>= (count bullet-lines) 2)
        ;; Bullet list — take first 3 bullets
        (let [result (str/trim (str/join "\n" (take 3 bullet-lines)))]
          (if (<= (count result) target-length)
            result
            (subs result 0 target-length)))
        ;; Prose — split on sentence boundaries
        (let [single-line (str/replace text #"\n+" " ")
              sentences (str/split single-line #"(?<=[.!?])\s+")
              condensed (reduce
                         (fn [acc sentence]
                           (let [candidate (if (str/blank? acc)
                                            sentence
                                            (str acc " " sentence))]
                             (if (> (count candidate) target-length)
                               (reduced acc)
                               candidate)))
                         ""
                         sentences)]
          (if (str/blank? condensed)
            (subs text 0 (min (count text) target-length))
            condensed))))))

(defn extract-agent-behavior
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

;; ── Globs normalization ─────────────────────────────────────────────────────

(defn- normalize-globs
  "Normalize the globs frontmatter value to a vector of strings.
   Handles: nil, string, vector, other sequential."
  [globs-raw]
  (cond
    (nil? globs-raw)        nil
    (string? globs-raw)     [globs-raw]
    (sequential? globs-raw) (vec globs-raw)
    :else                   nil))

;------------------------------------------------------------------------------ Layer 2
;; Rule compilation

(defn mdc->rule
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

          ;; ── Identity ────────────────────────────────────────────────────
          slug         (str/replace filename #"\.mdc$" "")
          rule-id      (keyword "std" slug)
          dewey        (get frontmatter "dewey" "000")
          title        (or (get frontmatter "description")
                           (slug->title slug))
          description  (str "Engineering standard (" dewey "): " title)
          always-apply (true? (get frontmatter "alwaysApply"))

          ;; ── Applicability ───────────────────────────────────────────────
          phases       (dewey->phases dewey)
          globs        (normalize-globs (get frontmatter "globs"))
          applies-to   (cond-> {:phases phases}
                         (seq globs) (assoc :file-globs globs))

          ;; ── Content extraction ──────────────────────────────────────────
          body-trimmed    (when-not (str/blank? body) (str/trim body))
          agent-behavior  (extract-agent-behavior body)

          ;; ── Build rule map ──────────────────────────────────────────────
          rule (cond->
                 {:rule/id          rule-id
                  :rule/title       title
                  :rule/description description
                  :rule/severity    :info
                  :rule/category    dewey
                  :rule/applies-to  applies-to
                  :rule/detection   {:type :custom}
                  :rule/enforcement {:action  :audit
                                    :message (str "Standard: " title)}}

                 always-apply
                 (assoc :rule/always-inject? true)

                 body-trimmed
                 (assoc :rule/knowledge-content body-trimmed)

                 agent-behavior
                 (assoc :rule/agent-behavior agent-behavior))]

      {:success? true :rule rule})

    (catch Exception e
      {:success? false
       :error    (.getMessage e)
       :filename filename})))

;; ── Category builder ────────────────────────────────────────────────────────

(defn- build-categories
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

(defn- format-pack-version
  "Generate a DateVer version string (YYYY.MM) from the current date."
  []
  (let [date (java.time.LocalDate/now)]
    (format "%d.%02d" (.getYear date) (.getMonthValue date))))

;; ── Pack assembly ───────────────────────────────────────────────────────────

(defn- validate-no-duplicate-slugs
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

(defn compile-standards-pack
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
      {:success? false
       :errors   [(str "Standards directory not found: " standards-dir)]}

      (let [mdc-files (->> (file-seq dir)
                           (filter #(.isFile %))
                           (filter #(str/ends-with? (.getName %) ".mdc"))
                           (sort-by #(.getName %)))

            ;; Check for duplicate slugs (hard error)
            dup-errors (validate-no-duplicate-slugs mdc-files)]

        (if dup-errors
          {:success? false :errors dup-errors}

          (let [results (mapv (fn [f]
                               (let [content (slurp f)
                                     filename (.getName f)]
                                 (assoc (mdc->rule filename content)
                                        :source-path (str f))))
                             mdc-files)

                successes (filterv :success? results)
                failures  (filterv (complement :success?) results)
                rules     (mapv :rule successes)
                sorted-rules (vec (sort-by (comp str :rule/id) rules))

                categories (build-categories sorted-rules)
                now        (java.time.Instant/now)

                pack {:pack/id          "miniforge/standards"
                      :pack/name        "Miniforge Engineering Standards"
                      :pack/version     (format-pack-version)
                      :pack/description "Shared engineering standards compiled from .standards/ MDC files"
                      :pack/author      "miniforge.ai"
                      :pack/license     "Apache-2.0"
                      :pack/trust-level :trusted
                      :pack/authority   :authority/instruction
                      :pack/categories  categories
                      :pack/rules       sorted-rules
                      :pack/created-at  now
                      :pack/updated-at  now}

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

            {:success?       true
             :pack           pack
             :warnings       warnings
             :compiled-count (count successes)
             :failed-count   (count failures)}))))));

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
