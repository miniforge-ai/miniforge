;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.parsing
  "Judge response parsing: the LLM's text into canonical violation maps.

   Everything here tolerates a model that answers badly — unparseable
   output and unplaceable rule tags are normalized, never thrown.

   Layer 0: code-fence stripping, per-violation canonicalization,
     rule-id coercion, by-rule indexing
   Layer 1: whole-response parsing and canonicalization (over Layer 0)"
  (:require
   [ai.miniforge.compliance-scanner.interface :as factory]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} strip-code-fences
  "Remove markdown code fences from a string."
  [s]
  (cond-> s
    (str/starts-with? s "```") (str/replace #"^```\w*\n?" "")
    (str/ends-with? s "```")   (str/replace #"\n?```$" "")))

(defn ^{:stratum 0} raw->violation
  "Convert a single raw LLM violation to canonical shape via factory."
  [rule file-path v]
  (factory/->violation
   (get rule :rule/id)
   (get rule :rule/category "000")
   (get rule :rule/title)
   file-path
   (get v :line 0)
   (get v :current "")
   nil
   false
   (get v :message "Semantic analysis violation")))

(defn ^{:stratum 0} ->rule-kw
  "Coerce a model-emitted :rule-id (keyword, \":std/foo\" or \"std/foo\" string)
   to a keyword; nil for anything else."
  [x]
  (cond
    (keyword? x) x
    (string? x)  (try
                  (-> x str/trim (str/replace #"^:" "") keyword)
                  (catch Exception _ nil))
    :else        nil))

(defn ^{:stratum 0} index-by-rule
  "Fold a flat seq of canonical violations onto `by-rule` as
   {rule-id [violation...]}."
  [by-rule violations]
  (reduce (fn [m v] (update m (:rule/id v) (fnil conj []) v)) by-rule violations))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse-judge-response
  "Parse the LLM's EDN response into violation maps.
   Returns a vector of violations, or empty vector on parse failure."
  [response-text]
  (try
    (let [cleaned (-> (str response-text) str/trim strip-code-fences str/trim)
          parsed  (edn/read-string cleaned)]
      (if (vector? parsed)
        (filterv map? parsed)
        []))
    (catch Exception _ [])))

(defn ^{:stratum 1} judge-response->violations
  "Convert parsed judge response to canonical violation maps."
  [rule file-path raw-violations]
  (mapv #(raw->violation rule file-path %) raw-violations))
