# Evidence Bundle Component

The evidence-bundle component provides comprehensive audit trails for autonomous workflow execution, implementing the N6 Evidence & Provenance Standard.

## Purpose

Evidence bundles make autonomous workflows **credible** by providing:

1. **Traceability** - Complete chain from intent to outcome
2. **Validation** - Semantic intent verification (does implementation match declared intent?)
3. **Compliance** - Complete audit trail for SOCII/FedRAMP
4. **Debugging** - Phase evidence and event stream linkage
5. **Trust** - Policy checks and semantic validation results

## Architecture

The component follows the established protocol extraction pattern:

```
evidence-bundle/
├── src/ai/miniforge/evidence_bundle/
│   ├── interface.clj                          # Public API
│   ├── schema.clj                             # Evidence bundle schema per N6
│   ├── collector.clj                          # Evidence collection utilities
│   ├── workflow_integration.clj               # Workflow observer integration
│   ├── interface/protocols/
│   │   └── evidence_bundle.clj                # Protocol definitions
│   └── protocols/
│       ├── impl/
│       │   ├── evidence_bundle.clj            # Core bundle operations
│       │   ├── semantic_validator.clj         # Intent validation logic
│       │   └── provenance_tracer.clj          # Provenance tracing
│       └── records/
│           └── evidence_bundle.clj            # Protocol record implementation
└── test/ai/miniforge/evidence_bundle/
    ├── interface_test.clj                     # Public API tests
    └── semantic_validator_test.clj            # Validation logic tests
```

## Protocols

### EvidenceBundle

Core protocol for evidence bundle management:

```clojure
(defprotocol EvidenceBundle
  (create-bundle [this workflow-id opts])
  (get-bundle [this bundle-id])
  (get-bundle-by-workflow [this workflow-id])
  (query-bundles [this criteria])
  (validate-bundle [this bundle])
  (export-bundle [this bundle-id output-path]))
```

### ProvenanceTracer

Protocol for artifact provenance tracing:

```clojure
(defprotocol ProvenanceTracer
  (query-provenance [this artifact-id])
  (trace-artifact-chain [this workflow-id])
  (query-intent-mismatches [this opts]))
```

### SemanticValidator

Protocol for semantic intent validation:

```clojure
(defprotocol SemanticValidator
  (validate-intent [this intent implementation-artifacts])
  (analyze-terraform-plan [this plan-artifact])
  (analyze-kubernetes-manifest [this manifest-artifact]))
```

## Usage

### Basic Evidence Collection

```clojure
(require '[ai.miniforge.evidence-bundle.interface :as evidence]
         '[ai.miniforge.artifact.interface :as artifact])

;; Create artifact store and evidence manager
(def artifact-store (artifact/create-transit-store))
(def evidence-mgr (evidence/create-evidence-manager
                   {:artifact-store artifact-store}))

;; Create evidence bundle for completed workflow
(def bundle (evidence/create-bundle
             evidence-mgr
             workflow-id
             {:workflow-state workflow-state}))

;; Query bundles
(evidence/query-bundles evidence-mgr {:intent-type :import})

;; Export bundle for audit
(evidence/export-bundle evidence-mgr bundle-id "/tmp/evidence.edn")
```

### Automatic Evidence Collection

```clojure
(require '[ai.miniforge.evidence-bundle.workflow-integration :as integration]
         '[ai.miniforge.workflow.interface :as workflow])

;; Attach evidence collector to workflow
(def wf (-> (workflow/create-workflow)
            (integration/create-and-attach-evidence-collector artifact-store)
            (workflow/start spec context)))

;; Evidence bundle created automatically on workflow completion
```

### Semantic Intent Validation

```clojure
;; Validate that implementation matches declared intent
(def intent {:intent/type :import})
(def artifacts [{:artifact/type :terraform-plan
                 :artifact/content plan-output}])

(def result (evidence/validate-intent evidence-mgr intent artifacts))

;; Check for violations
(when-not (:passed? result)
  (println "Intent violations detected:")
  (doseq [v (:violations result)]
    (println " -" (:violation/message v))))
```

### Provenance Tracing

