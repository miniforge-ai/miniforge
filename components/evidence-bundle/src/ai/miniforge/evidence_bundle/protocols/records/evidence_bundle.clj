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

(ns ai.miniforge.evidence-bundle.protocols.records.evidence-bundle
  "Record implementations for EvidenceBundle protocol."
  (:require
   [ai.miniforge.evidence-bundle.interface.protocols.evidence-bundle :as p]
   [ai.miniforge.evidence-bundle.protocols.impl.evidence-bundle :as impl]
   [ai.miniforge.evidence-bundle.protocols.impl.semantic-validator :as sem-val]
   [ai.miniforge.evidence-bundle.protocols.impl.provenance-tracer :as prov]
   [ai.miniforge.logging.interface :as log]))

(defrecord EvidenceBundleManager [bundles artifact-store logger]
  p/EvidenceBundle

  (create-bundle [_this workflow-id opts]
    (let [[bundle new-bundles] (impl/create-bundle-impl
                                bundles artifact-store logger
                                workflow-id opts)]
      (reset! bundles new-bundles)
      bundle))

  (get-bundle [_this bundle-id]
    (impl/get-bundle-impl bundles bundle-id))

  (get-bundle-by-workflow [_this workflow-id]
    (impl/get-bundle-by-workflow-impl bundles workflow-id))

  (query-bundles [_this criteria]
    (impl/query-bundles-impl bundles criteria))

  (validate-bundle [_this bundle]
    (impl/validate-bundle-impl bundle))

  (export-bundle [_this bundle-id output-path]
    (impl/export-bundle-impl bundles logger bundle-id output-path))

  p/ProvenanceTracer

  (query-provenance [_this artifact-id]
    (prov/query-provenance-impl artifact-store bundles artifact-id))

  (trace-artifact-chain [_this workflow-id]
    (prov/trace-artifact-chain-impl artifact-store bundles workflow-id))

  (query-intent-mismatches [_this opts]
    (prov/query-intent-mismatches-impl bundles opts))

  p/SemanticValidator

  (validate-intent [_this intent implementation-artifacts]
    (sem-val/validate-intent-impl intent implementation-artifacts))

  (analyze-terraform-plan [_this plan-artifact]
    (sem-val/analyze-terraform-plan-impl plan-artifact))

  (analyze-kubernetes-manifest [_this manifest-artifact]
    (sem-val/analyze-kubernetes-manifest-impl manifest-artifact)))

(defn create-evidence-bundle-manager
  "Create an EvidenceBundleManager instance.
   Options:
   - :artifact-store - Artifact store instance (required)
   - :logger - Optional logger instance"
  ([] (create-evidence-bundle-manager {}))
  ([opts]
   (let [artifact-store (:artifact-store opts)
         logger (or (:logger opts) (log/create-logger {:min-level :info}))]
     (when-not artifact-store
       (throw (ex-info "artifact-store is required" {:opts opts})))
     (->EvidenceBundleManager
      (atom {})
      artifact-store
      logger))))
