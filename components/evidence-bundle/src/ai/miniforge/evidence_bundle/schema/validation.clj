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
(ns ai.miniforge.evidence-bundle.schema.validation
  "Generic schema-map validation engine for evidence-bundle schemas.

   A schema map (schema.domain, schema.compliance, schema itself) pairs each
   key — optionally wrapped via schema.optional-key/optional-key — with a
   validator predicate; `validate-schema` checks a data map against one.

   Split out of the former `schema.clj` (SL003, Wave 2) — this was its
   Layer 2/3 (`unwrap-key` and `validate-schema`)."
  (:require
   [ai.miniforge.evidence-bundle.schema.optional-key :as optional-key]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} unwrap-key
  "Unwrap an optional key to get the actual key."
  [k]
  (if (optional-key/optional-key? k)
    (:key k)
    k))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate-schema
  "Simple schema validation.
   Returns {:valid? bool :errors [...]}"
  [schema data]
  (let [errors (atom [])]
    (doseq [[k validator] schema]
      (let [is-optional? (optional-key/optional-key? k)
            actual-key   (unwrap-key k)
            v            (get data actual-key)]
        (cond
          (and (nil? v) (not is-optional?))
          (swap! errors conj {:key actual-key :error "Required key missing"})

          (and (some? v) (fn? validator) (not (validator v)))
          (swap! errors conj {:key actual-key :error "Validation failed" :value v}))))
    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (validate-schema {:a string?} {:a "x"})
  (validate-schema {:a string? (optional-key/optional-key :b) string?} {:a "x"})
  :end)
