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
(ns ai.miniforge.compliance-scanner.scan-pack-config
  "Pack-driven detection config conversion, split out of `scan` (rule 210:
   an eighth real layer there is the signal to split it). Converts
   compiled policy-pack rules into the detection config format `scan`
   dispatches on, and filters pack rules by the :rules selector. Rule/
   category selector matching lives in `scan-rule-selector`.

   Layer 0: Glob matching, suggest-fn building, scannable-types,
            violation enrichment
   Layer 1: Pack-rule filtering by the :rules option + detection config
            conversion"
  (:require [ai.miniforge.compliance-scanner.scan-rule-selector :as rule-selector]
            [clojure.string :as str])
  (:import [java.nio.file FileSystems Paths]))

;------------------------------------------------------------------------------ Layer 0

;; Pack-driven detection config
(defn- ^{:stratum 0} globs->file-pred
  "Convert a vector of glob patterns into a file-path predicate function.
   Uses java.nio.file.PathMatcher for standard glob matching."
  [globs]
  (let [fs       (FileSystems/getDefault)
        matchers (mapv #(.getPathMatcher fs (str "glob:" %)) globs)]
    (fn [path]
      (let [p (Paths/get path (into-array String []))]
        (boolean (some #(.matches ^java.nio.file.PathMatcher % p) matchers))))))

(defn- ^{:stratum 0} build-suggest-fn
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

(def ^{:stratum 0} ^:private scannable-types
  "Detection types the compliance scanner can process."
  #{:content-scan :diff-analysis :plan-output})

(defn ^{:stratum 0} enrich-violation
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

(defn ^{:stratum 1} pack-rule->detection-config
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

(defn ^{:stratum 1} filter-pack-rules
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
        requested (if (string? raw)
                    (let [trimmed (cond-> raw (str/starts-with? raw ":") (subs 1))]
                      (if (str/blank? trimmed) :always-apply (keyword trimmed)))
                    raw)
        scannable (filter #(contains? scannable-types
                                      (get-in % [:rule/detection :type]))
                          pack-rules)]
    (cond
      (contains? #{:all :always-apply} requested) scannable
      (keyword? requested)  (filter #(= requested (:rule/id %)) scannable)
      (set? requested)      (filter (fn [rule]
                                      (some #(rule-selector/rule-matches-selector? rule %) requested))
                                    scannable)
      :else                 scannable)))
