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

(ns ai.miniforge.compliance-scanner.classify
  "Classify violations: add :auto-fixable? and :rationale to each violation.

   Classification is pack-driven: uses :auto-fixable-default and
   :exclude-contexts metadata enriched onto violations during scan.

   Layer 0: Context exclusion matching
   Layer 1: Top-level classify-violations entry point"
  (:require [ai.miniforge.compliance-scanner.messages :as msg]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Context exclusion matching

(defn- has-path-exclusion?
  "Return true if the violation's file path matches a :path-contains exclusion."
  [violation ctx-rule]
  (when-let [p (get ctx-rule :path-contains)]
    (str/includes? (get violation :file "") p)))

(defn- has-content-exclusion?
  "Return true if the violation's :current text matches a :current-contains exclusion."
  [violation ctx-rule]
  (when-let [cs (get ctx-rule :current-contains)]
    (let [current (get violation :current "")]
      (if (vector? cs)
        (boolean (some #(str/includes? current %) cs))
        (str/includes? current cs)))))

(defn- matches-exclude-context?
  "Return true if a violation matches an exclusion context rule.
   Exclusion contexts are declared in MDC remediation config."
  [violation ctx-rule]
  (or (has-path-exclusion? violation ctx-rule)
      (has-content-exclusion? violation ctx-rule)))

(defn- semantic-strategy?
  "Return true if the violation has a :semantic remediation strategy."
  [violation]
  (= :semantic (get-in violation [:remediation :strategy]
                       (get violation :remediation-strategy))))

(defn- classify-one
  "Classify a single violation using pack-enriched metadata.
   Uses :auto-fixable-default and :exclude-contexts from the pack rule.
   Violations with :semantic remediation strategy are always not auto-fixable."
  [violation]
  (let [auto-default (get violation :auto-fixable-default true)
        excludes     (get violation :exclude-contexts [])
        excluded?    (boolean (some #(matches-exclude-context? violation %) excludes))
        semantic?    (semantic-strategy? violation)]
    (assoc violation
           :auto-fixable? (and auto-default (not excluded?) (not semantic?))
           :rationale     (cond
                            semantic?    "Requires LLM semantic review"
                            excluded?    (msg/t :classify/json-context)
                            auto-default (msg/t :classify/literal-default)
                            :else        (msg/t :classify/datever)))))

;------------------------------------------------------------------------------ Layer 1
;; Top-level entry point

(defn classify-violations
  "Add :auto-fixable? and :rationale to each violation.
   Returns updated violation list."
  [violations]
  (mapv classify-one violations))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (classify-violations
   [{:rule/id :std/clojure :rule/category "210"
     :file    "components/foo/src/ai/miniforge/foo/core.clj"
     :current "(or (:timeout m) 5000)"}
    {:rule/id :std/clojure :rule/category "210"
     :file    "server/handler.clj"
     :current "(or (:status m) :pending)"}
    {:rule/id :std/datever :rule/category "730"
     :file    "build.clj"
     :current "1.2.3"}
    {:rule/id :std/header-copyright :rule/category "810"
     :file    "docs/guide.md"
     :current "(missing copyright header)"}])

  :leave-this-here)
