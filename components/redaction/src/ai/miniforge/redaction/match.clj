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
(ns ai.miniforge.redaction.match
  "Recognising an excluded value, by key name or by value shape.

   Two mechanisms because secrets hide in two places: a password is
   detectable only by its key, an AWS access key only by its shape."
  (:require
   [clojure.string :as str]
   [ai.miniforge.redaction.payment-card :as payment-card]
   [ai.miniforge.redaction.policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} secret-key?
  "True when KEY's name indicates its value is a secret regardless of
   the value's shape. A password matches no value pattern; only its
   key betrays it."
  [k]
  (when-let [n (cond
                 (keyword? k) (name k)
                 (string? k)  k
                 (symbol? k)  (name k)
                 :else        nil)]
    (boolean
     (and (some #(re-find % n) (:redaction/secret-key-patterns @policy/policy))
          ;; A qualifier suffix makes the key a reference to a secret
          ;; rather than the secret, so the wholesale rule does not
          ;; apply. The value is still walked and still shape-scanned.
          (not (some #(re-find % n) (:redaction/secret-key-exclusions @policy/policy)))))))

(defn ^{:stratum 0} redactable-value?
  "True when V is the kind of value that could carry a secret.

   Key-name matching preserves numbers, booleans, nil, and timestamps.
   Redacting every number under a secret-naming key destroys data
   for no gain — `{:metrics {:tokens 42}}` is an LLM token *count*, not
   a bearer token, and replacing 42 with a string breaks every consumer
   that does arithmetic on it. Numeric payment-card candidates are handled
   separately by shape during the value walk.

   Collections stay redactable: a map under `:credentials` is replaced
   wholesale rather than walked, since its own key names may be
   innocuous while its values are not."
  [v]
  (not (or (nil? v) (number? v) (boolean? v) (inst? v) (uuid? v))))

(defn ^{:stratum 0} pattern-secret-string?
  "True when S matches one of the secret value patterns. Unlike
   `secret-string?`, payment-card candidates are not checked: those are
   found by checksum over any 12-19 digit run, which ordinary numbers in
   source code pass one time in ten. False for anything not a string."
  [s]
  (boolean
   (when (string? s)
     (some #(re-find % s) (:redaction/secret-value-patterns @policy/policy)))))

(defn ^{:stratum 0} pattern-secret-spans
  "[start end] of every secret value pattern match in S, over the whole
   string, so a match spanning lines is found whole. Payment-card
   candidates are not checked (see `pattern-secret-string?`)."
  [s]
  (when (string? s)
    (vec (for [^java.util.regex.Pattern pattern (:redaction/secret-value-patterns @policy/policy)
               :let [m (re-matcher pattern s)]
               span (loop [acc []]
                      (if (.find m) (recur (conj acc [(.start m) (.end m)])) acc))]
           span))))

(defn ^{:stratum 0} redact-string
  "Replace every secret-looking substring in S with the marker.

   Substring replacement rather than whole-value replacement, so
   `\"deploying with sk-live-abc…\"` keeps its sentence and loses only
   the key. Whole-value replacement would destroy the audit trail the
   marker exists to preserve.

   A pattern with a named group `secret` has only that group replaced;
   the rest of its match (a variable name, an `=`, quotes) is context
   that locates the secret and stays. Any other pattern has its whole
   match replaced."
  [s]
  ;; One pass per pattern rather than a single alternation over all of
  ;; them: the alternation measured ~45% slower, because Java can use a
  ;; literal-prefix optimization on each pattern separately and loses
  ;; it once they are combined.
  (let [marker (policy/marker)]
    (payment-card/redact
      (reduce (fn [acc ^java.util.regex.Pattern pattern]
                (if (str/includes? (.pattern pattern) "(?<secret>")
                  (let [m (re-matcher pattern acc)]
                    (loop [from 0 parts []]
                      (if (.find m)
                        (recur (.end m "secret")
                               (conj parts (subs acc from (.start m "secret")) marker))
                        (apply str (conj parts (subs acc from))))))
                  (str/replace acc pattern marker)))
              s (:redaction/secret-value-patterns @policy/policy)) marker)))

(defn ^{:stratum 0} redact-number [value]
  (if (payment-card/present? value) (policy/marker) value))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} secret-string?
  "True when S contains a secret-shaped value.

   False for anything that is not a string. A predicate reached through
   the interface should answer rather than throw, and \"does this
   non-string contain a secret\" has an answer: no. Only a string can
   carry one by shape — a key names it instead, which is `secret-key?`."
  [s]
  (boolean
   (when (string? s)
     (or (payment-card/present? s)
         (pattern-secret-string? s)))))

(defn ^{:stratum 1} redact-key
  "Redact a secret hiding in K itself, by shape.

   Keys normally keep their names: a key names a field, and losing the
   name would hide that the field existed. That holds for a structural
   key like :password or :event/type — but not for a key that *is*
   data. A map keyed by session token carries the secret in the key and
   nowhere else.

   Shape separates the two. No field name looks like an AWS access key
   or a JWT, so only the value-shape rules apply here; the key-name rule
   deliberately does not, since replacing the whole of `:password` would
   destroy the field.

   A redacted keyword or symbol comes back as a string, because the
   marker is not readable inside one: `(keyword \"[REDACTED]\")` prints
   as `:[REDACTED]`, which `edn/read-string` rejects, and N3 §4.3 makes
   every event durable — an unreadable key corrupts the log. The type
   change is honest anyway; such a key was data, not a field name."
  [k]
  (letfn [(qualified [k]
            ;; Namespace as well as name: a secret can sit in either
            ;; half, and `(keyword "AKIA..." "v")` hides it entirely in
            ;; the namespace.
            (let [n  (namespace k)
                  nm (name k)
                  n* (some-> n redact-string)
                  nm* (redact-string nm)]
              (when (or (not= n n*) (not= nm nm*))
                (if n* (str n* "/" nm*) nm*))))]
    (cond
      (number? k) (redact-number k)
      (string? k)  (let [r (redact-string k)] (if (= r k) k r))
      (keyword? k) (or (qualified k) k)
      (symbol? k)  (or (qualified k) k)
      :else        k)))
