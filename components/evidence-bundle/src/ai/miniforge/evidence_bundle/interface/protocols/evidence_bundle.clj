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
