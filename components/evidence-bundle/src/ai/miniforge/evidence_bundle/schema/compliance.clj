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
(ns ai.miniforge.evidence-bundle.schema.compliance
  "Compliance, retention, and access-log schemas for evidence bundles.

   Data classification, PII handling, regulatory tags, retention policy, and
   the audit access-log — the compliance-metadata subset of the N6 Evidence
   & Provenance Standard, plus the predicates that validate them.

   Split out of the former `schema.clj` (SL003, Wave 2) — this was its
   Layer 0 (compliance constants/schemas) and Layer 2/4/5 (the `valid-*?`
   predicates that validate them, which called down into the also-moved
   `validate-schema`)."
  (:require
   [ai.miniforge.evidence-bundle.schema.optional-key :as optional-key]
   [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pii-handling-types #{:none :redacted :encrypted})

(def ^{:stratum 0} data-classifications
  "Valid data classification levels for evidence bundles."
  #{:public :internal :confidential :restricted})

(def ^{:stratum 0} regulatory-tag-values
  "Regulatory frameworks that may apply to an evidence bundle."
  #{:gdpr :hipaa :sox :pci})

(def ^{:stratum 0} default-retention-days
  "Default retention period for evidence bundles — 90 days aligns with the
   team's baseline audit window. Callers can override per-bundle retention
   through :evidence/retention-policy in workflow-spec or opts compliance
   metadata for regulatory environments that require longer holds."
  90)

(def ^{:stratum 0} default-data-classification
  "Default data classification for new evidence bundles. :internal rather
   than :public ensures new bundles are never accidentally treated as safe
   to share externally; operators promoting to :public must explicitly opt in.
   :confidential and :restricted require manual assignment at bundle creation."
  :internal)

(def ^{:stratum 0} default-created-by-principal
  "Default :evidence/created-by value for system-generated bundles.
   The string 'system' is the canonical principal token for automated evidence
   collection, distinct from a human user identity."
  "system")

(def ^{:stratum 0} retention-policy-schema
  "Schema for retention policy sub-document."
  {:retain-days pos-int?
   :auto-delete? boolean?
   :legal-hold? boolean?})

(def ^{:stratum 0} access-log-entry-schema
  "Schema for a single access log entry on an evidence bundle."
  {:access-log/principal string?
   :access-log/action keyword?
   :access-log/timestamp inst?
   (optional-key/optional-key :access-log/reason) string?})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} compliance-schema
  "Schema for compliance metadata."
  {:compliance/created-at inst?
   :compliance/sensitive-data boolean?
   :compliance/pii-handling (fn [t] (contains? pii-handling-types t))
   (optional-key/optional-key :compliance/retention-policy) keyword?
   (optional-key/optional-key :compliance/auditor-notes) string?})

;; Public (not `defn-`): evidence-bundle-schema in the root `schema` ns
;; references these as validator predicates across the namespace boundary
;; that this split introduced. Named fns — not anonymous — so they can be
;; tested independently (rule 002: no anon fns in maps).
(defn ^{:stratum 1} valid-data-classification?
  "Returns true when v is a member of data-classifications."
  [v]
  (contains? data-classifications v))

(defn ^{:stratum 1} valid-regulatory-tags?
  "Returns true when v is a set containing only known regulatory tags."
  [v]
  (and (set? v)
       (every? #(contains? regulatory-tag-values %) v)))

(defn ^{:stratum 1} valid-retention-policy-map?
  "Returns true when v is a map that satisfies retention-policy-schema."
  [v]
  (and (map? v)
       (:valid? (validation/validate-schema retention-policy-schema v))))

(defn ^{:stratum 1} valid-access-log-entry?
  "Returns true when a single access log entry satisfies access-log-entry-schema."
  [entry]
  (:valid? (validation/validate-schema access-log-entry-schema entry)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid-access-log?
  "Returns true when the access log is a vector of valid entries."
  [v]
  (and (vector? v)
       (every? valid-access-log-entry? v)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (valid-data-classification? :internal)
  (valid-retention-policy-map? {:retain-days 90 :auto-delete? true :legal-hold? false})
  (valid-access-log? [])
  :end)
