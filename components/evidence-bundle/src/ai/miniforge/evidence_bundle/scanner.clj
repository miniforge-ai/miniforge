(ns ai.miniforge.evidence-bundle.scanner
  "Sensitive data scanner for evidence artifacts.
   Detects API keys, tokens, and PII in artifact content and redacts matches.
   WI-1.2: Sensitive Data Scanner."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pattern Definitions

(def ^:private api-key-patterns
  "Regex patterns for detecting API keys."
  [{:pattern-name "aws-access-key-id"
    :regex        #"(?i)\bAKIA[0-9A-Z]{16}\b"
    :type         :api-key}
   {:pattern-name "aws-secret-access-key"
    :regex        #"(?i)(?:aws_secret_access_key|aws secret)[^\S\r\n]*[=:][^\S\r\n]*([A-Za-z0-9/+]{40})"
    :type         :api-key}
   {:pattern-name "github-token"
    :regex        #"\bgh[pousr]_[A-Za-z0-9]{36,255}\b"
    :type         :api-key}
   {:pattern-name "slack-token"
    :regex        #"\bxox[baprs]-[0-9A-Za-z\-]{10,48}\b"
    :type         :api-key}
   {:pattern-name "generic-api-key"
    :regex        #"(?i)(?:api[_\-]?key|apikey)[^\S\r\n]*[=:][^\S\r\n]*([A-Za-z0-9_\-]{20,64})"
    :type         :api-key}])

(def ^:private token-patterns
  "Regex patterns for detecting tokens."
  [{:pattern-name "jwt"
    :regex        #"\beyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\b"
    :type         :token}
   {:pattern-name "bearer-token"
    :regex        #"(?i)\bBearer[^\S\r\n]+([A-Za-z0-9\-_\.~\+/]+=*)"
    :type         :token}])

(def ^:private pii-patterns
  "Regex patterns for detecting PII."
  [{:pattern-name "email"
    :regex        #"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"
    :type         :pii}
   {:pattern-name "us-phone"
    :regex        #"\b(?:\+1[\s.\-]?)?\(?\d{3}\)?[\s.\-]?\d{3}[\s.\-]?\d{4}\b"
    :type         :pii}
   {:pattern-name "ssn"
    :regex        #"\b\d{3}-\d{2}-\d{4}\b"
    :type         :pii}
   {:pattern-name "credit-card"
    :regex        #"\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\b"
    :type         :pii}])

(def ^:private all-patterns
  (concat api-key-patterns token-patterns pii-patterns))

;------------------------------------------------------------------------------ Layer 1
;; Match Extraction

(defn- redaction-label
  "Build the redaction placeholder string for a given finding type."
  [type]
  (str "[REDACTED:" (name type) "]"))

(defn- find-matches
  "Return all regex matches of pattern-def in text as finding maps."
  [{:keys [pattern-name regex type]} text]
  (let [matcher (re-matcher regex text)]
    (loop [findings []]
      (if (.find matcher)
        (let [match (.group matcher 0)]
          (recur (conj findings
                       {:type         type
                        :pattern-name pattern-name
                        :match        match
                        :redacted     (redaction-label type)})))
        findings))))

(defn- scan-with-pattern
  "Apply a single pattern definition to text, return seq of findings."
  [pattern-def text]
  (find-matches pattern-def text))

;------------------------------------------------------------------------------ Layer 2
;; Content Scanning

(defn scan-content
  "Scan a string for sensitive data.
   Returns {:findings [{:type :api-key|:token|:pii
                        :pattern-name string
                        :match        string
                        :redacted     string}]
            :has-sensitive-data? bool}"
  [text]
  (if (or (nil? text) (str/blank? text))
    {:findings [] :has-sensitive-data? false}
    (let [findings (mapcat #(scan-with-pattern % text) all-patterns)]
      {:findings            (vec findings)
       :has-sensitive-data? (boolean (seq findings))})))

;------------------------------------------------------------------------------ Layer 3
;; Artifact Scanning

(defn- mapcat-indexed
  "Like mapcat but passes index and value to f, then flattens one level."
  [f coll]
  (mapcat (fn [[i v]] (f i v)) (map-indexed vector coll)))

(defn- string-values
  "Recursively collect all string values from a nested map/coll, paired with
   their key-path for context. Returns seq of [path value]."
  ([data] (string-values [] data))
  ([path data]
   (cond
     (string? data)
     [[path data]]

     (map? data)
     (mapcat (fn [[k v]] (string-values (conj path k) v)) data)

     (sequential? data)
     (mapcat-indexed (fn [i v] (string-values (conj path i) v)) data)

     :else [])))

(defn scan-artifact
  "Scan all string values in an evidence artifact map recursively.
   Returns {:findings [...] :has-sensitive-data? bool} aggregated across all
   string values found in the artifact."
  [artifact]
  (if (nil? artifact)
    {:findings [] :has-sensitive-data? false}
    (let [pairs    (string-values artifact)
          findings (mapcat (fn [[_path text]] (:findings (scan-content text))) pairs)]
      {:findings            (vec findings)
       :has-sensitive-data? (boolean (seq findings))})))

;------------------------------------------------------------------------------ Layer 4
;; Compliance Metadata

(defn compliance-metadata
  "Derive compliance metadata from scan results.
   Returns a map suitable for merging into an evidence bundle's compliance keys:
   {:compliance/sensitive-data {:has-sensitive-data? bool
                                :finding-count       int
                                :finding-types       set
                                :scan-timestamp      java.util.Date}}"
  [{:keys [findings has-sensitive-data?]}]
  {:compliance/sensitive-data
   {:has-sensitive-data? (boolean has-sensitive-data?)
    :finding-count       (count findings)
    :finding-types       (into #{} (map :type) findings)
    :scan-timestamp      (java.util.Date.)}})
