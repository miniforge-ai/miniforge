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
(ns ai.miniforge.evidence-bundle.collector
  "Assembling an evidence bundle from workflow state.\n\n   The pieces it assembles live in sibling namespaces; this one is the\n   order they go together in."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.evidence-bundle.collectors :as collectors]
   [ai.miniforge.evidence-bundle.compliance-defaults :as compliance-defaults]
   [ai.miniforge.evidence-bundle.dependency-health :as dependency-health]
   [ai.miniforge.evidence-bundle.outcome :as outcome]
   [ai.miniforge.evidence-bundle.phases :as phases]
   [ai.miniforge.evidence-bundle.protocols.impl.semantic-validator :as semantic-validator]
   [ai.miniforge.evidence-bundle.scanner :as scanner]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} extract-intent
  [workflow-spec]
  {:intent/type (get workflow-spec :intent/type :update)
   :intent/description (or (:description workflow-spec)
                            (get workflow-spec :title ""))
   :intent/business-reason (get workflow-spec :business-reason
                                "No business reason provided")
   :intent/constraints (get workflow-spec :constraints [])
   :intent/declared-at (java.time.Instant/now)
   :intent/author (get workflow-spec :author "system")})

;; Compliance Defaults and Overrides
(defn ^{:stratum 0} append-access-log-entry
  "Append an access log entry to the bundle's :evidence/access-log.
   Stamps :access-log/timestamp on the entry when absent.
   Append-only contract: existing entries are never removed or mutated.
   Returns the updated bundle."
  [bundle entry]
  (let [stamped (if (some? (:access-log/timestamp entry))
                  entry
                  (assoc entry :access-log/timestamp (java.time.Instant/now)))]
    (update bundle :evidence/access-log
            (fn [access-log]
              (conj (vec (or access-log [])) stamped)))))

;; Phase Evidence Collection
(defn ^{:stratum 0} should-create-bundle?
  "Check if evidence bundle should be created for workflow.
   Always create bundle at completion, even on failure (per N6 spec)."
  [workflow-state]
  (contains? #{:completed :failed} (:workflow/status workflow-state)))

(defn ^{:stratum 0} collect-all-phases
  "Collect evidence for all executed phases.
   Returns map of evidence keys to phase evidence."
  [workflow-state]
  (let [phases [:plan :design :implement :verify :review :release :observe]]
    (reduce
     (fn [acc phase-name]
       (if-let [evidence (phases/collect-phase-evidence workflow-state phase-name)]
         (assoc acc (keyword "evidence" (name phase-name)) evidence)
         acc))
     {}
     phases)))

(defn- ^{:stratum 0} collect-dependency-health
  [workflow-state stream workflow-id opts]
  (let [projection (or (:dependency-health opts)
                       (:dependency-health workflow-state)
                       (when stream
                         (dependency-health/dependency-health-from-events stream workflow-id)))]
    (dependency-health/canonical-dependency-health projection)))

;------------------------------------------------------------------------------ Layer 1

;; Complete Bundle Assembly
(defn ^{:stratum 1} assemble-evidence-bundle
  "Assemble complete evidence bundle from workflow state and context.
   Merges N11 §9.1 execution evidence fields from :execution/output.

   Compliance defaults (data-classification :internal, retention 90 days, no PII)
   are applied after the main cond-> chain. Callers may override any compliance
   field by supplying a :compliance map on the workflow-spec or opts:

     opts:          {:compliance {:evidence/data-classification :confidential}}
     workflow-spec: {:compliance {:evidence/contains-pii? true
                                  :evidence/retention-policy {:retain-days 365}}}

   :evidence/retention-policy overrides are merged one level deep — partial
   overrides (e.g. only :retain-days) leave the other retention fields intact.

   Returns evidence bundle ready for storage."
  [workflow-id workflow-state artifact-store & [opts]]
  (let [workflow-spec (:workflow/spec workflow-state)
        event-stream (:event-stream opts)
        intent (extract-intent workflow-spec)
        phase-evidence (collect-all-phases workflow-state)
        policy-checks (collectors/collect-policy-checks workflow-state)
        pack-promotions (collectors/collect-pack-promotions workflow-state)
        outcome (outcome/build-outcome-evidence workflow-state)
        tool-invocations (collectors/collect-tool-invocations workflow-state)
        rules-applied (collectors/collect-rules-applied workflow-state)
        supervision-decisions (collectors/collect-supervision-decisions event-stream workflow-id)
        control-actions (collectors/collect-control-actions event-stream workflow-id)
        dependency-health (collect-dependency-health workflow-state event-stream workflow-id opts)
        failure-attribution (outcome/collect-failure-attribution workflow-state opts)

        ;; N11 §9.1: extract execution evidence from workflow result
        execution-evidence (collectors/collect-execution-evidence workflow-state)

        ;; Compliance: defaults from schema + caller overrides from spec/opts
        compliance-defaults (compliance-defaults/build-default-compliance-metadata)
        compliance-overrides (compliance-defaults/extract-compliance-overrides workflow-spec opts)
        compliance-data (compliance-defaults/merge-compliance compliance-defaults compliance-overrides)

        ;; Get artifacts for semantic validation
        artifacts (when artifact-store
                    (filter
                     #(= workflow-id (get-in % [:artifact/provenance :provenance/workflow-id]))
                     (artifact/query artifact-store {})))

        ;; Perform semantic validation if implementer artifacts exist
        impl-artifacts (filter #(= :implement (get-in % [:artifact/provenance :provenance/phase]))
                               artifacts)
        semantic-validation (when (seq impl-artifacts)
                              (semantic-validator/validate-intent-impl intent impl-artifacts))

        base-bundle (merge
                     (schema/create-evidence-bundle-template)
                     {:evidence-bundle/id (random-uuid)
                      :evidence-bundle/workflow-id workflow-id
                      :evidence-bundle/created-at (java.time.Instant/now)
                      :evidence/intent intent
                      :evidence/policy-checks policy-checks
                      :evidence/outcome outcome}
                     phase-evidence
                     execution-evidence)
        bundle (cond-> base-bundle
                 (seq tool-invocations)
                 (assoc :evidence/tool-invocations tool-invocations)
                 (seq pack-promotions)
                 (assoc :evidence/pack-promotions pack-promotions)
                 (seq supervision-decisions)
                 (assoc :evidence/supervision-decisions supervision-decisions)
                 (seq control-actions)
                 (assoc :evidence/control-actions control-actions)
                 (seq dependency-health)
                 (assoc :evidence/dependency-health dependency-health)
                 failure-attribution
                 (assoc :evidence/failure-attribution failure-attribution)
                 (seq rules-applied)
                 (assoc :evidence/rules-applied rules-applied)
                 semantic-validation
                 (assoc :evidence/semantic-validation semantic-validation))

        ;; Wire compliance defaults then caller overrides into the assembled bundle.
        ;; Runs after the cond-> chain so that spec/opts overrides take precedence
        ;; over the template defaults.  The scanner step below may still add
        ;; :compliance/* scan-detected keys on top.
        bundle (merge bundle compliance-data)

        ;; Run sensitive data scanner before hashing
        scan-result (try
                      (scanner/compliance-metadata (scanner/scan-artifact bundle))
                      (catch Exception _e
                        {}))

        ;; Merge compliance metadata
        bundle (cond-> bundle
                 scan-result (merge scan-result))]
    (assoc bundle :evidence/content-hash (content-hash/content-hash bundle))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} auto-collect-evidence
  [workflow-id workflow-state artifact-store]
  (when (should-create-bundle? workflow-state)
    (assemble-evidence-bundle workflow-id workflow-state artifact-store)))
