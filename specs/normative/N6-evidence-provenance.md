# N6 — Evidence & Provenance Standard

**Version:** 0.1.0-draft
**Date:** 2026-01-23
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **evidence bundle** and **artifact provenance** contracts that make autonomous workflows credible to platform and security teams.

**Evidence bundles** provide a complete audit trail from **intent** → **plan** → **implementation** → **validation** → **outcome**.

**Artifact provenance** enables tracing any artifact back to its source inputs, tool executions, and semantic intent.

### 1.1 Design Principles

1. **Complete traceability** - Every artifact MUST link back to originating workflow and intent
2. **Semantic validation** - Declared intent MUST be verifiable against actual behavior
3. **Immutable records** - Evidence bundles MUST NOT be modified after creation
4. **Queryable history** - Users MUST be able to query "What was the intent for this artifact?"
5. **Compliance-ready** - Evidence format MUST support SOCII, FedRAMP audit requirements

---

## 2. Evidence Bundle Schema

### 2.1 Evidence Bundle Structure

```clojure
{:evidence-bundle/id uuid
 :evidence-bundle/workflow-id uuid
 :evidence-bundle/created-at inst
 :evidence-bundle/version string

 ;; Original Intent
 :evidence/intent {...}

 ;; Phase Evidence (for each phase executed)
 :evidence/plan {...}
 :evidence/design {...}         ; OPTIONAL: if design phase executed
 :evidence/implement {...}
 :evidence/verify {...}
 :evidence/review {...}

 ;; Validation Evidence
 :evidence/semantic-validation {...}
 :evidence/policy-checks [...]

 ;; Outcome
 :evidence/outcome {...}

 ;; Compliance Metadata
 :compliance/auditor-notes string   ; OPTIONAL
 :compliance/sensitive-data boolean
 :compliance/pii-handling keyword}  ; :none, :redacted, :encrypted
```

### 2.2 Intent Evidence

```clojure
{:intent/type keyword           ; REQUIRED: :import, :create, :update, :destroy, :refactor
 :intent/description string      ; REQUIRED: Human-readable intent
 :intent/business-reason string  ; REQUIRED: Why this change is needed
 :intent/constraints [...]       ; REQUIRED: Constraints that MUST be satisfied

 :intent/declared-at inst
 :intent/author string}          ; OPTIONAL: User who declared intent
```

#### 2.2.1 Intent Types

Implementations MUST support these intent types:

- `:import` - Import existing resources (no creates/destroys)
- `:create` - Create new resources
- `:update` - Modify existing resources (no creates/destroys)
- `:destroy` - Remove resources
- `:refactor` - Change structure without behavior change (no functional change)
- `:migrate` - Move resources (create + destroy, but logically a move)

#### 2.2.2 Constraints Schema

```clojure
{:constraint/type keyword       ; e.g., :no-resource-creation, :no-downtime
 :constraint/description string
 :constraint/validation-fn ...} ; OPTIONAL: Function to validate constraint
```

### 2.3 Phase Evidence

Each workflow phase MUST produce phase evidence:

```clojure
{:phase/name keyword            ; :plan, :design, :implement, :verify, :review
 :phase/agent keyword           ; Agent that executed phase
 :phase/agent-instance-id uuid

 :phase/started-at inst
 :phase/completed-at inst
 :phase/duration-ms long

 :phase/output {...}             ; Phase-specific output
 :phase/artifacts [uuid ...]     ; Artifacts produced in this phase

 :phase/inner-loop-iterations long  ; OPTIONAL: How many validate/repair cycles
 :phase/event-stream-range {:start-seq long :end-seq long}}  ; Link to events
```

### 2.4 Semantic Validation Evidence

```clojure
{:semantic-validation/declared-intent keyword  ; From intent/type
 :semantic-validation/actual-behavior keyword  ; Inferred from implementation

 :semantic-validation/resource-creates long    ; Count of creates
 :semantic-validation/resource-updates long    ; Count of updates
 :semantic-validation/resource-destroys long   ; Count of destroys

 :semantic-validation/passed? boolean
 :semantic-validation/violations [...]         ; If failed
 :semantic-validation/checked-at inst}
```

