;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.parsing
  "Judge response parsing: the LLM's text into canonical violation maps.

   Everything here tolerates a model that answers badly — unparseable
   output and unplaceable rule tags are normalized, never thrown. An answer
   that is not a finding list parses to nil, never to an empty list, so a
   caller cannot mistake it for a clean file.

   Layer 0: code-fence stripping, per-violation canonicalization,
     rule-id coercion, by-rule indexing, failure message keys
   Layer 1: whole-response parsing, canonicalization and judge-failure
     violations (over Layer 0)"
  (:require
   [ai.miniforge.compliance-scanner.interface :as factory]
   [ai.miniforge.semantic-analyzer.messages :as messages]
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

(def ^{:stratum 0} ^:private failure-message-keys
  "Message key for each way the judge can fail to give a judgement."
  {:call-failed       :judge/call-failed
   :unparseable       :judge/unparseable
   :deadline-exceeded :judge/deadline-exceeded})

(defn ^{:stratum 0} index-by-rule
  "Fold a flat seq of canonical violations onto `by-rule` as
   {rule-id [violation...]}."
  [by-rule violations]
  (reduce (fn [m v] (update m (:rule/id v) (fnil conj []) v)) by-rule violations))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse-judge-response
  "Parse the LLM's EDN response into violation maps.
   Returns a vector of violations (empty when the judge found none), or nil
   when the response is not an EDN vector — the judge gave no judgement."
  [response-text]
  (try
    (let [cleaned (-> (str response-text) str/trim strip-code-fences str/trim)
          parsed  (edn/read-string cleaned)]
      (when (vector? parsed)
        (filterv map? parsed)))
    (catch Exception _ nil)))

(defn ^{:stratum 1} judge-failure-violation
  "A violation standing in for a judgement the judge did not give on
   `file-path`. `failure` is :call-failed, :unparseable or
   :deadline-exceeded; `detail` fills the message. It blocks `rule` like a
   finding, so a judge outage never reads as compliance."
  [rule file-path failure detail]
  (assoc (raw->violation rule file-path
                         {:message (messages/t (get failure-message-keys failure)
                                               {:detail detail})})
         :semantic/judge-failure failure))

(defn ^{:stratum 1} judge-response->violations
  "Convert parsed judge response to canonical violation maps."
  [rule file-path raw-violations]
  (mapv #(raw->violation rule file-path %) raw-violations))
