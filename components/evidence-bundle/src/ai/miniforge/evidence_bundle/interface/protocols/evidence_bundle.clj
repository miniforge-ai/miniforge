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

(ns ai.miniforge.evidence-bundle.interface.protocols.evidence-bundle
  "Public protocol for evidence bundle management.
   This is the main extensibility point for evidence collection and retrieval.")

(defprotocol EvidenceBundle
  "Protocol for creating, storing, and querying evidence bundles.
   Evidence bundles provide complete audit trails from intent to outcome."

  (create-bundle [this workflow-id opts]
    "Create an evidence bundle for a completed workflow.
     Returns evidence bundle map with :evidence-bundle/id.")

  (get-bundle [this bundle-id]
    "Retrieve an evidence bundle by ID.
     Returns evidence bundle map or nil if not found.")

  (get-bundle-by-workflow [this workflow-id]
    "Retrieve evidence bundle for a specific workflow.
     Returns evidence bundle map or nil if not found.")

  (query-bundles [this criteria]
    "Query evidence bundles by criteria.
     criteria: {:time-range [start end] :intent-type :status}
     Returns vector of evidence bundles.")

  (validate-bundle [this bundle]
    "Validate evidence bundle structure and integrity.
     Returns {:valid? bool :errors [...]}")

  (export-bundle [this bundle-id output-path]
    "Export evidence bundle to file (EDN or JSON).
     Returns true on success, false on error."))

(defprotocol ProvenanceTracer
  "Protocol for tracing artifact provenance chains."

  (query-provenance [this artifact-id]
    "Get full provenance for an artifact.
     Returns {:artifact {...} :workflow-id :original-intent {...}}")

  (trace-artifact-chain [this workflow-id]
    "Trace complete artifact chain for a workflow.
     Returns {:intent {...} :chain [...] :outcome {...}}")

  (query-intent-mismatches [this opts]
    "Find workflows where declared intent != actual behavior.
     Returns vector of mismatch records."))

(defprotocol SemanticValidator
  "Protocol for semantic intent validation."

  (validate-intent [this intent implementation-artifacts]
    "Validate implementation matches declared intent.
     Returns {:passed? bool :violations [...]}")

  (analyze-terraform-plan [this plan-artifact]
    "Analyze Terraform plan for resource changes.
     Returns {:creates N :updates N :destroys N}")

  (analyze-kubernetes-manifest [this manifest-artifact]
    "Analyze Kubernetes manifest for resource changes.
     Returns {:creates N :updates N :destroys N}"))