#### 2.4.1 Semantic Validation Rules

Implementations MUST validate:

| Intent Type | Allowed Creates | Allowed Updates | Allowed Destroys |
|------------|----------------|----------------|-----------------|
| `:import` | 0 | 0 (state-only) | 0 |
| `:create` | >0 | Any | 0 |
| `:update` | 0 | >0 | 0 |
| `:destroy` | 0 | 0 | >0 |
| `:refactor` | 0 | 0 | 0 (code-only) |
| `:migrate` | >0 | 0 | >0 (balanced) |

### 2.5 Policy Check Evidence

```clojure
[{:policy-check/pack-id string      ; e.g., "terraform-aws"
  :policy-check/pack-version string
  :policy-check/phase keyword       ; Phase where check ran
  :policy-check/checked-at inst

  :policy-check/violations [...]    ; See 2.5.1
  :policy-check/passed? boolean
  :policy-check/duration-ms long}]
```

#### 2.5.1 Violation Schema

```clojure
{:violation/rule-id string
 :violation/severity keyword        ; :critical, :high, :medium, :low, :info
 :violation/message string
 :violation/location {...}          ; File, line, etc.
 :violation/remediation string      ; OPTIONAL: How to fix
 :violation/auto-fixable? boolean}
```

### 2.6 Outcome Evidence

```clojure
{:outcome/success boolean
 :outcome/pr-number long           ; OPTIONAL: If PR created
 :outcome/pr-url string            ; OPTIONAL
 :outcome/pr-status keyword        ; OPTIONAL: :open, :merged, :closed
 :outcome/pr-merged-at inst        ; OPTIONAL

 :outcome/error-message string     ; OPTIONAL: If failed
 :outcome/error-phase keyword      ; OPTIONAL: Which phase failed
 :outcome/error-details {...}}     ; OPTIONAL
```

---

## 3. Artifact Provenance Schema

### 3.1 Artifact Structure

```clojure
{:artifact/id uuid
 :artifact/type keyword           ; See 3.1.1
 :artifact/content ...            ; Type-specific content
 :artifact/content-hash string    ; SHA-256 of content

 :artifact/created-at inst
 :artifact/size-bytes long

 :artifact/provenance {...}       ; See 3.2
 :artifact/metadata {...}}        ; OPTIONAL: Type-specific metadata
```

#### 3.1.1 Artifact Types

Implementations MUST support:

- `:terraform-plan` - Terraform plan output
- `:terraform-state` - Terraform state file
- `:code-changes` - Code diff or patch
- `:test-results` - Test execution results
- `:review-report` - Code review output
- `:plan-document` - Implementation plan
- `:architecture-diagram` - Design artifacts
- `:evidence-bundle` - Evidence bundle itself (meta)

### 3.2 Provenance Schema

```clojure
{:provenance/workflow-id uuid
 :provenance/phase keyword
 :provenance/agent keyword
 :provenance/agent-instance-id uuid

 :provenance/created-at inst
 :provenance/created-by-event-id uuid  ; Links to event stream

 :provenance/source-artifacts [uuid ...]  ; Input artifacts
 :provenance/tool-executions [...]        ; Tools used to create artifact

 :provenance/content-hash string          ; Duplicate for quick access
 :provenance/signature string}            ; OPTIONAL: Cryptographic signature
```

#### 3.2.1 Tool Execution Record

```clojure
{:tool/name keyword
 :tool/version string
 :tool/args {...}
 :tool/invoked-at inst
 :tool/duration-ms long
 :tool/exit-code long             ; For command-line tools
 :tool/output-summary string}     ; OPTIONAL: First N lines of output
```

---

## 4. Queryable Provenance

### 4.1 Required Query Operations

Implementations MUST support:

#### 4.1.1 Query Artifact Provenance

```clojure
(query-provenance artifact-store artifact-id)
;; Returns:
{:artifact {...}
 :workflow-id uuid
 :original-intent {...}
 :created-by-phase keyword
 :created-by-agent keyword
 :created-at inst
 :source-artifacts [...]          ; Prior artifacts in chain
 :subsequent-artifacts [...]      ; Artifacts created from this one
 :validation-results [...]        ; Policy checks at this phase
 :full-evidence-bundle {...}}
```

