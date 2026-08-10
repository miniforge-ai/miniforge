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
(ns ai.miniforge.policy-pack.mdc-compiler.frontmatter-values
  "MDC frontmatter scalar-value parsing: quote-stripping and the
   YAML-like value grammar (booleans, quoted strings, inline arrays,
   bare strings). Split out of `ai.miniforge.policy-pack.mdc-compiler`
   (rule 210: the parent namespace measured 9 real layers, max 3; each
   concern moves to its own layer-coherent file — same approach as the
   dag-orchestrator split, miniforge#1485, and the workflow-runner
   split, miniforge#1662)."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} strip-quotes
  "Strip surrounding single or double quotes from a string."
  [s]
  (cond
    (and (str/starts-with? s "\"") (str/ends-with? s "\"")) (subs s 1 (dec (count s)))
    (and (str/starts-with? s "'")  (str/ends-with? s "'"))  (subs s 1 (dec (count s)))
    :else s))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parse-inline-array
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
           (mapv (comp strip-quotes str/trim))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-frontmatter-value
  "Parse a single YAML-like frontmatter value string into a Clojure value.

   Handles: booleans, quoted strings, inline arrays, bare strings."
  [raw]
  (let [v (str/trim raw)]
    (cond
      (= v "true")               true
      (= v "false")              false
      (str/starts-with? v "[")   (parse-inline-array v)
      :else                      (strip-quotes v))))
