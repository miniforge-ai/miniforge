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
(ns ai.miniforge.compliance-scanner.classify-exclusion
  "Exclusion-context matching, split out of `classify` (rule 210: pulling
   this out keeps `classify` itself at 3 real layers instead of 4).

   Layer 0: Path/content exclusion predicates
   Layer 1: Combined exclude-context match"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Context exclusion matching
(defn- ^{:stratum 0} has-path-exclusion?
  "Return true if the violation's file path matches a :path-contains exclusion."
  [violation ctx-rule]
  (when-let [p (get ctx-rule :path-contains)]
    (str/includes? (get violation :file "") p)))

(defn- ^{:stratum 0} has-content-exclusion?
  "Return true if the violation's :current text matches a :current-contains exclusion."
  [violation ctx-rule]
  (when-let [cs (get ctx-rule :current-contains)]
    (let [current (get violation :current "")]
      (if (vector? cs)
        (boolean (some #(str/includes? current %) cs))
        (str/includes? current cs)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} matches-exclude-context?
  "Return true if a violation matches an exclusion context rule.
   Exclusion contexts are declared in MDC remediation config."
  [violation ctx-rule]
  (or (has-path-exclusion? violation ctx-rule)
      (has-content-exclusion? violation ctx-rule)))