#### 4.1.2 Trace Artifact Chain

```clojure
(trace-artifact-chain artifact-store workflow-id)
;; Returns:
{:intent {...}
 :chain [{:phase :plan
          :agent :planner
          :artifacts [...]
          :timestamp inst}
         {:phase :implement
          :agent :implementer
          :artifacts [...]
          :timestamp inst}
         ...]
 :outcome {...}}
```

#### 4.1.3 Find Intent Mismatches

```clojure
(query-intent-mismatches artifact-store
                         {:time-range [start-inst end-inst]})
;; Returns workflows where declared intent ≠ actual behavior
[{:workflow-id uuid
  :declared-intent :import
  :actual-behavior :create
  :violation-details {...}}]
```

### 4.2 Query API

Implementations MUST provide:

```clojure
;; Get artifact by ID
(get-artifact artifact-store artifact-id)

;; Get all artifacts for workflow
(get-workflow-artifacts artifact-store workflow-id)

;; Get artifacts by phase
(get-artifacts-by-phase artifact-store workflow-id phase)

;; Get artifacts by type
(get-artifacts-by-type artifact-store artifact-type {:limit long :offset long})

;; Get evidence bundle
(get-evidence-bundle artifact-store workflow-id)
```

---

## 5. Evidence Bundle Generation

### 5.1 Bundle Creation Requirements

Implementations MUST:

1. **Create bundle at workflow completion** - Even if workflow fails
2. **Include all phase evidence** - For phases that executed
3. **Link to event stream** - Via event sequence ranges
4. **Generate semantic validation** - Check intent vs. behavior
5. **Include policy check results** - From all gates executed
6. **Calculate content hashes** - For all artifacts
7. **Make bundle immutable** - No modifications after creation

### 5.2 Bundle Storage Requirements

Implementations MUST:

1. **Store bundles durably** - Survive process restarts
2. **Index by workflow ID** - Fast retrieval
3. **Support partial reads** - Don't require loading entire bundle
4. **Compress large bundles** - But maintain queryability
5. **Retain indefinitely** - Unless explicit retention policy

### 5.3 Bundle Format

Implementations SHOULD use EDN (Extensible Data Notation) for:

- Human readability
- Machine parseability
- Clojure native support
- Schema validation

Implementations MAY support JSON for broader tooling compatibility.

---

## 6. Semantic Intent Validation

### 6.1 Validation Process

Implementations MUST:

1. **Extract declared intent** - From workflow spec
2. **Analyze implementation** - Count creates/updates/destroys
3. **Compare against rules** - See 2.4.1
4. **Generate violation report** - If mismatch detected
5. **Block on critical mismatches** - Prevent merge if intent violated

### 6.2 Terraform-Specific Validation

For Terraform workflows, implementations MUST:

1. **Parse terraform plan output** - Extract resource changes
2. **Categorize changes**:
   - Create: `+` or `+/-` (recreate)
   - Update: `~`
   - Destroy: `-` or `-/+` (recreate)
   - Import: `import` blocks (state-only, no `+/-~`)
3. **Count by category** - For semantic validation
4. **Flag dangerous patterns**:
   - Network resource recreates (`-/+` on `aws_route`, `aws_route_table_association`)
   - Database destroys (`-` on `aws_db_instance`)
   - Unintentional state changes (e.g., declared `:import` but sees `+`)

### 6.3 Kubernetes-Specific Validation

For Kubernetes workflows, implementations MUST:

1. **Parse kubectl diff/apply output**
2. **Detect resource lifecycle**:
   - Create: New resources in manifest
   - Update: Changed fields in existing resources
   - Destroy: Resources in cluster but not in manifest
3. **Validate against intent**

---

## 7. Compliance Metadata

### 7.1 Required Metadata

Evidence bundles MUST include:

```clojure
{:compliance/created-at inst
 :compliance/sensitive-data boolean    ; REQUIRED: Was sensitive data detected?
 :compliance/pii-handling keyword      ; REQUIRED: :none, :redacted, :encrypted
 :compliance/retention-policy keyword} ; OPTIONAL: :7-years, :indefinite
```

