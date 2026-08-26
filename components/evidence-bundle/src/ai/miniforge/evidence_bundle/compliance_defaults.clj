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
(ns ai.miniforge.evidence-bundle.compliance-defaults
  "Compliance metadata: template defaults, caller overrides, and the
   one-level-deep merge between them."
  (:require
   [ai.miniforge.evidence-bundle.schema.compliance :as compliance]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private compliance-override-keys
  "Allowed keys for workflow-spec and opts compliance override maps."
  #{:evidence/data-classification
    :evidence/contains-pii?
    :evidence/retention-policy
    :evidence/regulatory-tags
    :evidence/created-by})

;; Compliance Defaults and Overrides
(defn ^{:stratum 0} build-default-compliance-metadata
  "Return the default compliance map for assembled evidence bundles.
   Uses schema.compliance-defined defaults so the single source of truth
   lives there. Covers the assembly path; the template function covers
   manual construction."
  []
  {:evidence/data-classification compliance/default-data-classification
   :evidence/contains-pii?       false
   :evidence/retention-policy    {:retain-days  compliance/default-retention-days
                                  :auto-delete? true
                                  :legal-hold?  false}
   :evidence/regulatory-tags     #{}
   :evidence/created-by          compliance/default-created-by-principal})

(defn ^{:stratum 0} merge-compliance
  "Merge compliance defaults with operator overrides.
   :evidence/retention-policy is merged one level deep so callers may supply
   only the keys they wish to change (e.g. just {:retain-days 365}) without
   losing the other retention fields from the defaults."
  [defaults overrides]
  (let [base (merge defaults overrides)]
    (if (and (contains? overrides :evidence/retention-policy)
             (map? (get defaults :evidence/retention-policy))
             (map? (get overrides :evidence/retention-policy)))
      (assoc base :evidence/retention-policy
             (merge (get defaults :evidence/retention-policy)
                    (get overrides :evidence/retention-policy)))
      base)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} normalize-compliance-overrides
  "Return only documented compliance override keys from m, or nil for non-maps."
  [m]
  (when (map? m)
    (select-keys m compliance-override-keys)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} extract-compliance-overrides
  "Read compliance overrides from workflow-spec or opts.
   Checks the :compliance key on the spec map first, then falls back to opts.
   Returns a map of overrides to merge into the bundle, or {} when absent.

   Allowed override keys:
   - :evidence/data-classification
   - :evidence/contains-pii?
   - :evidence/retention-policy  (partial — merged one level deep)
   - :evidence/regulatory-tags
   - :evidence/created-by"
  [workflow-spec opts]
  (or (normalize-compliance-overrides (:compliance workflow-spec))
      (normalize-compliance-overrides (:compliance opts))
      {}))
