;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.connector-linter.etl
  "Data-driven field-mapping ETL engine for linter output.

   Transforms structured linter output (JSON/EDN) into the canonical
   violation shape using declarative mapping specs loaded from EDN.
   No parser code per linter — all extraction is driven by data.

   Layer 0: Field extraction and severity mapping
   Layer 1: Format-specific record extraction
   Layer 2: Apply mapping spec to produce violations"
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Field extraction

(defn- extract-field
  "Extract a value from a record using a path spec.
   Path can be a keyword (single key) or vector (get-in path).
   Numeric elements in vectors index into sequential collections."
  [record path-spec]
  (cond
    (keyword? path-spec) (get record path-spec)
    (vector? path-spec)  (reduce (fn [acc k]
                                   (cond
                                     (nil? acc)     nil
                                     (integer? k)   (nth acc k nil)
                                     :else          (get acc k)))
                                 record
                                 path-spec)
    :else                nil))

(defn- map-severity
  "Map a raw severity value to a canonical severity keyword using the mapping's severity-map."
  [severity-map raw-value]
  (let [key (if (keyword? raw-value) (name raw-value) raw-value)]
    (get severity-map key
         (get severity-map raw-value :minor))))

(defn- record->violation
  "Transform a single parsed record into the canonical violation shape
   using the field mapping spec."
  [linter-id fields severity-map record file-override]
  (let [file     (or file-override (str (extract-field record (get fields :file))))
        line     (extract-field record (get fields :line))
        column   (extract-field record (get fields :column))
        message  (extract-field record (get fields :message))
        code     (let [c (extract-field record (get fields :code))]
                   (if (keyword? c) (name c) (str c)))
        severity (map-severity severity-map
                               (extract-field record (get fields :severity)))]
    {:rule/id       (keyword (name linter-id) (or code "lint"))
     :rule/category "lint"
     :rule/title    (str (name linter-id) "/" (or code "lint"))
     :rule/severity severity
     :file          (str file)
     :line          (or line 0)
     :column        (or column 0)
     :current       (or message "")
     :suggested     nil
     :auto-fixable? false
     :rationale     (str (name linter-id) ": " (or message ""))}))

;------------------------------------------------------------------------------ Layer 1
;; Format-specific record extraction

(defn- parse-json-safe
  "Parse JSON string, returning nil on failure."
  [s]
  (when-not (str/blank? s)
    (try (json/parse-string s true) (catch Exception _ nil))))

(defn- matches-filter?
  "Check if a record matches the mapping's filter predicate."
  [filter-spec record]
  (if filter-spec
    (= (get filter-spec :equals)
       (extract-field record (get filter-spec :path)))
    true))

(defn- extract-records-json
  "Extract records from a JSON array. Optionally unwrap via path."
  [output unwrap-path]
  (let [data (parse-json-safe output)]
    (if unwrap-path
      (extract-field data unwrap-path)
      (when (sequential? data) data))))

(defn- extract-records-json-lines
  "Extract records from newline-delimited JSON (one object per line)."
  [output]
  (keep parse-json-safe (str/split-lines output)))

(defn- extract-records-json-nested
  "Extract records from a JSON array of parent objects, each containing
   a nested array of child records. File path comes from the parent."
  [output parent-file-path unwrap-path]
  (let [parents (parse-json-safe output)]
    (when (sequential? parents)
      (mapcat (fn [parent]
                (let [file     (extract-field parent parent-file-path)
                      children (extract-field parent unwrap-path)]
                  (mapv #(assoc % ::parent-file file) children)))
              parents))))

(defn- extract-records-edn
  "Extract records from EDN output. Optionally unwrap via path."
  [output unwrap-path]
  (try
    (let [data (edn/read-string output)]
      (if unwrap-path
        (extract-field data unwrap-path)
        (when (sequential? data) data)))
    (catch Exception _ nil)))

(defn- extract-records
  "Dispatch record extraction based on format."
  [output mapping]
  (let [fmt    (get mapping :mapping/format)
        unwrap (get mapping :mapping/unwrap)]
    (case fmt
      :json        (extract-records-json output unwrap)
      :json-lines  (extract-records-json-lines output)
      :json-nested (extract-records-json-nested output
                                                 (get mapping :mapping/parent-file)
                                                 unwrap)
      :edn         (extract-records-edn output unwrap)
      nil)))

;------------------------------------------------------------------------------ Layer 2
;; Apply mapping

(defn apply-mapping
  "Apply a mapping spec to raw linter output, producing canonical violations.

   Arguments:
   - mapping — Mapping spec from linter-mappings.edn
   - output  — Raw linter output string

   Returns:
   - Vector of violation maps"
  [mapping output]
  (let [linter-id    (get mapping :mapping/id)
        fields       (get mapping :mapping/fields)
        severity-map (get mapping :mapping/severity-map {})
        filter-spec  (get mapping :mapping/filter)
        records      (extract-records output mapping)]
    (->> records
         (filter #(matches-filter? filter-spec %))
         (mapv (fn [record]
                 (record->violation linter-id fields severity-map record
                                   (get record ::parent-file))))
         (filterv #(seq (:current %))))))

;------------------------------------------------------------------------------ Layer 2
;; Mapping registry

(def ^:private mappings-resource "connector_linter/linter-mappings.edn")

(def mappings
  "All linter mappings loaded from classpath EDN."
  (delay
    (if-let [url (io/resource mappings-resource)]
      (let [raw (edn/read-string (slurp url))]
        (zipmap (map :mapping/id raw) raw))
      {})))

(defn get-mapping
  "Look up a mapping by linter ID. Returns nil if not found."
  [linter-id]
  (get @mappings linter-id))