### 7.2 Sensitive Data Handling

Implementations MUST:

1. **Scan artifacts for sensitive data** - Before storing
2. **Detect patterns**:
   - AWS access keys (20-char uppercase alphanumeric)
   - Passwords in plaintext
   - SSNs, credit cards
3. **Redact or flag** - Replace with `[REDACTED:<type>]` or mark bundle as sensitive
4. **Record in metadata** - Set `compliance/sensitive-data` flag

### 7.3 Audit Trail Requirements

For SOCII/FedRAMP compliance, implementations MUST:

1. **Record all evidence bundle accesses** - Who, when, why
2. **Prevent tampering** - Immutable storage, content hashing
3. **Support export** - Evidence bundles exportable for auditors
4. **Maintain chain of custody** - From intent to outcome

---

## 8. Evidence Bundle Presentation

### 8.1 CLI Evidence View

Implementations MUST provide CLI command:

```bash
miniforge evidence show <workflow-id>
```

Displaying (at minimum):

- Intent summary
- Artifacts per phase
- Policy validation results
- Semantic validation results
- Outcome

### 8.2 TUI Evidence Browser

Implementations MUST provide TUI view with:

1. **Intent panel** - Type, description, constraints
2. **Phase tree** - Expandable phases with artifacts
3. **Validation results** - Policy checks, semantic validation
4. **Artifact viewer** - View artifact content (with syntax highlighting)
5. **Provenance trace** - Navigate artifact chain

### 8.3 Programmatic Access

Implementations MUST provide API:

```clojure
;; Get evidence bundle as data
(evidence/get-bundle artifact-store workflow-id)

;; Export as file
(evidence/export-bundle artifact-store workflow-id output-path)

;; Validate bundle integrity
(evidence/validate-bundle bundle)
```

---

## 9. Conformance & Testing

### 9.1 Evidence Bundle Validation

Implementations MUST validate:

1. **Schema compliance** - All required fields present
2. **Artifact references valid** - All artifact UUIDs exist
3. **Semantic validation correct** - Intent vs. behavior logic correct
4. **Content hashes match** - Artifact content matches hash
5. **Immutability** - Bundle unchanged after creation

### 9.2 Provenance Chain Tests

Conformance tests MUST verify:

1. **Complete traceability** - Can trace from artifact to intent
2. **Chain integrity** - Source artifacts link correctly
3. **Tool execution records** - All tools recorded
4. **Event stream linkage** - Events match phase evidence

### 9.3 Semantic Validation Tests

Conformance tests MUST verify:

1. **Correct violation detection** - `:import` intent with creates flagged
2. **No false positives** - Valid workflows pass validation
3. **Terraform parsing accuracy** - Correctly categorizes all change types
4. **Kubernetes parsing accuracy** - Correctly detects resource changes

---

## 10. Example Evidence Bundle

