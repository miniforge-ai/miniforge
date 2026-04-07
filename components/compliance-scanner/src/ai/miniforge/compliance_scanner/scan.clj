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
   Layer 1: Per-rule scanning
   Layer 2: Top-level scan-repo entry point"
  (:require [ai.miniforge.compliance-scanner.factory  :as factory]
            [ai.miniforge.compliance-scanner.messages :as msg]
            [ai.miniforge.policy-pack.interface       :as policy-pack]
            [ai.miniforge.repo-index.interface        :as repo-index]
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
        ep       (get rule-cfg :email-pattern)]
    (if (header-present? content pattern ep)
      []
      [(factory/->violation
        rule-id
        rule-cat
        title
        file-path
        1
        (msg/t :scan/missing-header)
        (suggest nil)
        false   ; classify phase fills this in
        "")]))) ; classify phase fills this in

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
          (try
            (str/replace-first (str matched-text) pat replacement)
            (catch Exception _ nil))))

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

(defn- filter-pack-rules
  "Filter compiled pack rules to those with content-scan detection,
   further filtered by the :rules option.
   :all and :always-apply both return all scannable rules."
  [pack-rules opts]
  (let [raw       (get opts :rules :always-apply)
        requested (if (string? raw) (keyword (subs raw 1)) raw)
        scannable (filter #(= :content-scan (get-in % [:rule/detection :type])) pack-rules)]
    (cond
      (contains? #{:all :always-apply} requested) scannable
      (keyword? requested)  (filter #(= requested (:rule/id %)) scannable)
      (set? requested)      (filter #(contains? requested (:rule/id %)) scannable)
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

;------------------------------------------------------------------------------ Layer 2
;; Top-level entry point

(defn scan-repo
  "Scan a repo for violations across all configured rules.

   Compiles the policy pack from standards-path and uses detection
   config declared in MDC frontmatter. Only rules with :content-scan
   detection type are scanned.

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with:
       :rules  - set of :rule/id keywords to run, :all, or :always-apply (default)
       :since  - git ref (optional, ignored in M1)
       :pack   - pre-compiled PackManifest (optional, bypasses compilation)

   Returns ScanResult map."
  [repo-path standards-path opts]
  (let [start-ms   (System/currentTimeMillis)
        index      (repo-index/build-index repo-path)
        rule-cfgs  (or (when-let [pack (get opts :pack)]
                         (let [rules    (get pack :pack/rules)
                               filtered (filter-pack-rules rules opts)]
                           (when (seq filtered)
                             (vec (keep pack-rule->detection-config filtered)))))
                       (load-pack-detection-configs standards-path opts)
                       [])
        all-violations (->> rule-cfgs
                            (mapcat (fn [cfg]
                                      (let [viols (scan-rule cfg index)]
                                        (if-let [rem (get cfg :remediation)]
                                          (mapv #(enrich-violation % rem) viols)
                                          viols))))
                            vec)
        end-ms     (System/currentTimeMillis)
        file-count (get index :file-count 0)
        rule-ids   (mapv :rule/id rule-cfgs)]
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
