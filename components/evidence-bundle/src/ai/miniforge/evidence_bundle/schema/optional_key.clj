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
(ns ai.miniforge.evidence-bundle.schema.optional-key
  "Optional-key marker for evidence-bundle schema maps.

   A schema map (see schema.domain, schema.compliance, schema itself) pairs
   each key with a validator predicate; wrapping a key with `optional-key`
   tells `schema.validation/validate-schema` the key may be absent from data
   without failing validation.

   Split out of the former `schema.clj` (SL003, Wave 2) — this was its
   Layer 0/1 (the `OptionalKey` record and its constructor/predicate).")

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} OptionalKey [key])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} optional-key
  "Mark a schema key as optional."
  [k]
  (->OptionalKey k))

(defn ^{:stratum 1} optional-key?
  "Check if a key is marked as optional."
  [k]
  (instance? OptionalKey k))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (optional-key :evidence/plan)
  (optional-key? (optional-key :evidence/plan))
  (optional-key? :evidence/plan)
  :end)