```clojure
;; Trace complete artifact chain
(def chain (evidence/trace-artifact-chain evidence-mgr workflow-id))

;; Show intent -> phases -> outcome
(println "Intent:" (get-in chain [:intent :intent/type]))
(doseq [phase (:chain chain)]
  (println "Phase:" (:phase phase)
           "Artifacts:" (count (:artifacts phase))))
(println "Outcome:" (:outcome chain))

;; Find intent mismatches across all workflows
(def mismatches (evidence/query-intent-mismatches evidence-mgr {}))
(doseq [m mismatches]
  (println "Workflow" (:workflow-id m)
           "declared" (:declared-intent m)
           "but performed" (:actual-behavior m)))
```

## Evidence Bundle Structure

Evidence bundles follow the N6 specification:

```clojure
{:evidence-bundle/id uuid
 :evidence-bundle/workflow-id uuid
 :evidence-bundle/created-at inst
 :evidence-bundle/version "1.0.0"

 ;; Original Intent
 :evidence/intent {:intent/type :import
                   :intent/description "..."
                   :intent/business-reason "..."
                   :intent/constraints [...]}

 ;; Phase Evidence (for each executed phase)
 :evidence/plan {...}
 :evidence/implement {...}
 :evidence/verify {...}

 ;; Semantic Validation
 :evidence/semantic-validation {:semantic-validation/declared-intent :import
                                :semantic-validation/actual-behavior :import
                                :semantic-validation/passed? true
                                :semantic-validation/violations []}

 ;; Policy Checks
 :evidence/policy-checks [{:policy-check/pack-id "terraform-aws"
                           :policy-check/passed? true
                           :policy-check/violations []}]

 ;; Outcome
 :evidence/outcome {:outcome/success true
                    :outcome/pr-number 123
                    :outcome/pr-url "..."}}
```

## Semantic Validation Rules

Per N6 section 2.4.1, validation rules enforce intent-to-implementation consistency:

| Intent Type | Allowed Creates | Allowed Updates | Allowed Destroys |
|------------|----------------|----------------|-----------------|
| `:import` | 0 | 0 (state-only) | 0 |
| `:create` | >0 | Any | 0 |
| `:update` | 0 | >0 | 0 |
| `:destroy` | 0 | 0 | >0 |
| `:refactor` | 0 | 0 | 0 (code-only) |
| `:migrate` | >0 | 0 | >0 (balanced) |

Example violation detection:

```clojure
;; User declares intent to import (no creates)
{:intent/type :import}

;; But Terraform plan shows creates
"aws_security_group.new will be created"

;; Semantic validator detects violation
{:passed? false
 :violations [{:violation/rule-id "semantic-creates"
               :violation/severity :critical
               :violation/message "Intent 'import' expects 0 creates, found 1"}]}
```

## Integration with Workflow Component

The evidence-bundle component integrates with the workflow component via the WorkflowObserver pattern:

1. **Workflow Completion**: When workflow reaches terminal state (completed/failed)
2. **Evidence Collection**: EvidenceCollector gathers all phase evidence
3. **Semantic Validation**: Validates intent vs. implementation
4. **Bundle Creation**: Creates immutable evidence bundle
5. **Storage**: Stores bundle for audit/compliance

## Testing

Run component tests:

```bash
# From repository root
clojure -M:test:poly test :component evidence-bundle

# Or run specific test namespace
clojure -M:test -n ai.miniforge.evidence-bundle.interface-test
```

## Compliance

Evidence bundles support:

- **SOCII**: Immutable audit trails, change traceability
- **FedRAMP**: Complete provenance chains, policy validation
- **ISO 27001**: Evidence-based compliance verification

## Dependencies

- `ai.miniforge/schema` - Domain schema definitions
- `ai.miniforge/logging` - Structured logging
- `ai.miniforge/artifact` - Artifact storage and provenance

## Related Specifications

- **N1 (Architecture)**: Section 2.8 defines evidence bundle requirements
- **N6 (Evidence & Provenance)**: Complete evidence bundle specification
- **N3 (Event Stream)**: Evidence bundles link to event streams
- **N4 (Policy Packs)**: Policy check results stored in evidence

## Future Enhancements

Per N6 section 12 (Future Extensions):

1. **Cryptographic Signatures**: Digital signatures by agents
2. **Evidence Comparison**: Fleet-wide evidence analysis
3. **Privacy-Preserving Patterns**: Federated learning from evidence

---

**Version**: 1.0.0
**Status**: Conformant with N6-0.1.0-draft
**Last Updated**: 2026-01-23
