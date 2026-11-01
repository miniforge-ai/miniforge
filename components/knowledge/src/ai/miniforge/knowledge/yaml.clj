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
(ns ai.miniforge.knowledge.yaml
  "YAML frontmatter parsing for markdown files.

   `parse-yaml-frontmatter` reads frontmatter with a YAML parser
   (clj-yaml), so block scalars (`description: |`), nested maps
   (`intent:` with `type:` / `scope:` under it) and block lists keep
   their values. Two compatibility paths keep inputs the earlier line
   parser accepted working:

   - A top-level `key: [...]` line whose value reads as an EDN vector of
     keywords, strings, numbers or booleans (e.g. `tags: [:auth :security]`,
     which is not valid YAML) keeps its EDN value.
   - Frontmatter that is not a valid YAML mapping falls back to the line
     parser (flat `key: value` pairs, `[a, b]` arrays, `  - item` lists).
     Cursor-style `.mdc` rules need this: they often carry unquoted globs
     such as `globs: **/*.{md,mdx,html}`, which YAML rejects."
  (:require
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

;; Pure parsing helpers
(defn ^{:stratum 0} parse-array-value
  "Parse array-style value [item1, item2] into vector.
   Returns parsed vector or original string on failure."
  [v]
  (try
    (edn/read-string v)
    (catch Exception _
      ;; If EDN parsing fails, split by comma
      (vec (map str/trim (str/split (str/replace v #"[\[\]]" "") #","))))))

(defn ^{:stratum 0} parse-scalar-value
  "Parse a scalar YAML value into appropriate Clojure type.
   Handles: booleans, numbers, and strings (with quote removal)."
  [v]
  (cond
    ;; Boolean
    (= v "true") true
    (= v "false") false

    ;; Number
    (re-matches #"\d+" v)
    (parse-long v)

    ;; String (remove quotes if present)
    :else
    (str/replace v #"^[\"']|[\"']$" "")))

(defn ^{:stratum 0} parse-list-item
  "Parse a list item line (e.g., '  - item').
   Returns the item value or nil if not a list item."
  [line]
  (when (str/starts-with? line "  - ")
    (str/trim (subs line 4))))

(defn ^{:stratum 0} add-to-collection
  "Add a value to an existing collection field.
   Creates vector if field doesn't exist, appends to existing vector."
  [existing value]
  (cond
    (vector? existing) (conj existing value)
    (nil? existing) [value]
    :else [existing value]))

(defn ^{:stratum 0} split-frontmatter
  "Split markdown content into frontmatter and body.
   Returns {:frontmatter string :body string} or nil if no frontmatter."
  [content]
  (let [lines (str/split-lines content)]
    (when (and (seq lines) (= "---" (first lines)))
      (let [end-idx (->> (rest lines)
                         (map-indexed vector)
                         (filter (fn [[_ line]] (= "---" line)))
                         first
                         first)]
        (when end-idx
          {:frontmatter (str/join "\n" (take end-idx (rest lines)))
           :body (str/join "\n" (drop (+ end-idx 2) lines))})))))

(defn ^{:stratum 0} plain-data
  "Convert clj-yaml output (ordered maps, lazy seqs) to plain maps and
   vectors, so parsed frontmatter prints and reads back as ordinary EDN."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]] [k (plain-data v)])) x)
    (sequential? x) (mapv plain-data x)
    :else           x))

(defn ^{:stratum 0} edn-vector-entry
  "Read a top-level `key: [...]` line whose value is an EDN vector of
   keywords, strings, numbers or booleans, e.g. `tags: [:auth :security]`.
   Returns [key-string vector], or nil for any other line, including YAML
   flow lists of bare words such as `[a, b]` (EDN reads those as symbols)."
  [line]
  (when-let [[_ k v] (re-matches #"([\w.-]+):\s*(\[.*\])\s*" line)]
    ;; Plain try: this component has no slingshot dep (rule 211 exemption 1).
    (let [parsed (try (edn/read-string v) (catch Exception _ nil))]
      (when (and (vector? parsed)
                 (seq parsed)
                 (every? (some-fn keyword? string? number? boolean?) parsed))
        [k parsed]))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse-value
  "Parse a YAML value, handling arrays and scalars."
  [v]
  (if (str/starts-with? v "[")
    (parse-array-value v)
    (parse-scalar-value v)))

(defn ^{:stratum 1} parse-yaml-mapping
  "Parse frontmatter text as a YAML mapping with keyword keys.

   Top-level EDN vector lines (see `edn-vector-entry`) are replaced by
   `key: []` before YAML parsing and their EDN values put back after.
   Returns a plain map ({} for empty text), or nil when the text is not a
   valid YAML mapping."
  [yaml-str]
  (let [lines     (str/split-lines yaml-str)
        entries   (mapv edn-vector-entry lines)
        yaml-text (str/join "\n" (map (fn [line [k]] (if k (str k ": []") line))
                                       lines entries))
        ;; Plain try: this component has no slingshot dep (rule 211 exemption 1).
        parsed    (try (yaml/parse-string yaml-text)
                       (catch Exception _ ::invalid))]
    (when (or (nil? parsed) (map? parsed))
      (into (plain-data (or parsed {}))
            (keep (fn [[k v]] (when k [(keyword k) v])))
            entries))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-key-value-line
  "Parse a key: value line into [key value] pair.
   Returns [key nil] for key with no value, or nil if line doesn't match pattern."
  [line]
  (when-let [[_ k v] (re-find #"^(\w+):\s*(.*)$" line)]
    (if (str/blank? v)
      [(keyword k) nil]
      [(keyword k) (parse-value v)])))

;------------------------------------------------------------------------------ Layer 3

;; Stateful accumulation (using reduce instead of atom)
(defn ^{:stratum 3} process-yaml-line
  "Process a single YAML line and update accumulator.
   Returns updated accumulator map."
  [acc line]
  (cond
    ;; Empty line - skip
    (str/blank? line)
    acc

    ;; Key-value line (including key: with no value)
    (re-find #"^(\w+):\s*(.*)$" line)
    (let [[k v] (parse-key-value-line line)]
      (assoc acc k v))

    ;; List item - append to last key
    (and (str/starts-with? line "  - ")
         (not-empty acc))
    (let [last-key (last (keys acc))
          item (parse-list-item line)]
      (update acc last-key add-to-collection item))

    ;; Unknown line format - skip
    :else
    acc))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} parse-yaml-frontmatter
  "Parse YAML frontmatter into a map with keyword keys.

   Uses a YAML parser, so block scalars, nested maps and block lists keep
   their values. Falls back to the line parser when the text is not a
   valid YAML mapping (see the namespace docstring)."
  [yaml-str]
  (or (parse-yaml-mapping yaml-str)
      (reduce process-yaml-line {} (str/split-lines yaml-str))))
