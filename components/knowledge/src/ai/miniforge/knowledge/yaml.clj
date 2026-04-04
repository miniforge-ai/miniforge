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

   Provides a simplified YAML parser for basic frontmatter structures.
   Handles key-value pairs, arrays, and list items."
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Pure parsing helpers

(defn parse-array-value
  "Parse array-style value [item1, item2] into vector.
   Returns parsed vector or original string on failure."
  [v]
  (try
    (edn/read-string v)
    (catch Exception _
      ;; If EDN parsing fails, split by comma
      (vec (map str/trim (str/split (str/replace v #"[\[\]]" "") #","))))))

(defn parse-scalar-value
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

(defn parse-value
  "Parse a YAML value, handling arrays and scalars."
  [v]
  (if (str/starts-with? v "[")
    (parse-array-value v)
    (parse-scalar-value v)))

(defn parse-key-value-line
  "Parse a key: value line into [key value] pair.
   Returns [key nil] for key with no value, or nil if line doesn't match pattern."
  [line]
  (when-let [[_ k v] (re-find #"^(\w+):\s*(.*)$" line)]
    (if (str/blank? v)
      [(keyword k) nil]
      [(keyword k) (parse-value v)])))

(defn parse-list-item
  "Parse a list item line (e.g., '  - item').
   Returns the item value or nil if not a list item."
  [line]
  (when (str/starts-with? line "  - ")
    (str/trim (subs line 4))))

(defn add-to-collection
  "Add a value to an existing collection field.
   Creates vector if field doesn't exist, appends to existing vector."
  [existing value]
  (cond
    (vector? existing) (conj existing value)
    (nil? existing) [value]
    :else [existing value]))

;------------------------------------------------------------------------------ Layer 1
;; Stateful accumulation (using reduce instead of atom)

(defn process-yaml-line
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

(defn parse-yaml-frontmatter
  "Parse YAML frontmatter into EDN map.
   Simple parser for basic YAML - handles:
   - key: value
   - key: [list, items]
   - Lists with hyphens

   Note: This is a simplified YAML parser. For complex YAML,
   consider adding a proper YAML library."
  [yaml-str]
  (let [lines (str/split-lines yaml-str)]
    (reduce process-yaml-line {} lines)))

(defn split-frontmatter
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
