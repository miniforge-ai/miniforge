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

(ns ai.miniforge.compliance-scanner.scan
  "Scan phase: load files, run detection, return violations.

   Layer 0: Per-file detection helpers
   Layer 0.5: Pack-driven detection config (glob matching, suggest builder)
   Layer 0.6: Diff/plan detection helpers
   Layer 1: Per-rule scanning
   Layer 2: Top-level scan-repo entry point"
  (:require [ai.miniforge.compliance-scanner.factory  :as factory]
            [ai.miniforge.compliance-scanner.messages :as msg]
            [ai.miniforge.policy-pack.interface       :as policy-pack]
            [ai.miniforge.repo-index.interface        :as repo-index]
            [clojure.java.io                          :as io]
            [clojure.string                           :as str])
  (:import [java.nio.file FileSystems Paths]))

;------------------------------------------------------------------------------ Layer 0
;; Per-file detection helpers

(defn- header-present?
  "Return true if the first 10 lines of content contain both
   the name pattern and the email pattern."
  [content name-pattern email-pattern]
  (let [first-10 (->> (str/split-lines content)
                      (take 10)
                      (str/join "\n"))]
    (and (re-find name-pattern first-10)
         (re-find email-pattern first-10))))

(defn- pattern-present?
  "Return true if pattern appears anywhere in content.
   Generic negative-mode check (not header-specific)."
  [content pattern]
  (boolean (re-find pattern content)))

(defn- positive-matches
  "Find all lines in content matching pattern.
   Returns seq of {:line int :text string :match string}."
  [pattern content]
  (->> (str/split-lines content)
       (map-indexed (fn [idx line]
                      (let [m (re-find pattern line)]
                        {:line  (inc idx)
                         :text  line
                         :match (if (string? m) m (first m))})))
       (filter :match)))

(defn- violations-for-positive-rule
  "Scan a single file with a positive-match rule (pattern = violation).
   Returns vector of Violation maps (without :auto-fixable? / :rationale —
   those are added by classify)."
  [rule-cfg file-path content]
  (let [rule-id  (get rule-cfg :rule/id)
        rule-cat (get rule-cfg :rule/category)
        title    (get rule-cfg :title)
        pattern  (get rule-cfg :pattern)
        suggest  (get rule-cfg :suggest-fn)
        matches  (positive-matches pattern content)]
    (mapv (fn [{:keys [line match]}]
            (factory/->violation
             rule-id
             rule-cat
             title
             file-path
             line
             match
             (suggest match)
             false          ; classify phase fills this in
             ""))           ; classify phase fills this in
          matches)))

(defn- violations-for-negative-rule
  "Scan a single file with a negative-match rule (absence of pattern = violation).
   Returns a vector of 0 or 1 Violation maps."
  [rule-cfg file-path content]
  (let [rule-id  (get rule-cfg :rule/id)
        rule-cat (get rule-cfg :rule/category)
        title    (get rule-cfg :title)
        pattern  (get rule-cfg :pattern)
        suggest  (get rule-cfg :suggest-fn)
        ep       (get rule-cfg :email-pattern)
        present? (if ep
                   (header-present? content pattern ep)
                   (pattern-present? content pattern))
        absence-msg (if ep
                      (msg/t :scan/missing-header)
                      (str "(missing: " title ")"))]
    (if present?
      []
      [(factory/->violation
        rule-id
        rule-cat
        title
        file-path
        1
        absence-msg
        (suggest nil)
        false
        "")])))

(defn- scan-file
  "Dispatch to the appropriate scanner for a rule config.
   Returns vector of raw (pre-classify) Violation maps."
  [rule-cfg file-path content]
  (case (get rule-cfg :detect-mode :positive)
    :positive (violations-for-positive-rule rule-cfg file-path content)
    :negative (violations-for-negative-rule rule-cfg file-path content)
    []))

;------------------------------------------------------------------------------ Layer 0.5
;; Pack-driven detection config

