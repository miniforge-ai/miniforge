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
    (boolean (some #(re-find % n) (:redaction/secret-key-patterns @policy/policy)))))

(defn ^{:stratum 0} redact-string
  "Replace every secret-looking substring in S with the marker.

   Substring replacement rather than whole-value replacement, so
   `\"deploying with sk-live-abc…\"` keeps its sentence and loses only
   the key. Whole-value replacement would destroy the audit trail the
   marker exists to preserve."
  [s]
  (reduce (fn [acc pattern] (str/replace acc pattern (policy/marker)))
          s
          (:redaction/secret-value-patterns @policy/policy)))
