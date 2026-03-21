(ns ai.miniforge.evidence-bundle.interface
  "Public API for the evidence-bundle component.
   Handles evidence collection, storage, and provenance tracing per N6 spec."
  (:require
   [ai.miniforge.evidence-bundle.chain-evidence :as chain-evidence]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.extraction :as extraction]
   [ai.miniforge.evidence-bundle.hash :as hash]
   [ai.miniforge.evidence-bundle.schema :as schema]
   [ai.miniforge.evidence-bundle.interface.protocols.evidence-bundle :as p]
   [ai.miniforge.evidence-bundle.protocols.records.evidence-bundle :as records]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def EvidenceBundle p/EvidenceBundle)
(def ProvenanceTracer p/ProvenanceTracer)
(def SemanticValidator p/SemanticValidator)

;------------------------------------------------------------------------------ Layer 1
;; Evidence Bundle Manager Creation

(defn create-evidence-manager
  "Create an evidence bundle manager.

   Options:
   - :artifact-store - Artifact store instance (required)
   - :logger - Optional logger instance

   The evidence manager implements three protocols:
   - EvidenceBundle: Create and query evidence bundles
   - ProvenanceTracer: Trace artifact provenance chains
   - SemanticValidator: Validate intent vs. implementation

   Example:
     (def artifact-store (artifact/create-store))
     (def evidence-mgr (create-evidence-manager {:artifact-store artifact-store}))"
  [opts]
  (records/create-evidence-bundle-manager opts))

;------------------------------------------------------------------------------ Layer 2
;; Evidence Bundle Operations

(defn create-bundle
  "Create an evidence bundle for a completed workflow.

   Arguments:
   - manager: Evidence manager instance
   - workflow-id: UUID of the workflow
   - opts: Map with:
     - :workflow-state - Current workflow state (required)
     - :intent - Intent declaration (optional, extracted from workflow-state if not provided)
     - :semantic-validation - Pre-computed semantic validation (optional)
     - :policy-checks - Policy check results (optional)
     - :outcome - Workflow outcome (optional)

   Returns evidence bundle map with :evidence-bundle/id.

   Example:
     (create-bundle manager workflow-id {:workflow-state state})"
  [manager workflow-id opts]
  (p/create-bundle manager workflow-id opts))

(defn get-bundle
  "Retrieve an evidence bundle by ID.
   Returns evidence bundle map or nil if not found."
  [manager bundle-id]
  (p/get-bundle manager bundle-id))

(defn get-bundle-by-workflow
  "Retrieve evidence bundle for a specific workflow.
   Returns evidence bundle map or nil if not found."
  [manager workflow-id]
  (p/get-bundle-by-workflow manager workflow-id))

(defn query-bundles
  "Query evidence bundles by criteria.

   Criteria options:
   - :time-range [start-inst end-inst] - Filter by creation time
   - :intent-type keyword - Filter by intent type (:import, :create, etc.)
   - :status boolean - Filter by success/failure

   Returns vector of evidence bundles.

   Example:
     (query-bundles manager {:intent-type :import})
     (query-bundles manager {:time-range [start end]})"
  [manager criteria]
  (p/query-bundles manager criteria))

(defn validate-bundle
  "Validate evidence bundle structure and integrity.
   Returns {:valid? bool :errors [...]}"
  [manager bundle]
  (p/validate-bundle manager bundle))

