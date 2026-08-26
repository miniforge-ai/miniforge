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

   A credential is textual. A number, boolean, nil, or timestamp cannot
   encode one, so redacting it under a secret-naming key destroys data
   for no gain — `{:metrics {:tokens 42}}` is an LLM token *count*, not
   a bearer token, and replacing 42 with a string breaks every consumer
   that does arithmetic on it.

   Collections stay redactable: a map under `:credentials` is replaced
   wholesale rather than walked, since its own key names may be
   innocuous while its values are not."
  [v]
  (not (or (nil? v) (number? v) (boolean? v) (inst? v) (uuid? v))))

(defn ^{:stratum 0} secret-string?
  "True when S contains a secret-shaped value."
  [s]
  (boolean (some #(re-find % s) (:redaction/secret-value-patterns @policy/policy))))

(defn ^{:stratum 0} redact-string
  "Replace every secret-looking substring in S with the marker.

   Substring replacement rather than whole-value replacement, so
   `\"deploying with sk-live-abc…\"` keeps its sentence and loses only
   the key. Whole-value replacement would destroy the audit trail the
   marker exists to preserve."
  [s]
  ;; One str/replace per pattern rather than a single alternation over
  ;; all of them: the alternation measured ~45% slower, because Java can
  ;; use a literal-prefix optimization on each pattern separately and
  ;; loses it once they are combined.
  (let [marker (policy/marker)]
    (reduce (fn [acc pattern] (str/replace acc pattern marker))
            s
            (:redaction/secret-value-patterns @policy/policy))))
