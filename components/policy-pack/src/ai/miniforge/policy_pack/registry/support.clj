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
(ns ai.miniforge.policy-pack.registry.support
  "Version comparison, glob matching, rule applicability, dedup, and
   signature-decoding helpers for `ai.miniforge.policy-pack.registry`.

   Split out of `registry` (rule 210: the combined namespace measured 5
   real layers, over the budget of 3) — the protocol, the stateful
   InMemoryPackRegistry, and the registry constructor stay in the
   parent namespace; the pure functions they compose live here.

   Layer 0: parse-datever, glob-matches?, decode-signature, dedupe-by-id
   Layer 1: compare-versions (over parse-datever), rule-applies? (over
     glob-matches?)
   Layer 2: latest-version (over compare-versions)"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Version comparison helpers
(defn ^{:stratum 0} parse-datever
  "Parse DateVer string (YYYY.MM.DD) into comparable vector."
  [version-str]
  (when version-str
    (try
      (mapv parse-long (str/split version-str #"\."))
      (catch Exception _
        nil))))

;; Rule applicability checking
(defn ^{:stratum 0} glob-matches?
  "Simple glob pattern matching.
   Supports * (any within segment) and ** (any path segments)."
  [pattern path]
  (let [regex-pattern (str "^"
                          (-> pattern
                              (str/replace "." "\\.")
                              (str/replace "**/" "<<<GLOBSTAR_SLASH>>>")
                              (str/replace "**" "<<<GLOBSTAR>>>")
                              (str/replace "*" "[^/]*")
                              (str/replace "<<<GLOBSTAR_SLASH>>>" "(.*/)?")
                              (str/replace "<<<GLOBSTAR>>>" ".*"))
                          "$")]
    (try
      (boolean (re-matches (re-pattern regex-pattern) path))
      (catch Exception _
        false))))

(defn ^{:stratum 0} decode-signature
  "Base64 pack signature to bytes; nil when the field is not a string or not
   base64. A pack reaching verification has not necessarily been through
   schema validation, so the type check belongs here."
  [sig-str]
  (when (string? sig-str)
    (try
      (.decode (java.util.Base64/getDecoder) ^String sig-str)
      (catch IllegalArgumentException _
        nil))))

(defn ^{:stratum 0} dedupe-by-id
  "Remove duplicate rules, keeping the last occurrence (later pack wins)."
  [rules]
  (vals (reduce (fn [acc rule]
                  (assoc acc (:rule/id rule) rule))
                {}
                rules)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compare-versions
  "Compare two DateVer version strings.
   Returns negative if a < b, 0 if equal, positive if a > b."
  [a b]
  (let [va (or (parse-datever a) [0 0 0])
        vb (or (parse-datever b) [0 0 0])]
    (compare va vb)))

(defn ^{:stratum 1} rule-applies?
  "Check if a rule applies to the given context.

   Context map:
   - :task - Task with :task/intent containing :intent/type
   - :artifact - Artifact with :artifact/path, :artifact/type
   - :repo - Repository with :repo/type
   - :phase - Current workflow phase keyword"
  [rule context]
  (let [{:keys [task-types file-globs repo-types phases]}
        (:rule/applies-to rule)]
    (and
     ;; Task type filter
     (or (nil? task-types)
         (empty? task-types)
         (contains? task-types (get-in context [:task :task/intent :intent/type])))

     ;; File glob filter
     (or (nil? file-globs)
         (empty? file-globs)
         (let [path (get-in context [:artifact :artifact/path] "")]
           (some #(glob-matches? % path) file-globs)))

     ;; Repo type filter
     (or (nil? repo-types)
         (empty? repo-types)
         (contains? repo-types (get-in context [:repo :repo/type])))

     ;; Phase filter
     (or (nil? phases)
         (empty? phases)
         (contains? phases (:phase context))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} latest-version
  "Get the latest version from a collection of version strings."
  [versions]
  (when (seq versions)
    (first (sort-by identity (comparator #(pos? (compare-versions %1 %2))) versions))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Version comparison
  (compare-versions "2026.01.22" "2026.01.15")
  ;; => 7 (positive, first is later)

  (latest-version ["2025.12.01" "2026.01.22" "2026.01.15"])
  ;; => "2026.01.22"

  ;; Glob matching
  (glob-matches? "**/*.tf" "modules/vpc/main.tf")
  ;; => true

  (glob-matches? "*.tf" "main.tf")
  ;; => true

  (glob-matches? "*.tf" "modules/main.tf")
  ;; => false

  :leave-this-here)