(defn export-bundle
  "Export evidence bundle to file (EDN format).

   Arguments:
   - manager: Evidence manager instance
   - bundle-id: UUID of the bundle to export
   - output-path: Path to output file

   Returns true on success, false on error.

   Example:
     (export-bundle manager bundle-id \"/tmp/evidence.edn\")"
  [manager bundle-id output-path]
  (p/export-bundle manager bundle-id output-path))

;------------------------------------------------------------------------------ Layer 3
;; Provenance Tracing

(defn query-provenance
  "Get full provenance for an artifact.

   Returns map with:
   - :artifact - The artifact itself
   - :workflow-id - Workflow that created it
   - :original-intent - Intent from workflow
   - :created-by-phase - Phase that created artifact
   - :created-by-agent - Agent that created artifact
   - :created-at - Creation timestamp
   - :source-artifacts - Input artifacts
   - :full-evidence-bundle - Complete evidence bundle

   Example:
     (query-provenance manager artifact-id)"
  [manager artifact-id]
  (p/query-provenance manager artifact-id))

(defn trace-artifact-chain
  "Trace complete artifact chain for a workflow.

   Returns map with:
   - :intent - Original intent
   - :chain - Vector of phase steps with artifacts
   - :outcome - Final outcome

   Example:
     (trace-artifact-chain manager workflow-id)"
  [manager workflow-id]
  (p/trace-artifact-chain manager workflow-id))

(defn query-intent-mismatches
  "Find workflows where declared intent != actual behavior.

   Options:
   - :time-range [start end] - Filter by time range

   Returns vector of mismatch records with:
   - :workflow-id
   - :declared-intent
   - :actual-behavior
   - :violation-details

   Example:
     (query-intent-mismatches manager {})
     (query-intent-mismatches manager {:time-range [start end]})"
  [manager opts]
  (p/query-intent-mismatches manager opts))

;------------------------------------------------------------------------------ Layer 4
;; Semantic Validation

(defn validate-intent
  "Validate implementation matches declared intent.

   Arguments:
   - manager: Evidence manager instance
   - intent: Intent map with :intent/type
   - implementation-artifacts: Vector of artifacts from implement phase

   Returns map with:
   - :passed? - Whether validation passed
   - :violations - Vector of violations (if any)
   - :semantic-validation/* - Detailed validation results

   Example:
     (validate-intent manager intent artifacts)"
  [manager intent implementation-artifacts]
  (p/validate-intent manager intent implementation-artifacts))

(defn analyze-terraform-plan
  "Analyze Terraform plan for resource changes.

   Returns map with:
   - :creates - Number of resources created
   - :updates - Number of resources updated
   - :destroys - Number of resources destroyed

   Example:
     (analyze-terraform-plan manager plan-artifact)"
  [manager plan-artifact]
  (p/analyze-terraform-plan manager plan-artifact))

(defn analyze-kubernetes-manifest
  "Analyze Kubernetes manifest for resource changes.

   Returns map with:
   - :creates - Number of resources created
   - :updates - Number of resources updated
   - :destroys - Number of resources destroyed

   Example:
     (analyze-kubernetes-manifest manager manifest-artifact)"
  [manager manifest-artifact]
  (p/analyze-kubernetes-manifest manager manifest-artifact))

;------------------------------------------------------------------------------ Layer 5
;; Evidence Collection Helpers

(defn extract-intent
  "Extract intent from workflow specification.
   Returns intent evidence map per N6 spec.

   Example:
     (extract-intent {:intent/type :import :description \"Import RDS\"})"
  [workflow-spec]
  (collector/extract-intent workflow-spec))

(defn assemble-evidence-bundle
  "Assemble complete evidence bundle from workflow state.

   This is a convenience function that combines all evidence collection steps.
   Use create-bundle for storage.

   Arguments:
   - workflow-id: UUID of the workflow
   - workflow-state: Current workflow state
   - artifact-store: Artifact store instance

   Returns evidence bundle map (not yet stored).

   Example:
     (assemble-evidence-bundle workflow-id state artifact-store)"
  [workflow-id workflow-state artifact-store]
  (collector/assemble-evidence-bundle workflow-id workflow-state artifact-store))

(defn auto-collect-evidence
  "Automatically collect evidence when workflow reaches terminal state.

   This function checks if the workflow is complete/failed and automatically
   assembles the evidence bundle if ready.

   Returns evidence bundle or nil if not ready.

   Example:
     (when-let [bundle (auto-collect-evidence workflow-id state artifact-store)]
       (create-bundle manager workflow-id {:workflow-state state}))"
  [workflow-id workflow-state artifact-store]
  (collector/auto-collect-evidence workflow-id workflow-state artifact-store))

;------------------------------------------------------------------------------ Layer 6
;; Content Hashing

(defn content-hash
  "Compute SHA-256 digest of artifact content.
   Returns hex-encoded hash string.

   Example:
     (content-hash {:type :terraform-plan :body \"resource ...\"})
     ;; => \"a3f2b7c9...\" (64-char hex string)"
  [content]
  (hash/content-hash content))

;------------------------------------------------------------------------------ Layer 7b
;; Chain Evidence

(defn create-chain-evidence
  "Create a chain-level evidence record from chain results."
  [chain-def chain-result & [opts]]
  (chain-evidence/create-chain-evidence chain-def chain-result opts))

(defn summarize-step
  "Create a summary of a single chain step."
  [step-result step-index]
  (chain-evidence/summarize-step step-result step-index))

(defn aggregate-metrics
  "Aggregate metrics across chain step results."
  [step-results]
  (chain-evidence/aggregate-metrics step-results))

;------------------------------------------------------------------------------ Layer 7
;; Schema Exports

(def intent-types
  "Valid intent types per N6 spec."
  schema/intent-types)

(def semantic-validation-rules
  "Validation rules per N6 section 2.4.1."
  schema/semantic-validation-rules)

(defn create-evidence-template
  "Create an empty evidence bundle template.
   Useful for testing or manual bundle construction."
  []
  (schema/create-evidence-bundle-template))

;------------------------------------------------------------------------------ Layer 8
;; Artifact Extraction

(defn extract-file
  "Extract a single file from artifact and write to disk.

   File map should contain:
   - :path - File path to write
   - :content - File content
   - :action - One of :create, :modify, or :delete

   Returns map with operation result.

   Example:
     (extract-file {:path \"src/foo.clj\"
                    :content \"(ns foo)\"
                    :action :create})"
  [file-map]
  (extraction/extract-file file-map))

(defn extract-files
  "Extract multiple files from artifact and write to disk.

   Artifact should be a map containing:
   - :code/files - Vector of file maps

   Returns map with extraction results including:
   - :total - Total files processed
   - :successful - Successful operations
   - :failed - Failed operations
   - :results - Vector of individual results

   Example:
     (extract-files {:code/files [{:path \"src/foo.clj\"
                                   :content \"(ns foo)\"
                                   :action :create}]})"
  [artifact]
  (extraction/extract-files artifact))

(defn load-artifact
  "Load artifact from an EDN file.

   Arguments:
   - artifact-path: Path to EDN file

   Returns artifact map.

   Example:
     (load-artifact \"/tmp/artifact.edn\")"
  [artifact-path]
  (extraction/load-artifact artifact-path))

(defn extract-artifact-from-file
  "Load artifact from file and extract all files to disk.

   Convenience function combining load-artifact and extract-files.

   Arguments:
   - artifact-path: Path to EDN file

   Returns extraction results.

   Example:
     (extract-artifact-from-file \"/tmp/artifact.edn\")"
  [artifact-path]
  (extraction/extract-artifact-from-file artifact-path))

(defn validate-artifact
  "Validate artifact structure for extraction.

   Returns map with:
   - :valid? - true if valid
   - :errors - Error messages if invalid

   Example:
     (validate-artifact {:code/files [...]})"
  [artifact]
  (extraction/validate-artifact artifact))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (require '[ai.miniforge.artifact.interface :as artifact])

  ;; Create artifact store and evidence manager
  (def artifact-store (artifact/create-transit-store))
  (def evidence-mgr (create-evidence-manager {:artifact-store artifact-store}))

  ;; Create evidence bundle for workflow
  (def workflow-state
    {:workflow/id #uuid "00000000-0000-0000-0000-000000000001"
     :workflow/status :completed
     :workflow/spec {:intent/type :import
                     :description "Import RDS instance"}
     :workflow/phases {:plan {:agent :planner
                             :artifacts []
                             :started-at (java.time.Instant/now)
                             :completed-at (java.time.Instant/now)
                             :duration-ms 1000}}})

  (def bundle (create-bundle evidence-mgr
                             (:workflow/id workflow-state)
                             {:workflow-state workflow-state}))

  ;; Query bundles
  (query-bundles evidence-mgr {:intent-type :import})

  ;; Trace provenance
  (def example-artifact-id #uuid "00000000-0000-0000-0000-000000000000")
  (query-provenance evidence-mgr example-artifact-id)
  (def example-workflow-id #uuid "00000000-0000-0000-0000-000000000000")
  (trace-artifact-chain evidence-mgr example-workflow-id)

  ;; Find intent mismatches
  (query-intent-mismatches evidence-mgr {})

  ;; Validate intent
  (def example-implementation-artifacts [{:artifact/type :terraform-plan}])
  (validate-intent evidence-mgr
                   {:intent/type :import}
                   example-implementation-artifacts)

  ;; Export bundle
  (def example-bundle-id #uuid "00000000-0000-0000-0000-000000000000")
  (export-bundle evidence-mgr example-bundle-id "/tmp/evidence.edn")

  :end)