```clojure
{:evidence-bundle/id #uuid "bundle-123"
 :evidence-bundle/workflow-id #uuid "workflow-abc"
 :evidence-bundle/created-at #inst "2026-01-23T10:30:00Z"
 :evidence-bundle/version "1.0.0"

 ;; Intent
 :evidence/intent
 {:intent/type :import
  :intent/description "Import existing RDS instance to Terraform state"
  :intent/business-reason "Enable infrastructure-as-code management"
  :intent/constraints [{:constraint/type :no-resource-creation}
                       {:constraint/type :no-resource-destruction}]
  :intent/declared-at #inst "2026-01-23T10:00:00Z"
  :intent/author "chris@example.com"}

 ;; Plan Phase
 :evidence/plan
 {:phase/name :plan
  :phase/agent :planner
  :phase/started-at #inst "2026-01-23T10:00:05Z"
  :phase/completed-at #inst "2026-01-23T10:02:30Z"
  :phase/duration-ms 145000
  :phase/artifacts [#uuid "artifact-plan-doc"]
  :phase/event-stream-range {:start-seq 0 :end-seq 15}}

 ;; Implement Phase
 :evidence/implement
 {:phase/name :implement
  :phase/agent :implementer
  :phase/started-at #inst "2026-01-23T10:02:35Z"
  :phase/completed-at #inst "2026-01-23T10:08:10Z"
  :phase/duration-ms 335000
  :phase/artifacts [#uuid "artifact-terraform-plan"
                    #uuid "artifact-code-changes"]
  :phase/inner-loop-iterations 2
  :phase/event-stream-range {:start-seq 16 :end-seq 45}}

 ;; Verify Phase
 :evidence/verify
 {:phase/name :verify
  :phase/agent :tester
  :phase/started-at #inst "2026-01-23T10:08:15Z"
  :phase/completed-at #inst "2026-01-23T10:10:00Z"
  :phase/duration-ms 105000
  :phase/artifacts [#uuid "artifact-test-results"]
  :phase/event-stream-range {:start-seq 46 :end-seq 60}}

 ;; Semantic Validation
 :evidence/semantic-validation
 {:semantic-validation/declared-intent :import
  :semantic-validation/actual-behavior :import
  :semantic-validation/resource-creates 0
  :semantic-validation/resource-updates 0
  :semantic-validation/resource-destroys 0
  :semantic-validation/passed? true
  :semantic-validation/violations []
  :semantic-validation/checked-at #inst "2026-01-23T10:08:12Z"}

 ;; Policy Checks
 :evidence/policy-checks
 [{:policy-check/pack-id "terraform-aws"
   :policy-check/pack-version "1.0.0"
   :policy-check/phase :implement
   :policy-check/violations []
   :policy-check/passed? true
   :policy-check/checked-at #inst "2026-01-23T10:08:08Z"
   :policy-check/duration-ms 2500}]

 ;; Outcome
 :evidence/outcome
 {:outcome/success true
  :outcome/pr-number 234
  :outcome/pr-url "https://github.com/acme/terraform/pull/234"
  :outcome/pr-status :merged
  :outcome/pr-merged-at #inst "2026-01-23T11:00:00Z"}

 ;; Compliance
 :compliance/sensitive-data false
 :compliance/pii-handling :none
 :compliance/retention-policy :7-years}
```

---

## 11. Rationale & Design Notes

### 11.1 Why Evidence Bundles Matter

Evidence bundles make autonomous workflows **credible** because:

1. **Traceability** - "Why did we make this change?" → See original intent
2. **Validation** - "Does implementation match intent?" → Semantic validation proves it
3. **Compliance** - "Can we audit this?" → Complete audit trail
4. **Debugging** - "Why did this fail?" → Phase evidence + event stream
5. **Trust** - "Is it safe to merge?" → Policy checks + semantic validation

### 11.2 Why Semantic Intent Validation

Traditional CI/CD validates "does it work?" (tests, lints). miniforge validates **"does it do what you said it would do?"**

Example:

- Declared intent: `:import` (no infrastructure changes)
- Actual behavior: Created new security group (`:create`)
- **Violation detected** - Block merge, escalate to human

This catches:

- Accidental drift (meant to import, accidentally created)
- Scope creep (started as import, evolved into create)
- Malicious changes (declared import, hiding a backdoor create)

### 11.3 Why Immutable Evidence

Immutability provides:

- **Compliance** - Can't retroactively change audit trail
- **Debugging** - Evidence doesn't drift over time
- **Trust** - Users know evidence is authoritative

---

## 12. Future Extensions

### 12.1 Cryptographic Signatures (Post-OSS)

Evidence bundles will support:

- Digital signatures by agents
- Signature chains (planner → implementer → reviewer)
- Non-repudiation for compliance

### 12.2 Evidence Comparison (Paid)

Fleet-wide evidence will enable:

- "Show me all IMPORT workflows that created resources" (intent violations)
- "Compare this evidence to similar past workflows"
- "Learning from evidence patterns"

---

## 13. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N3 (Event Stream): Evidence bundles link to event streams via sequence ranges
- N2 (Workflow Execution): Workflows produce evidence bundles
- N4 (Policy Packs): Policy check results stored in evidence

---

**Version History:**

- 0.1.0-draft (2026-01-23): Initial evidence & provenance specification
