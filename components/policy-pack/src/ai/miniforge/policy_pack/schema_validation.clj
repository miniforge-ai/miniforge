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
(ns ai.miniforge.policy-pack.schema-validation
  "Generic Malli validation helpers and the {:success? ...} result-map
   constructors used across the component (loader.clj, mdc_compiler.clj,
   ast.clj, detection.clj, and others).

   Split out of schema.clj (Wave 2, SL003): domain-independent infrastructure
   that takes a schema/value as an argument rather than referencing any of
   policy-pack's own schemas, so it doesn't participate in the Rule/
   PackManifest composition chain at all.

   Layer 0: valid?/validate/explain (generic Malli wrappers) and
     succeeded?/success/failure/failure-with-errors (result-map helpers) —
     none call each other in this file"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Validation helpers
(defn ^{:stratum 0} valid?
  "Check if value validates against schema."
  [schema value]
  (m/validate schema value))

(defn ^{:stratum 0} validate
  "Validate value against schema.
   Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn ^{:stratum 0} explain
  "Return human-readable explanation of validation errors, or nil if valid."
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

;; Result helpers (used by loader.clj)
(defn ^{:stratum 0} succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

(defn ^{:stratum 0} success
  "Create a success result.
   (success :pack pack {:errors nil}) => {:success? true :pack pack :errors nil}"
  [key value extras]
  (merge {:success? true key value} extras))

(defn ^{:stratum 0} failure
  "Create a failure result.
   (failure :data \"error msg\") => {:success? false :error \"error msg\"}"
  [_key message]
  {:success? false :error message})

(defn ^{:stratum 0} failure-with-errors
  "Create a failure result with error list.
   (failure-with-errors :pack [...]) => {:success? false :errors [...]}"
  [_key errors]
  {:success? false :errors errors})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (valid? [:enum :a :b] :a)
  ;; => true

  (validate [:enum :a :b] :c)
  ;; => {:valid? false :errors [...]}

  (explain [:enum :a :b] :c)
  ;; => humanized error data

  (succeeded? (success :pack {:pack/id "test"} {}))
  ;; => true

  :leave-this-here)