(defn- globs->file-pred
  "Convert a vector of glob patterns into a file-path predicate function.
   Uses java.nio.file.PathMatcher for standard glob matching."
  [globs]
  (let [fs       (FileSystems/getDefault)
        matchers (mapv #(.getPathMatcher fs (str "glob:" %)) globs)]
    (fn [path]
      (let [p (Paths/get path (into-array String []))]
        (boolean (some #(.matches ^java.nio.file.PathMatcher % p) matchers))))))

(defn- build-suggest-fn
  "Build a suggest function from pack detection and remediation config.
   Uses str/replace-first with $1/$2/$3 group references for mechanical fixes."
  [detection remediation]
  (let [replacement (get remediation :replacement)
        pattern-str (get detection :pattern)]
    (cond
      replacement
      (let [pat (re-pattern pattern-str)]
        (fn [matched-text]
          (when (and matched-text pat replacement)
            (str/replace-first matched-text pat replacement))))

      (= :prepend (get remediation :type))
      (constantly nil)

      :else
      (constantly nil))))

(defn- pack-rule->detection-config
  "Convert a compiled pack rule into the detection config format used by scan-file.
   Returns nil for rules without :content-scan detection."
  [rule]
  (let [detection   (get rule :rule/detection)
        remediation (get rule :rule/remediation)
        globs       (get-in rule [:rule/applies-to :file-globs])]
    (when (= :content-scan (get detection :type))
      (cond->
        {:rule/id       (get rule :rule/id)
         :rule/category (get rule :rule/category)
         :title         (get rule :rule/title)
         :pattern       (re-pattern (get detection :pattern))
         :file-pred     (globs->file-pred (or globs ["**/*"]))
         :suggest-fn    (build-suggest-fn detection remediation)
         :detect-mode   (get detection :mode :positive)}

        (get detection :email-pattern)
        (assoc :email-pattern (re-pattern (get detection :email-pattern)))

        remediation
        (assoc :remediation remediation)))))

(def ^:private scannable-types
  "Detection types the compliance scanner can process."
  #{:content-scan :diff-analysis :plan-output})

(def ^:private category-dewey-ranges
  "Category keyword name to Dewey code range [lo hi].
   Static data — no runtime dependency on taxonomy component."
  {"foundations"   [0 99]
   "tools"         [100 199]
   "languages"     [200 299]
   "frameworks"    [300 399]
   "testing"       [400 499]
   "operations"    [500 599]
   "documentation" [600 699]
   "workflows"     [700 799]
   "project"       [800 899]
   "meta"          [900 999]})

(defn- category-matches?
  "Check if a rule's Dewey category falls in a category selector's range.
   Uses static range lookup — no cross-layer dependency."
  [rule cat-kw]
  (let [cat-name (name cat-kw)
        rule-cat (:rule/category rule)]
    (or (= cat-name rule-cat)
        (when-let [[lo hi] (get category-dewey-ranges cat-name)]
          (try
            (let [code (Integer/parseInt (str rule-cat))]
              (and (<= lo code) (<= code hi)))
            (catch Exception _ false))))))

(defn- rule-matches-selector?
  "Check if a rule matches a selector element (keyword — rule ID or category ID)."
  [rule selector-kw]
  (or (= selector-kw (:rule/id rule))
      (category-matches? rule selector-kw)))

(defn- filter-pack-rules
  "Filter compiled pack rules by the :rules option.

   Supported selectors:
   - :all / :always-apply — all rules with scannable detection types
   - keyword — single rule ID match
   - set of keywords — matches rule IDs OR category IDs
     e.g. #{:std/clojure :mf.cat/workflows}

   Only rules with scannable detection types (content-scan, diff-analysis,
   plan-output) pass through."
  [pack-rules opts]
  (let [raw       (get opts :rules :always-apply)
        requested (if (string? raw) (keyword (subs raw 1)) raw)
        scannable (filter #(contains? scannable-types
                                      (get-in % [:rule/detection :type]))
                          pack-rules)]
    (cond
      (contains? #{:all :always-apply} requested) scannable
      (keyword? requested)  (filter #(= requested (:rule/id %)) scannable)
      (set? requested)      (filter (fn [rule]
                                      (some #(rule-matches-selector? rule %) requested))
                                    scannable)
      :else                 scannable)))

(defn- load-pack-detection-configs
  "Load compiled pack from standards-path and convert rules to detection configs.
   Returns vector of detection config maps, or nil if pack unavailable."
  [standards-path opts]
  (try
    (let [result (policy-pack/compile-standards-pack standards-path)]
      (when (:success? result)
        (let [rules     (get-in result [:pack :pack/rules])
              filtered  (filter-pack-rules rules opts)
              configs   (keep pack-rule->detection-config filtered)]
          (when (seq configs)
            (vec configs)))))
    (catch Exception _ nil)))

(defn- enrich-violation
  "Attach remediation metadata from the pack rule to a violation map.
   Downstream classify and execute phases use these fields."
  [violation remediation]
  (cond-> violation
    (get remediation :type)
    (assoc :remediation-type (get remediation :type))

    (get remediation :template)
    (assoc :remediation-template (get remediation :template))

    (some? (get remediation :auto-fixable-default))
    (assoc :auto-fixable-default (get remediation :auto-fixable-default))

    (get remediation :exclude-contexts)
    (assoc :exclude-contexts (get remediation :exclude-contexts))))

;------------------------------------------------------------------------------ Layer 0.6
;; Diff/plan detection and incremental mode helpers

(defn- git-diff
  "Run git diff and return the output string."
  [repo-path since-ref]
  (try
    (let [pb (ProcessBuilder. ^java.util.List
              ["git" "diff" (str since-ref "..HEAD")])
          _  (.directory pb (io/file repo-path))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (.waitFor proc)
      (when-not (str/blank? out) out))
    (catch Exception _ nil)))

(defn- git-diff-name-only
  "Run git diff --name-only and return set of changed file paths."
  [repo-path since-ref]
  (try
    (let [pb (ProcessBuilder. ^java.util.List
              ["git" "diff" "--name-only" (str since-ref "..HEAD")])
          _  (.directory pb (io/file repo-path))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (.waitFor proc)
      (when-not (str/blank? out)
        (set (str/split-lines (str/trim out)))))
    (catch Exception _ nil)))

(defn- scan-diff-rule
  "Scan a git diff for violations using a diff-analysis rule.
   Returns vector of Violation maps."
  [rule-cfg diff-content]
  (let [rule-id  (:rule/id rule-cfg)
        rule-cat (:rule/category rule-cfg)
        title    (:rule/title rule-cfg)
        pattern  (re-pattern (get-in rule-cfg [:rule/detection :pattern]))
        matches  (positive-matches pattern diff-content)]
    (mapv (fn [{:keys [line match]}]
            (factory/->violation
             rule-id rule-cat title
             "<diff>"     ; file is the entire diff
             line match
             nil          ; no suggestion for diff violations
             false ""))
          matches)))

(defn- scan-plan-rule
  "Scan plan output for violations using a plan-output rule.
   Uses the policy-pack detect-violation dispatcher for resource-level analysis.
   Returns vector of Violation maps."
  [rule-cfg plan-output]
  (let [rule-id  (:rule/id rule-cfg)
        rule-cat (:rule/category rule-cfg)
        title    (:rule/title rule-cfg)
        ;; Build rule and artifact maps for the detection dispatcher
        rule-map {:rule/id         rule-id
                  :rule/detection  (get rule-cfg :rule/detection)
                  :rule/applies-to (get rule-cfg :rule/applies-to {})
                  :rule/enforcement {:action :warn :message title}}
        artifact {:artifact/content plan-output}
        context  {:terraform-plan plan-output}
        result   (policy-pack/detect-violation rule-map artifact context)]
    (if result
      (let [resources (:resource-violations result [])]
        (if (seq resources)
          (mapv (fn [{:keys [resource action line]}]
                  (factory/->violation
                   rule-id rule-cat title
                   (str resource)
                   0
                   (str action " " resource " — " (str/trim (or line "")))
                   nil false ""))
                resources)
          [(factory/->violation
            rule-id rule-cat title
            "<plan-output>" 0
            (:message result)
            nil false "")]))
      [])))

;------------------------------------------------------------------------------ Layer 1
;; Per-rule scanning

(defn- scan-file-record
  "Load and scan a single file record against a rule config.
   Returns vector of raw Violation maps, or [] if the file cannot be read."
  [rule-cfg index file-record]
  (let [path    (:path file-record)
        content (try
                  (slurp (str (:repo-root index) "/" path))
                  (catch Exception _ nil))]
    (if content
      (scan-file rule-cfg path content)
      [])))

(defn- scan-rule
  "Scan the entire repo for a single rule.
   Returns vector of raw Violation maps."
  [rule-cfg index]
  (let [file-pred      (get rule-cfg :file-pred (constantly false))
        matching-files (repo-index/find-files index #(file-pred (:path %)))]
    (->> matching-files
         (mapcat (partial scan-file-record rule-cfg index))
         vec)))

(defn- get-rule-configs
  "Resolve detection configs from opts :pack or by compiling from standards-path.
   Returns vector of detection config maps, or nil if none found."
  [opts standards-path]
  (or (when-let [pack (get opts :pack)]
        (let [rules    (get pack :pack/rules)
              filtered (filter-pack-rules rules opts)]
          (when (seq filtered)
            (vec (keep pack-rule->detection-config filtered)))))
      (load-pack-detection-configs standards-path opts)))

;------------------------------------------------------------------------------ Layer 2
;; Top-level entry point

(defn- scan-changed-files
  "Scan only files present in the changed-files set for a single rule config."
  [cfg index changed-files]
  (let [file-pred (get cfg :file-pred (constantly false))
        matching  (->> (repo-index/find-files index identity)
                       (filter (fn [f]
                                 (and (contains? changed-files (:path f))
                                      (file-pred (:path f))))))]
    (mapcat #(scan-file-record cfg index %) matching)))

(defn- scan-and-enrich
  "Scan a single rule config (full or incremental) and enrich violations."
  [cfg index changed-files]
  (let [viols (if changed-files
                (scan-changed-files cfg index changed-files)
                (scan-rule cfg index))
        rem   (get cfg :remediation)]
    (if rem
      (mapv #(enrich-violation % rem) viols)
      viols)))

(defn- scan-content-rules
  "Scan content-scan rules across the repo index.
   When changed-files is non-nil, limits scanning to those files only."
  [content-cfgs index changed-files]
  (->> content-cfgs
       (mapcat #(scan-and-enrich % index changed-files))
       vec))

(defn- scan-diff-rules
  "Scan diff-analysis rules against a git diff."
  [diff-rules diff-content]
  (when diff-content
    (->> diff-rules
         (mapcat #(scan-diff-rule % diff-content))
         vec)))

(defn- scan-plan-rules
  "Scan plan-output rules against terraform plan output."
  [plan-rules plan-output]
  (when plan-output
    (->> plan-rules
         (mapcat #(scan-plan-rule % plan-output))
         vec)))

(defn scan-repo
  "Scan a repo for violations across all configured rules.

   Supports multiple detection types:
   - :content-scan — regex matching against file content
   - :diff-analysis — regex matching against git diff (requires :since)
   - :plan-output — terraform plan resource analysis (requires :terraform-plan)

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with:
       :rules           - selector: :all, :always-apply, keyword, or set of keywords
       :since           - git ref for incremental mode + diff-analysis
       :pack            - pre-compiled PackManifest (optional, bypasses compilation)
       :terraform-plan  - plan output string for :plan-output rules

   Returns ScanResult map."
  [repo-path standards-path opts]
  (let [start-ms   (System/currentTimeMillis)
        index      (repo-index/build-index repo-path)
        since-ref  (get opts :since)
        plan-text  (get opts :terraform-plan)

        ;; Resolve all rule configs (now including diff/plan rules)
        all-cfgs   (or (get-rule-configs opts standards-path) [])

        ;; Content-scan rules use the detection config format (from pack-rule->detection-config)
        content-cfgs (filterv #(contains? % :detect-mode) all-cfgs)

        ;; Diff/plan rules use pack rule format directly
        pack-rules   (when-let [pack (or (get opts :pack)
                                         (when-let [r (policy-pack/compile-standards-pack standards-path)]
                                           (when (:success? r) (:pack r))))]
                       (filter-pack-rules (:pack/rules pack) opts))
        diff-rules   (filterv #(= :diff-analysis (get-in % [:rule/detection :type])) (or pack-rules []))
        plan-rules   (filterv #(= :plan-output (get-in % [:rule/detection :type])) (or pack-rules []))

        ;; Incremental: get changed files if :since is provided
        changed-files (when since-ref (git-diff-name-only repo-path since-ref))
        diff-content  (when (and since-ref (seq diff-rules))
                        (git-diff repo-path since-ref))

        ;; Run all detection types
        content-violations (scan-content-rules content-cfgs index changed-files)
        diff-violations    (scan-diff-rules diff-rules diff-content)
        plan-violations    (scan-plan-rules plan-rules plan-text)

        all-violations (vec (concat content-violations
                                    (or diff-violations [])
                                    (or plan-violations [])))
        end-ms     (System/currentTimeMillis)
        file-count (get index :file-count 0)
        rule-ids   (into (mapv :rule/id content-cfgs)
                         (map :rule/id (concat diff-rules plan-rules)))]
    (factory/->scan-result
     all-violations
     rule-ids
     file-count
     (- end-ms start-ms))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def result (scan-repo "." ".standards" {:rules :all}))
  (count (:violations result))
  (:files-scanned result)
  (:rules-scanned result)

  :leave-this-here)
