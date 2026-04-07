<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N1 — Core Architecture & Concepts

**Version:** 0.5.0-draft
**Date:** 2026-03-08
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **core architectural concepts** and **structural model** of
**MiniForge Core**, the governed workflow engine that powers Miniforge (the autonomous
software factory) and Data Foundry (ETL product). It establishes:

- **Core domain nouns** and their precise definitions
- **Three-layer architecture** that enables autonomous execution
- **Component boundaries** using Polylith architecture
- **Operational model** principles (local-first, reproducibility, failure semantics)
- **Agent protocols** and communication patterns

All other normative specifications (N2-N9) build upon the concepts defined here.

### 1.1 Design Principles

1. **Autonomous execution** - Agents execute workflows without human intervention
2. **Multi-agent cognition** - Agents collaborate as teammates, not tools
3. **Local-first** - Core functionality runs on local machine
4. **Reproducibility** - Same inputs MUST produce same outputs
5. **Observable** - All state changes MUST be observable via event stream
6. **Modular** - Components MUST have clean boundaries and interfaces

---

## 2. Core Domain Model

### 2.1 Workflow

A **workflow** is the top-level unit of autonomous execution.

```clojure
{:workflow/id uuid                  ; REQUIRED: Unique workflow identifier
 :workflow/type keyword             ; REQUIRED: :infrastructure-change, :feature, :refactor
 :workflow/status keyword           ; REQUIRED: :pending, :executing, :completed, :failed, :cancelled

 :workflow/spec {...}               ; REQUIRED: Workflow specification (see N2)
 :workflow/intent {...}             ; REQUIRED: Intent declaration (see N6)

 :workflow/current-phase keyword    ; OPTIONAL: :plan, :design, :implement, :verify, :review, :release, :observe
 :workflow/phases {...}             ; REQUIRED: Phase execution records

 :workflow/created-at inst          ; REQUIRED
 :workflow/started-at inst          ; OPTIONAL
 :workflow/completed-at inst        ; OPTIONAL

 :workflow/evidence-bundle-id uuid} ; OPTIONAL: Created upon completion (see N6)
```

#### 2.1.1 Workflow Requirements

Implementations MUST:

1. Assign unique UUID to each workflow
2. Track workflow status through lifecycle
3. Record all phase executions
4. Generate evidence bundle upon completion (success or failure)
5. Emit events for all state transitions (see N3)

### 2.2 Phase

A **phase** is a logical stage in the Software Development Lifecycle (SDLC).

```clojure
{:phase/name keyword               ; REQUIRED: :plan, :design, :implement, :verify, :review, :release, :observe
 :phase/status keyword             ; REQUIRED: :pending, :executing, :completed, :failed, :skipped

 :phase/agent keyword              ; REQUIRED: Primary agent for this phase
 :phase/input-context {...}        ; REQUIRED: Context from prior phases
 :phase/output-context {...}       ; OPTIONAL: Context for next phase

 :phase/started-at inst            ; OPTIONAL
 :phase/completed-at inst          ; OPTIONAL
 :phase/duration-ms long           ; OPTIONAL

 :phase/artifacts [uuid ...]       ; OPTIONAL: Artifacts produced
 :phase/gates [...]                ; OPTIONAL: Gates executed (see 2.7)
 :phase/inner-loop-iterations long} ; OPTIONAL: Validate/repair cycles
```

#### 2.2.1 Standard Phases

Implementations MUST support these phases:

| Phase         | Purpose                      | Primary Agent | Required Gates                     |
| ------------- | ---------------------------- | ------------- | ---------------------------------- |
| **Plan**      | Decompose intent into tasks  | Planner       | None                               |
| **Design**    | Create architecture/approach | Designer      | Architecture review                |
| **Implement** | Write code/config            | Implementer   | Policy validation, semantic intent |
| **Verify**    | Test implementation          | Tester        | Test pass/fail                     |
| **Review**    | Code review                  | Reviewer      | Review approval                    |
| **Release**   | Deploy to production         | Releaser      | Deployment validation              |
| **Observe**   | Capture learnings            | Observer      | None                               |

Implementations MAY support additional custom phases.

### 2.3 Agent

An **agent** is an autonomous software entity that executes a workflow phase.

```clojure
{:agent/id keyword                 ; REQUIRED: Agent type (:planner, :implementer, etc.)
 :agent/instance-id uuid           ; REQUIRED: Unique instance for this execution

 :agent/status keyword             ; REQUIRED: :idle, :starting, :executing, :completed, :failed
 :agent/current-activity string    ; OPTIONAL: Human-readable status

 :agent/context {:workflow/id uuid  ; REQUIRED: Workflow being executed
                 :phase/name keyword ; REQUIRED: Phase being executed
                 ...}               ; Context data

 :agent/memory {...}               ; OPTIONAL: Agent episodic memory (see 2.4)
 :agent/started-at inst            ; OPTIONAL
 :agent/completed-at inst}         ; OPTIONAL
```

#### 2.3.1 Standard Agents

Implementations MUST provide:

- **Planner** - Analyzes intent, creates implementation plan
- **Implementer** - Writes code, configuration, infrastructure
- **Tester** - Validates implementation against constraints
- **Reviewer** - Checks policy compliance, semantic intent
- **Observer** - Captures patterns for learning (see 2.9)

Implementations MAY provide additional specialized agents.

#### 2.3.2 Agent Protocol

All agents MUST implement:

```clojure
(defprotocol Agent
  (invoke [agent context]
    "Execute agent with given context. Returns {:output ... :artifacts [...] :next-context ...}")

  (get-status [agent]
    "Get current agent status")

  (abort [agent reason]
    "Abort agent execution"))
```

### 2.4 Subagent

A **subagent** is a specialized agent spawned by a parent agent for a specific subtask.

```clojure
{:subagent/id keyword              ; REQUIRED: Subagent type
 :subagent/instance-id uuid        ; REQUIRED: Unique instance

 :parent-agent/id keyword          ; REQUIRED: Parent agent type
 :parent-agent/instance-id uuid    ; REQUIRED: Parent instance

 :subagent/purpose string          ; REQUIRED: Why subagent was spawned
 :subagent/status keyword}         ; REQUIRED: Same statuses as agent
```

#### 2.4.1 Subagent Requirements

Implementations MUST:

1. Link subagent to parent agent via IDs
2. Emit events for subagent lifecycle (see N3)
3. Aggregate subagent results into parent agent output
4. Propagate failures from subagent to parent

### 2.5 Tool

A **tool** is an external capability invoked by an agent.

```clojure
{:tool/name keyword                ; REQUIRED: :read-file, :write-file, :run-command, etc.
 :tool/version string              ; REQUIRED: Tool implementation version
 :tool/args {...}                  ; REQUIRED: Tool-specific arguments

 :tool/invoked-by-agent keyword    ; REQUIRED: Agent that invoked tool
 :tool/invoked-at inst             ; REQUIRED
 :tool/completed-at inst           ; OPTIONAL
 :tool/duration-ms long            ; OPTIONAL

 :tool/result {...}                ; OPTIONAL: Tool execution result
 :tool/exit-code long              ; OPTIONAL: For command-line tools
 :tool/error string}               ; OPTIONAL: Error message if failed
```

#### 2.5.1 Tool Protocol

All tools MUST implement:

```clojure
(defprotocol Tool
  (invoke [tool args]
    "Execute tool with arguments. Returns {:result ... :exit-code ...}")

  (validate-args [tool args]
    "Validate arguments before execution")

  (get-schema [tool]
    "Return tool argument schema"))
```

### 2.6 Gate

A **gate** is a validation checkpoint that artifacts must pass before phase completion.

```clojure
{:gate/id keyword                  ; REQUIRED: Gate identifier
 :gate/type keyword                ; REQUIRED: :policy, :test, :lint, :security, :semantic-intent

 :gate/artifacts [uuid ...]        ; REQUIRED: Artifacts being validated
 :gate/status keyword              ; REQUIRED: :pending, :checking, :passed, :failed

 :gate/started-at inst             ; OPTIONAL
 :gate/completed-at inst           ; OPTIONAL
 :gate/duration-ms long            ; OPTIONAL

 :gate/violations [...]            ; OPTIONAL: If failed (see N4)
 :gate/remediation {...}}          ; OPTIONAL: How to fix violations
```

#### 2.6.1 Gate Execution Contract

All gates MUST implement:

```clojure
(defprotocol Gate
  (check [gate artifacts context]
    "Validate artifacts. Returns {:passed? boolean :violations [...] :remediation {...}}")

  (repair [gate artifacts violations context]
    "Attempt to repair violations. Returns {:repaired? boolean :artifacts [...]}"))
```

### 2.7 Policy Pack

A **policy pack** is a collection of validation rules applied at gates.

```clojure
{:policy-pack/id string            ; REQUIRED: Pack identifier (e.g., "terraform-aws")
 :policy-pack/version string       ; REQUIRED: Semantic version
 :policy-pack/name string          ; REQUIRED: Human-readable name

 :policy-pack/rules [...]          ; REQUIRED: Validation rules (see N4)
 :policy-pack/metadata {...}       ; OPTIONAL: Author, description, etc.

 :policy-pack/signature string}    ; OPTIONAL: Cryptographic signature
```

See N4 for detailed policy pack specification.

### 2.8 Evidence Bundle

An **evidence bundle** is an immutable record of workflow execution from intent to outcome.

```clojure
{:evidence-bundle/id uuid
 :evidence-bundle/workflow-id uuid
 :evidence-bundle/created-at inst

 :evidence/intent {...}            ; Original intent declaration
 :evidence/plan {...}              ; Plan phase evidence
 :evidence/implement {...}         ; Implement phase evidence
 :evidence/verify {...}            ; Verify phase evidence
 :evidence/review {...}            ; Review phase evidence

 :evidence/semantic-validation {...} ; Intent vs. behavior validation
 :evidence/policy-checks [...]    ; Policy validation results
 :evidence/outcome {...}}          ; Final outcome (PR, deployment, etc.)
```

See N6 for detailed evidence bundle specification.

### 2.9 Artifact

An **artifact** is a work product created during workflow execution.

```clojure
{:artifact/id uuid                 ; REQUIRED: Unique identifier
 :artifact/type keyword            ; REQUIRED: :code-changes, :terraform-plan, :test-results, etc.
 :artifact/content ...             ; REQUIRED: Type-specific content
 :artifact/content-hash string     ; REQUIRED: SHA-256 hash

 :artifact/created-at inst         ; REQUIRED
 :artifact/size-bytes long         ; REQUIRED

 :artifact/provenance {...}        ; REQUIRED: See N6
 :artifact/metadata {...}}         ; OPTIONAL: Type-specific metadata
```

See N6 for detailed artifact provenance specification.

#### 2.9.1 Data Foundry Extension Nouns

The following nouns are defined by the Data Foundry extension (see Data Foundry N1–N4) and recognised as first-class concepts within the Core domain model:

- **Dataset** — A versioned, structured collection of data with a formal schema, storage location, partitioning strategy, and complete provenance chain. Datasets are artifacts (`:artifact/type :dataset`) and MUST satisfy N6 §3.1 requirements. See Data Foundry N1.
- **Schema** — A formal, versioned definition of dataset structure including field names, types, constraints, and nullability rules. Schemas are versioned independently and MAY be shared across dataset versions. See Data Foundry N1.
- **Connector** — A governed tool (N10) that bridges Data Foundry and external systems for data ingestion or publication. Connectors declare capabilities, authentication requirements, and configuration schemas. See Data Foundry N2.
- **Pipeline** — A declarative DAG of stages that ingests, transforms, and publishes data. Pipelines are specialised workflows (N2) with data-specific execution semantics. See Data Foundry N3.
- **QualityRule** — A named, versioned data validation constraint that extends Core policy rules (N4) with data-specific check types and thresholds. See Data Foundry N4.
- **LineageGraph** — A directed acyclic graph tracking dataset dependencies and transformations, enabling impact analysis and reprocessing decisions. See Data Foundry N4.

### 2.10 Knowledge Base

A **knowledge base** is a structured repository of learnings from workflow executions.

```clojure
{:knowledge-base/id uuid
 :knowledge-base/type :zettelkasten ; Implementation approach

 :knowledge-base/units [...]       ; Knowledge units (notes)
 :knowledge-base/index {...}       ; Full-text and semantic index

 :knowledge-base/statistics
 {:total-units long
  :total-links long
  :last-updated inst}}
```

#### 2.10.1 Knowledge Unit Schema

```clojure
{:knowledge-unit/id uuid
 :knowledge-unit/type keyword      ; :pattern, :learning, :heuristic
 :knowledge-unit/title string
 :knowledge-unit/content {...}

 :knowledge-unit/tags [...]
 :knowledge-unit/links [...]       ; Links to other units

 :knowledge-unit/created-at inst
 :knowledge-unit/source-workflow-id uuid} ; OPTIONAL: If derived from workflow
```

#### 2.10.2 Knowledge Trust and Authority

Knowledge units MUST be labeled with an explicit trust model so the system can safely
incorporate information from user repositories and external sources.

- **Trust levels**
  - `:trusted` — authoritative, platform-validated and/or user-promoted content
  - `:untrusted` — repo-derived or externally sourced content; usable as _data_ only
  - `:tainted` — content flagged by scanners; MUST NOT be used as instruction

- **Authority channels**
  - `:authority/instruction` — may shape agent plans and execution (MUST be `:trusted`)
  - `:authority/data` — reference material only (may be `:untrusted` or `:trusted`)

Knowledge units SHOULD include a content hash and MAY include a cryptographic signature.

##### Transitive Trust Rules

Implementations MUST enforce these transitive trust rules:

1. **Instruction authority is not transitive:** If pack A (`:trusted`, `:authority/instruction`)
   references pack B (`:untrusted`), pack B MUST remain `:authority/data` and MUST NOT gain
   instruction authority through the reference.

2. **Trust level inheritance:** When pack A includes content from pack B, the resulting combined
   content MUST be assigned the lower trust level (`:tainted` < `:untrusted` < `:trusted`).

3. **Cross-trust references:** Packs MAY reference other packs of any trust level, but
   implementations MUST track and validate the transitive trust graph before allowing execution.

4. **Tainted isolation:** Content marked `:tainted` MUST NOT be included in any pack used for
   instruction authority, even transitively.

##### Trust Promotion and Revocation

**Trust promotion is one-way for a given pack version:** Once a pack version is promoted from
`:untrusted` to `:trusted`, that specific version MUST NOT be demoted back to `:untrusted`. This
prevents accidental trust downgrades and ensures immutability of trust decisions.

**Revocation mechanisms:** If a vulnerability or malicious content is discovered in a promoted
pack:

1. **Pack removal:** The pack MAY be removed from local registries and remote distribution.
2. **Key revocation:** If the pack was signed, the signing key MAY be revoked via key revocation
   lists (KRLs).
3. **Version deprecation:** The pack version MAY be marked as deprecated, warning users but not
   forcibly removing it.
4. **New version:** A corrected pack SHOULD be released as a new version, which starts at
   `:untrusted` and must be promoted separately.

Implementations SHOULD provide mechanisms to check for revoked packs and deprecated versions
during pack loading and workflow execution.

##### Expanded Knowledge Unit Schema

```clojure
{:knowledge-unit/id uuid
 :knowledge-unit/type keyword              ; :pattern, :learning, :heuristic, :feature-pack, :policy-pack, :agent-profile-pack
 :knowledge-unit/title string
 :knowledge-unit/content {...}

 :knowledge-unit/tags [...]
 :knowledge-unit/links [...]

 :knowledge-unit/trust-level keyword       ; REQUIRED: :trusted | :untrusted | :tainted
 :knowledge-unit/authority keyword         ; REQUIRED: :authority/instruction | :authority/data

 :knowledge-unit/content-hash string       ; OPTIONAL: sha256 over canonical representation
 :knowledge-unit/signature string          ; OPTIONAL: signature over content-hash

 :knowledge-unit/created-at inst
 :knowledge-unit/source-workflow-id uuid}  ; OPTIONAL: if derived from a workflow (including ETL)
```

#### 2.10.3 Pack Types

miniforge treats structured "packs" as first-class knowledge units and artifacts. Packs are EDN-serialized and
schema-validated.

**Core pack types (OSS):**

- **Feature Pack** (`:feature-pack`) — normalized feature intent, acceptance criteria, constraints, and references
- **Policy Pack** (`:policy-pack`) — deterministic validation rules and scanners (see N4)
- **Agent Profile Pack** (`:agent-profile-pack`) — agent routing, allowed capabilities, and scopes
- **Pack Index** (`:pack-index`) — manifest of generated packs, hashes, trust labels, and provenance
- **Workflow Pack** (`:workflow-pack`) — versioned bundle containing workflows, schemas, templates,
  and metadata for distributable domain workflows (see §2.24)

Policy Packs (N4) are a specialization of the general pack model: they follow the same
versioning, signing, and trust semantics but their content is deterministic validation
rules rather than workflow definitions. Workflow Packs generalize the pack model to
arbitrary domain workflows (e.g., reporting, product-brief pipelines).

Packs MUST be machine-readable and MUST NOT embed freeform prose as executable instruction.

##### Pack Versioning and Identity

All packs MUST include versioning information following this schema:

```clojure
{:pack/id string                      ; REQUIRED: unique identifier (e.g., "com.example/feature-auth")
 :pack/version string                 ; REQUIRED: semantic version (e.g., "1.2.3")
 :pack/type keyword                   ; REQUIRED: :feature-pack | :policy-pack | :agent-profile-pack | :workflow-pack
 :pack/created-at inst                ; REQUIRED: creation timestamp
 :pack/content-hash string            ; REQUIRED: sha256 over canonical EDN representation

 :pack/publisher string               ; REQUIRED for :workflow-pack: publisher identity
 :pack/miniforge-min-version string   ; REQUIRED for :workflow-pack: minimum runtime version

 :pack/dependencies                   ; OPTIONAL: dependencies on other packs
 [{:pack/id string
   :pack/version-constraint string    ; e.g., ">=1.0.0,<2.0.0"
   :pack/content-hash string}]        ; OPTIONAL: pin to specific content hash

 :pack/trust-level keyword            ; REQUIRED: :trusted | :untrusted | :tainted
 :pack/authority keyword              ; REQUIRED: :authority/instruction | :authority/data
 :pack/signature string               ; OPTIONAL: cryptographic signature over content-hash

 :pack/capabilities-required          ; REQUIRED for :workflow-pack: connector-scoped permissions
 [{:capability/id string              ; e.g., "github.pr.read", "jira.issue.write"
   :capability/scope keyword}]        ; :read | :write

 :pack/data-handling                  ; OPTIONAL: redaction and egress constraints
 {:redaction-policy keyword           ; :none | :pii-redact | :full-redact
  :egress-allowed? boolean}           ; default false

 :pack/entrypoints                    ; REQUIRED for :workflow-pack: named workflow entrypoints
 [{:entrypoint/name string
   :entrypoint/workflow-ref string    ; path within pack
   :entrypoint/input-schema {...}
   :entrypoint/output-schema {...}}]

 :pack/content {...}}                 ; Pack-specific content
```

Implementations MUST validate pack dependencies before loading and MUST reject circular dependencies.

#### 2.10.4 Pack Registry Roots and Loading

Implementations MUST support loading packs from a declared set of registry roots (e.g., a local
directory, a central repo checkout, or a remote registry in enterprise mode).

- Registry roots MUST be explicitly configured.
- Implementations MUST NOT implicitly ingest arbitrary repository markdown as instruction authority.
- Untrusted documents MAY be referenced by packs, but MUST be ingested only as `:authority/data`.

##### Signature Key Management

Implementations that support pack signing MUST provide:

1. **Key configuration:** Signing keys MUST be explicitly configured, not auto-generated or inferred from environment.

2. **Key storage:** Private keys MUST be stored securely (e.g., system keychain, HSM, or encrypted file with
  passphrase).

3. **Key verification:** Public keys for signature verification MUST be distributed through a
   trusted channel (e.g., configuration file, registry manifest).

4. **Key rotation:** Implementations SHOULD support key rotation with backward compatibility for previously signed
  packs.

5. **Revocation:** Implementations SHOULD support key revocation lists (KRLs) or revocation checking.

Minimal key configuration schema:

```clojure
{:pack-signing
 {:private-key-path string           ; Path to private key (PEM or keychain reference)
  :public-keys                        ; Trusted public keys for verification
  [{:key-id string
    :public-key string                ; PEM-encoded public key
    :valid-from inst                  ; Key validity period
    :valid-until inst                 ; OPTIONAL: expiration
    :revoked? boolean}]}}
```

Implementations MUST validate signatures using the `ed25519` algorithm (RECOMMENDED) or `rsa-sha256` (acceptable).

#### 2.10.5 ETL Pipelines (Repository → Packs)

miniforge SHOULD provide an ETL pipeline that converts existing repositories (docs/specs/rules)
into sanitized, schema-valid packs for immediate workflow use.

An ETL pipeline MUST:

1. Inventory candidate sources (by path/type/metadata)
2. Classify sources into candidate pack inputs
3. **Run deterministic sanitization and static scanners BEFORE extraction** (see N4
   "knowledge-safety") — scanners MUST NOT execute or evaluate untrusted content
4. Extract normalized EDN packs
5. Validate packs against schemas
6. Emit a pack index with content hashes and trust labels

ETL output packs MUST default to `:untrusted` until promoted or signed under an approved policy.

##### Incremental ETL

Implementations SHOULD support incremental ETL to avoid re-processing unchanged sources.

Incremental ETL MUST:

1. **Track source content hashes:** Compute and store content hashes for each source file processed.

2. **Skip unchanged sources:** If source file hash matches previously processed hash, skip re-extraction unless forced.

3. **Detect deletions:** If a previously processed source is no longer present, mark corresponding
   packs as stale or remove them from the index.

4. **Handle dependencies:** If source A depends on source B, and B changes, both MUST be re-processed.

5. **Maintain pack index:** Update pack index incrementally, preserving provenance of unchanged packs.

Implementations MAY cache intermediate classification and scanner results for performance.

### 2.11 Operational Policy (N7)

An **Operational Policy** is a versioned set of runtime configuration artifacts that control
service behavior under load. Operational Policies are specializations of Artifacts (§2.9)
with provenance per N6.

```clojure
{:operational-policy/id string       ; REQUIRED: policy identifier
 :operational-policy/version string  ; REQUIRED: semantic version
 :operational-policy/target-services [string ...] ; REQUIRED
 :operational-policy/target-envs [string ...]     ; REQUIRED

 :operational-policy/scaling {...}   ; Scaling signals, thresholds, bounds
 :operational-policy/resources {...} ; Requests/limits recommendations
 :operational-policy/guardrails {...} ; Rate limits, concurrency caps
 :operational-policy/verification-summary
 {:passed? boolean
  :confidence keyword
  :caveats [string ...]}

 :operational-policy/evidence-refs [uuid ...]} ; N6 evidence artifacts
```

See N7 for detailed Operational Policy specification.

### 2.12 Experiment Pack (N7)

An **Experiment Pack** is a versioned, declarative artifact that defines workload models,
guardrails, success criteria, and convergence strategies for operational policy synthesis.
Experiment Packs are specializations of Packs (§2.10.3).

```clojure
{:experiment-pack/id string          ; REQUIRED: stable identifier
 :experiment-pack/version string     ; REQUIRED: semantic version or revision

 :experiment-pack/targets
 {:services [...]                    ; Repo and/or runtime selectors
  :environments [...]}               ; Cluster/namespace selectors

 :experiment-pack/workload
 {:profile keyword                   ; step/ramp/spike
  :mix [...]                         ; Request classes and weights
  :warmup-seconds long
  :cooldown-seconds long}

 :experiment-pack/success-criteria {...}
 :experiment-pack/guardrails {...}
 :experiment-pack/convergence {...}
 :experiment-pack/actuation-intent keyword} ; :recommend-only, :pr-only, :apply-allowed
```

Experiment Packs SHALL be hash-addressed and recorded in the event stream and evidence bundle.
See N7 for detailed Experiment Pack specification.

### 2.13 Actuation Mode (N7)

An **Actuation Mode** governs whether OPSV workflows produce recommendations, PRs, or
direct changes:

- **RECOMMEND_ONLY**: produce policy proposals and evidence; no changes emitted.
- **PR_ONLY**: produce changes as PRs against declared repos.
- **APPLY_ALLOWED**: apply changes directly when permitted by policy packs and gates.

`APPLY_ALLOWED` MUST be disabled by default. See N7 §1.4.

### 2.14 Verification (N7)

**Verification** is the process of executing an Experiment Pack against a candidate
Operational Policy and producing an evidence bundle showing whether success criteria
are satisfied. Verification produces pass/fail with per-criterion results and
confidence/caveat fields. See N7 §6.

### 2.15 Listener (N8)

A **Listener** is an external actor that subscribes to workflow events and MAY interact
with workflow execution. Listeners are specializations of the Observer concept (§3.3)
extended with capability levels and identity.

```clojure
{:listener/id uuid                   ; REQUIRED: unique listener identifier
 :listener/type keyword              ; REQUIRED: :watcher, :dashboard, :fleet, :enterprise
 :listener/capability keyword        ; REQUIRED: :observe, :advise, :control

 :listener/identity                  ; REQUIRED for ADVISE/CONTROL
 {:principal string
  :credentials {...}
  :roles [keyword ...]}

 :listener/filters                   ; OPTIONAL
 {:workflow-ids [uuid ...]
  :event-types [keyword ...]
  :phases [keyword ...]
  :agents [keyword ...]}}
```

See N8 for detailed Listener specification.

### 2.16 Capability Level (N8)

Listeners operate at one of three **Capability Levels**:

| Level     | Permissions                                     | Use Case                          |
| --------- | ----------------------------------------------- | --------------------------------- |
| `OBSERVE` | Read-only event stream access                   | Monitoring, analytics, audit      |
| `ADVISE`  | Emit advisory annotations (non-blocking)        | Recommendations, warnings         |
| `CONTROL` | Request control actions (subject to gates)      | Pause, rollback, approve, adjust  |

Capability levels are hierarchical: CONTROL includes ADVISE includes OBSERVE.
See N8 §2 for enforcement requirements.

### 2.17 Control Action (N8)

A **Control Action** is a command that modifies workflow execution state. All control
actions MUST be authorized by RBAC, pass through policy gates, and be audit-logged.

```clojure
{:action/id uuid                     ; REQUIRED: unique action identifier
 :action/type keyword                ; REQUIRED: see N8 §3.1
 :action/timestamp inst              ; REQUIRED

 :action/target
 {:target-type keyword               ; :workflow, :agent, :gate, :fleet
  :target-id uuid}

 :action/requester
 {:principal string
  :capability keyword                ; Must be :control
  :listener-id uuid}

 :action/justification string        ; REQUIRED for High/Critical risk
 :action/result
 {:status keyword                    ; :success, :failure, :pending
  :executed-at inst}}
```

See N8 §3 for the complete control action surface.

### 2.18 Advisory Annotation (N8)

An **Advisory Annotation** is a non-blocking message attached to a workflow or event.
Annotations MUST NOT block workflow execution but MAY be surfaced in UI and MAY
trigger alerts if patterns match.

```clojure
{:annotation/id uuid                 ; REQUIRED
 :annotation/type keyword            ; REQUIRED: :recommendation, :warning, :insight, :question
 :annotation/source {:listener-id uuid :principal string}
 :annotation/target {:workflow-id uuid :event-id uuid}
 :annotation/content {:title string :body string :severity keyword}}
```

See N8 §4 for the annotation system specification.

### 2.19 PR Work Item (N9)

A **PR Work Item** is the canonical internal model of a pull request used by the
Fleet control plane. All PRs — Miniforge-originated and external — MUST be represented
as PR Work Items. This is distinct from Workflow (§2.1); a PR Work Item tracks
provider state, not workflow execution state.

```clojure
{:pr/id uuid                         ; REQUIRED: stable internal id
 :pr/provider keyword                ; REQUIRED: :github, :gitlab, etc.
 :pr/repo string                     ; REQUIRED: "org/name"
 :pr/number long                     ; REQUIRED: provider PR number
 :pr/external? boolean               ; REQUIRED: true if not Miniforge-originated

 :pr/readiness                       ; REQUIRED: deterministic merge-readiness
 {:readiness/state keyword           ; :merge-ready, :needs-review, :ci-failing, etc.
  :readiness/blockers [...]}

 :pr/risk                            ; REQUIRED: explainable risk assessment
 {:risk/level keyword                ; :low, :medium, :high, :critical
  :risk/factors [...]
  :risk/requires-human? boolean}

 :pr/automation-tier keyword         ; REQUIRED: see N9 §10

 :pr/workflow-id uuid}               ; OPTIONAL: absent for external PRs
```

See N9 for the complete PR Work Item schema and semantics.

### 2.20 Provider (N9)

A **Provider** is an external code hosting platform (GitHub, GitLab, etc.) that is a
source of PR events and state. Providers are analogous to N7's Environment Targets —
they are external systems that Miniforge connects to but does not own.

Implementations MUST support at least one provider and MUST normalize provider-native
events to canonical N3 event types. See N9 §3.

### 2.21 PR Train (N9)

A **PR Train** is an ordered set of PR Work Items with explicit dependency relationships
that MUST be merged in sequence. Trains are analogous to N2's DAG task dependencies,
applied to PR merge ordering.

```clojure
{:train/id uuid                      ; REQUIRED: stable train identifier
 :train/members                      ; REQUIRED: ordered PR Work Item refs
 [{:pr/id uuid
   :pr/repo string
   :pr/number long
   :train/position long}]            ; 1-indexed merge order

 :train/policy
 {:train/merge-strategy keyword      ; :sequential, :batch
  :train/required-readiness keyword
  :train/auto-merge? boolean}}       ; Requires Tier 3
```

See N9 §13 for train operations and governance.

### 2.22 Readiness (N9)

**Readiness** is a deterministic assessment of whether a PR is ready to merge, computed
from provider signals (CI, reviews, merge conflicts) and policy evaluation results.

Readiness states: `:merge-ready`, `:needs-review`, `:changes-requested`, `:ci-failing`,
`:policy-failing`, `:merge-conflicts`, `:unknown`.

See N9 §2.2 for readiness computation requirements.

### 2.23 Risk Assessment (N9)

A **Risk Assessment** is an explainable evaluation of change risk for a PR, produced as
an evidence artifact (N6) with traceable factors. Risk scoring MUST be explainable via
factor evidence refs and MUST NOT be a black-box number without factors.

See N9 §5 for risk artifact schema.

### 2.24 Workflow Pack

A **Workflow Pack** is a versioned, distributable bundle containing one or more workflow
entrypoints with their schemas, templates, and metadata. Workflow Packs are the primary
mechanism for delivering domain workflows (e.g., reporting, product-brief pipelines)
without bloating Miniforge core.

Workflow Packs are specializations of Packs (§2.10.3) with `:pack/type :workflow-pack`.

```clojure
{:workflow-pack/id string              ; REQUIRED: stable identifier (e.g., "com.miniforge/pr-review")
 :workflow-pack/version string         ; REQUIRED: semantic version
 :workflow-pack/publisher string       ; REQUIRED: publisher identity

 :workflow-pack/entrypoints            ; REQUIRED: at least one
 [{:entrypoint/name string
   :entrypoint/workflow-ref string     ; path within pack bundle
   :entrypoint/input-schema {...}      ; EDN schema for inputs
   :entrypoint/output-schema {...}}]   ; EDN schema for outputs

 :workflow-pack/capabilities-required  ; REQUIRED: connector-scoped permissions
 [{:capability/id string               ; e.g., "github.pr.read"
   :capability/scope keyword}]         ; :read | :write

 :workflow-pack/templates [...]        ; OPTIONAL: rendering templates
 :workflow-pack/policy-fragments [...] ; OPTIONAL: advisory by default
 :workflow-pack/data-handling {...}}    ; OPTIONAL: redaction/egress constraints
```

#### 2.24.1 Workflow Pack Requirements

Implementations MUST:

1. Verify pack signature before installation (if signature is present)
2. Record pack digest (`pack/content-hash`) in all Pack Run evidence
3. Present required capabilities to the user before install and before run
4. Deny connector actions not covered by granted capabilities
5. Enforce deny-by-default for write capabilities (`:capability/scope :write`)
6. Require re-approval when a pack update increases required capabilities
7. Resolve pack dependencies deterministically and record resolved versions/digests
8. Support loading packs from local bundles and configured registry roots

#### 2.24.2 No Ambient Authority

Packs MUST NOT inherit implicit privileges from the runtime environment. All connector
actions MUST be mediated by the capability enforcement layer. A pack that declares
`github.pr.read` MUST NOT be able to invoke `github.pr.comment.write` unless that
capability is also declared and granted.

### 2.25 Capability (Pack Permissions)

A **Capability** is a connector-scoped permission unit that a Workflow Pack declares
and the runtime enforces. Capabilities follow the pattern `<connector>.<resource>.<action>`.

Examples:

- `github.pr.read` — read PR metadata and diffs
- `github.pr.comment.write` — post comments on PRs
- `jira.issue.read` — read Jira issues
- `jira.issue.write` — create/update Jira issues
- `git.repo.checkout` — checkout repository content
- `metrics.query.read` — query metrics endpoints

#### 2.25.1 Capability Enforcement Requirements

Implementations MUST:

1. Maintain an explicit grant set per Pack Run (capabilities granted at install/run time)
2. Intercept all connector actions and validate against the grant set
3. Block and log denied actions with the attempted capability
4. Default write capabilities (any `*.write`) to denied unless explicitly granted
5. Record granted capabilities in Pack Run evidence (N6)
6. Record capability denials as events (N3)

### 2.26 Pack Run

A **Pack Run** is an execution instance of a Workflow Pack entrypoint. Each Pack Run
is an observable, auditable unit of work that produces evidence and artifacts.

```clojure
{:pack-run/id uuid                     ; REQUIRED: unique run identifier
 :pack-run/pack-id string              ; REQUIRED: workflow pack identifier
 :pack-run/pack-version string         ; REQUIRED: pack version executed
 :pack-run/pack-digest string          ; REQUIRED: content hash at run time
 :pack-run/entrypoint string           ; REQUIRED: entrypoint name
 :pack-run/status keyword              ; REQUIRED: :pending, :executing, :completed, :failed, :cancelled

 :pack-run/signature-verified? boolean ; REQUIRED: whether signature was verified
 :pack-run/capabilities-granted        ; REQUIRED: capabilities granted for this run
 [{:capability/id string
   :capability/scope keyword}]

 :pack-run/inputs {...}                ; REQUIRED: input values (redacted per data-handling)
 :pack-run/outputs {...}               ; OPTIONAL: output values upon completion

 :pack-run/workflow-id uuid            ; REQUIRED: workflow created for this run
 :pack-run/evidence-bundle-id uuid     ; OPTIONAL: created upon completion

 :pack-run/started-at inst             ; OPTIONAL
 :pack-run/completed-at inst}          ; OPTIONAL
```

#### 2.26.1 Pack Run Requirements

Implementations MUST:

1. Create a workflow (§2.1) for each Pack Run
2. Emit Pack Run events (see N3) for lifecycle transitions
3. Generate an evidence bundle (N6) that includes pack identity, digest, signature
   verification result, and granted capabilities
4. Enforce capabilities throughout the run (§2.25.1)
5. Support re-running with a pinned pack version/digest

### 2.27 Repo Index

A **Repo Index** is a content-addressed, incrementally buildable index of a repository at a
specific commit. It provides structured access to files, symbols, edges, and search backends
so that agents never need to scan repository trees directly.

```clojure
{:repo-index/schema-version "1.0"

 ;; identity
 :repo/id              string      ; REQUIRED: e.g. "org/repo"
 :repo/vcs             keyword     ; REQUIRED: :git
 :repo/default-branch  string      ; REQUIRED: e.g. "main"

 ;; revision being indexed
 :revision/commit-sha  string      ; REQUIRED
 :revision/tree-sha    string      ; REQUIRED
 :revision/parents     [string]    ; REQUIRED
 :revision/indexed-at  inst        ; REQUIRED

 ;; per-file blob inventory (content-addressed)
 :repo-index/files     [file-record]
 ;; symbol table (Tree-sitter baseline; SCIP/LSIF upgrade path)
 :repo-index/symbols   [symbol-record]     ; OPTIONAL: by coverage
 ;; typed graph edges between symbols/files
 :repo-index/edges     [edge-record]       ; OPTIONAL: by coverage
 ;; search backend references (artifacts live behind refs)
 :repo-index/search    search-config       ; REQUIRED: at least lexical
 ;; repo map for bounded global prior
 :repo-index/repo-map  repo-map-config     ; REQUIRED
 ;; coverage metadata (drives tool availability)
 :repo-index/coverage  coverage-record}    ; REQUIRED
```

#### 2.27.1 File Record

```clojure
{:file/path          string      ; REQUIRED: repo-relative
 :file/blob-sha      string      ; REQUIRED: content-addressed hash
 :file/lang          keyword     ; REQUIRED: e.g. :ts :clojure :python :go :rust
 :file/size-bytes    int         ; REQUIRED
 :file/is-generated? boolean     ; REQUIRED
 :file/package       string      ; OPTIONAL: logical package/module
 :file/exports       [string]    ; OPTIONAL
 :file/imports       [string]}   ; OPTIONAL
```

**`:file/is-generated?` determination:** A file MUST be classified as generated if it matches
any of: (a) contains a recognized generation header comment (e.g. `// Code generated by`,
`# DO NOT EDIT`), or (b) matches a configurable exclusion glob (e.g. `vendor/**`, `*.lock`,
`*_generated.*`, `*.pb.go`). Implementations MUST NOT use content heuristics beyond header
detection.

#### 2.27.2 Range

All code references MUST use the Range addressing scheme. Ranges are the atomic unit of
content-addressed code citation.

```clojure
{:range/path       string    ; REQUIRED: repo-relative file path
 :range/blob-sha   string    ; REQUIRED: immutable content hash
 :range/start-line int       ; REQUIRED: 1-based, inclusive
 :range/end-line   int       ; REQUIRED: 1-based, inclusive
 :range/start-byte int       ; OPTIONAL: byte offset (post-normalization)
 :range/end-byte   int}      ; OPTIONAL: byte offset (post-normalization)
```

**Range normalization rules (normative):**

- Line numbers MUST be 1-based and inclusive on both ends.
- Text encoding MUST be UTF-8.
- Line endings MUST be LF-normalized before range computation; `:range/blob-sha` MUST be
  computed over the LF-normalized content.
- Byte offsets, when present, MUST refer to positions in the LF-normalized UTF-8 content.

#### 2.27.3 Symbol

```clojure
{:symbol/id           string     ; REQUIRED: stable commit-scoped identifier
 :symbol/key          string     ; OPTIONAL: cross-commit logical identity (best-effort)
 :symbol/kind         keyword    ; REQUIRED: :fn :method :class :trait :interface :var
                                 ;           :const :type :ns :macro :protocol :multimethod
 :symbol/name         string     ; REQUIRED: local name
 :symbol/fqname       string     ; REQUIRED: fully-qualified name
 :symbol/container-id string     ; OPTIONAL: parent symbol/id
 :symbol/visibility   keyword    ; REQUIRED: :public :private :protected :internal
 :symbol/modifiers    #{keyword} ; OPTIONAL: #{:async :static :abstract :final ...}
 :symbol/range.signature range   ; REQUIRED: signature/declaration range
 :symbol/range.body     range    ; OPTIONAL: implementation body range
 :symbol/range.doc      range}   ; OPTIONAL: documentation/docstring range
```

**`:symbol/id` stability rules (normative):**

1. When SCIP index is available: use the SCIP symbol identifier (`scip:<symbol>`).
2. Fallback: `local:{lang}:{path}:{kind}:{name}:{sig-hash}:{start-line}-{end-line}` where
   `sig-hash` is the first 8 hex chars of SHA-256 over the UTF-8 signature text.
3. `:symbol/id` is scoped to a single commit; it MAY change across commits for the same
   logical symbol.

**`:symbol/key` stability rules (normative):**

1. When SCIP is available: derive from the SCIP symbol with range information stripped.
2. Fallback: `{lang}:{fqname}:{kind}` — best-effort cross-commit identity.
3. `:symbol/key` MUST be used for cross-commit cache lookups and continuity tracking.
4. Implementations MUST NOT assume `:symbol/key` uniqueness within a commit (overloads
   may share a key).

#### 2.27.4 Edge

```clojure
{:edge/type keyword   ; REQUIRED: :import :ref :call :implements :inherits
                      ;           :tests :owns :type-of :member-of
 :edge/from map       ; REQUIRED: {:file/path ...} or {:symbol/id ...}
 :edge/to   map}      ; REQUIRED: {:file/path ...} or {:symbol/id ...}
```

#### 2.27.5 Search Configuration

```clojure
{:search/lexical {:lex/engine keyword        ; REQUIRED: :bm25
                  :lex/artifact-ref string}   ; REQUIRED
 :search/vectors [{:vec/space keyword         ; REQUIRED: :code :docs
                   :vec/model string           ; REQUIRED
                   :vec/artifact-ref string}]} ; OPTIONAL: empty if no vectors
```

#### 2.27.6 Repo Map Configuration

```clojure
{:repomap/token-budget-profiles
 {:tiny    {:repomap/max-tokens int}
  :default {:repomap/max-tokens int}
  :large   {:repomap/max-tokens int}}
 :repomap/artifact-ref string}
```

#### 2.27.7 Coverage Record

```clojure
{:coverage/symbols?  boolean   ; REQUIRED: Tree-sitter or better
 :coverage/scip?     boolean   ; REQUIRED
 :coverage/lsif?     boolean   ; REQUIRED
 :coverage/calls?    keyword   ; REQUIRED: :none :approx :precise
 :coverage/refs?     keyword}  ; REQUIRED: :none :approx :precise
```

Coverage drives tool availability: `nav.calls` and `nav.refs` MUST return errors (not empty
results) when the corresponding coverage level is `:none`.

#### 2.27.8 Repo Index Requirements

Implementations MUST:

1. Produce a `RepoIndex` for every `repo@commit` used in any workflow execution.
2. Content-address all index data; unchanged blobs MUST NOT be re-embedded,
   re-summarized, or re-indexed.
3. Include at minimum: `files`, `search/lexical`, `repo-map`, and `coverage`.
4. Make `symbols`, `edges`, and `search/vectors` available when coverage supports them.
5. Expose a stable `:symbol/id` scheme per §2.27.3.
6. Store the index manifest as a small JSON/EDN file; heavyweight structures (postings
   lists, vector shards, SQLite) MUST live behind `:artifact-ref` pointers.
7. Support incremental updates: re-indexing MUST process only changed blobs.

#### 2.27.9 Index Quality Metrics

Implementations MUST track index quality to support retrieval governance and degradation
detection. Quality metrics MUST be computed after each incremental index update.

```clojure
{:index-quality/repo-id       string   ; REQUIRED: repository identifier
 :index-quality/commit-sha    string   ; REQUIRED: commit at which quality was measured
 :index-quality/freshness-lag-ms long  ; REQUIRED: ms since last indexed commit
 :index-quality/coverage-score double  ; REQUIRED: 0.0-1.0 fraction of files indexed
 :index-quality/symbol-coverage double ; OPTIONAL: fraction of files with symbol data
 :index-quality/search-recall  double  ; OPTIONAL: measured via canary queries (§2.27.10)
 :index-quality/computed-at    inst}   ; REQUIRED
```

Implementations MUST:

1. Compute `:index-quality/coverage-score` and `:index-quality/freshness-lag-ms` after
   every incremental update.
2. Include quality metrics in workflow evidence bundles when repo context is consumed
   (see N6).
3. Emit `:repo-index/quality-computed` events (see N3 §3.18).

#### 2.27.10 Canary Protocol

Implementations SHOULD support index canary validation to detect retrieval regressions:

1. **Golden queries** — Maintain a set of labeled queries with known-good result sets per
   repository. Golden queries are artifacts stored per N6.
2. **Canary execution** — After each incremental index update, run golden queries against
   the updated index.
3. **Recall threshold** — If canary recall drops below configured threshold (default 0.8),
   emit `:repo-index/canary-failed` event (see N3 §3.18) and mark index as degraded.
4. **Degraded index handling** — Degraded indexes MUST trigger re-indexing or fallback to
   full scan. Implementations MUST NOT silently serve degraded results.
5. **Canary set evolution** — Golden query sets SHOULD be updated when new retrieval
   failures are identified in production, closing the eval feedback loop (see §3.3.3).

### 2.28 Context Pack

A **Context Pack** is a bounded, auditable context document assembled by the orchestrator
and supplied to agents as their primary repository context. Agents MUST NOT scan
repositories directly; they operate on Context Packs.

```clojure
{:context-pack/schema-version "1.0"
 :repo/id          string
 :revision/commit-sha string
 :task/id          string
 :context/built-at inst

 ;; global prior under strict token budget
 :context/repo-map-slice repo-map-slice

 ;; preferred: symbols (signature/doc first; bodies only when needed)
 :context/symbols [context-symbol]

 ;; last-resort: explicit content snippets
 :context/snippets [context-snippet]

 ;; canonical citations for claims/edits
 :context/citations [citation]

 ;; retrieval budgets (enforced by orchestrator)
 :context/constraints constraint-envelope

 ;; policy envelope (swappable independently of content)
 :context/policy policy-envelope

 ;; audit preamble
 :context/audit audit-record}
```

#### 2.28.1 Repo Map Slice

```clojure
{:repomap/token-budget int
 :repomap/items
 [{:file/path string
   :file/summary string}]}
```

#### 2.28.2 Context Symbol

```clojure
{:symbol/id        string
 :symbol/signature string
 :symbol/ranges    [range]
 :context/why      why-record}
```

#### 2.28.3 Context Snippet

```clojure
{:snippet/range   range
 :snippet/content string
 :context/why     why-record}
```

#### 2.28.4 Citation

```clojure
{:cite/use   keyword    ; REQUIRED: :edit-target :evidence :justification
                        ;           :novel-code :synthesis :policy-output
 :cite/range range      ; REQUIRED for :edit-target :evidence :justification
 :cite/note  string}    ; OPTIONAL: free-text for :novel-code :synthesis
```

**Citation modes:**

- `:edit-target` — the cited range is being modified by this agent.
- `:evidence` — the cited range supports a claim about code behavior.
- `:justification` — the cited range explains why a decision was made.
- `:novel-code` — new code with no direct source range; `:cite/note` MUST explain provenance.
- `:synthesis` — code synthesized from multiple sources; `:cite/note` MUST list contributing
  ranges or describe the synthesis.
- `:policy-output` — edit driven by policy/tool output rather than source code.

#### 2.28.5 Why Record (structured retrieval justification)

```clojure
{:why/reason  keyword    ; REQUIRED: :search-match :graph-nav :manual-request
                         ;           :dependency :test-failure :policy-rule
 :why/query   string     ; OPTIONAL: search query that produced this result
 :why/score   double     ; OPTIONAL: relevance score
 :why/note    string}    ; OPTIONAL: free-text addendum
```

#### 2.28.6 Constraint Envelope

```clojure
{:limits/max-open-calls          int   ; REQUIRED
 :limits/max-total-snippet-tokens int  ; REQUIRED
 :limits/max-distinct-files-opened int ; REQUIRED
 :limits/max-search-calls         int  ; REQUIRED
 :limits/max-search-preview-tokens int ; REQUIRED
 :limits/max-lines-per-open       int  ; REQUIRED: hard cap per repo.open call
 :limits/max-bytes-per-open       int} ; REQUIRED: hard cap per repo.open call
```

#### 2.28.7 Policy Envelope

```clojure
{:policy/budget-profile   keyword  ; REQUIRED: workflow-phase-derived
 :policy/on-exhaustion    keyword  ; REQUIRED: :fail-closed :request-escalation
 :policy/escalation-target string} ; OPTIONAL: who to escalate to
```

The Policy Envelope is separable from content so the orchestrator can tune budgets without
rebuilding the Context Pack, and can A/B test budget profiles cleanly.

#### 2.28.8 Context Pack Requirements

Implementations MUST:

1. Supply a `ContextPack` as the primary repository context for every agent invocation.
2. Enforce all limits in `:context/constraints` and `:context/policy`.
3. Deduplicate snippet payloads by `(blob-sha, range)` across all agents in a task.
4. Include an audit record linking to all sources used to build the pack.
5. Support incremental extension via `context.extend` without full rebuild.

Agents MUST:

1. Start with `:context/repo-map-slice` and search results before requesting snippets.
2. Request symbols by `:symbol/id` wherever possible (signature/doc first; body only if needed).
3. Cite ranges (`path + blob-sha + lines`) for every claim about code behavior.
4. Use appropriate `:cite/use` modes — `:novel-code` or `:synthesis` when no direct source exists.

### 2.29 Context Staleness and Invalidation

When the working tree changes during agent execution (e.g. another agent's edit lands, or
the user commits), the orchestrator MUST detect stale context and act.

#### 2.29.1 Staleness Detection

A Context Pack is **stale** when any `:range/blob-sha` in its citations, symbols, or snippets
no longer matches the current tree at `:revision/commit-sha`.

Implementations MUST:

1. Check blob-sha validity before applying edits from a stale Context Pack.
2. Track which agents hold which Context Pack versions.

#### 2.29.2 Staleness Response

On detecting a stale Context Pack, the orchestrator MUST choose one of:

1. **Invalidate and rebuild** — discard the stale pack, rebuild from the new commit, and
   re-invoke the agent. This is the default for `:fail-closed` policy.
2. **Merge** — compute a delta between old and new tree, patch the Context Pack with
   updated ranges, and continue. This is permitted only when the changed blobs do not
   overlap with any cited ranges.
3. **Fail the task** — mark the task as failed with a staleness error. Required when merge
   is not possible and rebuild budget is exhausted.

Implementations SHOULD prefer invalidate-and-rebuild in v1 and add merge support in later
versions.

### 2.30 Repo Context Tool Contract

The following tools define the surface area through which agents interact with repository
context. These tools follow MCP conventions and are budgeted by the orchestrator.

#### 2.30.1 Index and Navigation Tools

| Tool | Args | Returns | Notes |
|------|------|---------|-------|
| `repo.index-status` | `repo-id, commit-sha` | `{:index/ready?, :schema/version, :coverage}` | Check if index exists |
| `repo.map` | `repo-id, commit-sha, query?, budget-profile?` | `repo-map-slice` | Global prior; first call |
| `repo.search-lex` | `repo-id, commit-sha, query, filters?` | `[{:hit/path, :hit/line, :hit/preview, :hit/score}]` | BM25 text search |
| `repo.search-sem` | `repo-id, commit-sha, query, filters?` | `[{:item/kind, :symbol/id?, :range?, :item/score, :item/why}]` | Vector/semantic search |
| `repo.symbol` | `repo-id, commit-sha, symbol-id, include-body?` | `symbol-record` | Signature/doc by default |
| `repo.open` | `repo-id, commit-sha, path, start-line, end-line` | `{:snippet/range, :snippet/content}` | Last resort; capped |

**`repo.open` guardrails (normative):**

- MUST enforce `:limits/max-lines-per-open` (default: 200 lines).
- MUST enforce `:limits/max-bytes-per-open` (default: 50 KB).
- MUST reject requests for full-file reads when a narrower symbol/range is available in the index.
- MUST reject `start-line=1, end-line=EOF` patterns unless the file is smaller than the line cap.

#### 2.30.2 Graph Navigation Tools

Available only when `:coverage/scip?` or `:coverage/lsif?` is true, or when
`:coverage/refs?` / `:coverage/calls?` is not `:none`.

| Tool | Args | Returns | Notes |
|------|------|---------|-------|
| `nav.def` | `repo-id, commit-sha, symbol-id` | `symbol-id` | Go to definition |
| `nav.refs` | `repo-id, commit-sha, symbol-id, limit?` | `[range \| symbol-id]` | Find references |
| `nav.impls` | `repo-id, commit-sha, symbol-id` | `[symbol-id]` | Find implementations |
| `nav.calls` | `repo-id, commit-sha, symbol-id, direction?, limit?` | `[symbol-id]` | Call graph |

Graph tools MUST return an error (not empty results) when the required coverage level is
`:none`.

#### 2.30.3 Orchestrator-Only Tools

These tools are invoked by the orchestrator, not by agents directly. Agent attempts to call
these MUST be rejected.

| Tool | Args | Returns | Notes |
|------|------|---------|-------|
| `context.build` | `task-id, repo-id, commit-sha, query, budget-profile` | `ContextPack` | Initial pack assembly |
| `context.extend` | `task-id, request, budget-delta?` | `ContextPackPatch` | Incremental extension |
| `context.audit` | `task-id` | `{:audit/open-calls, :audit/snippet-tokens, ...}` | Budget + source trace |

#### 2.30.4 Budget Accounting

**All** tool calls — including search — MUST be metered against the constraint envelope:

- `repo.search-lex` and `repo.search-sem` MUST count against `:limits/max-search-calls` and
  their preview payloads against `:limits/max-search-preview-tokens`.
- `repo.open` MUST count against `:limits/max-open-calls`, `:limits/max-total-snippet-tokens`,
  and `:limits/max-distinct-files-opened`.
- `repo.symbol` with `include-body?=true` MUST count against snippet token limits.

#### 2.30.5 Default Budgets by Workflow Phase

Implementations SHOULD use these defaults (tunable per-repo):

| Phase | max-open-calls | max-snippet-tokens | max-distinct-files | max-search-calls | max-search-preview-tokens |
|-------|---------------|-------------------|-------------------|-----------------|-------------------------|
| Plan | 2 | 1200 | 4 | 6 | 800 |
| Implement | 8 | 3500 | 10 | 12 | 1500 |
| Test | 5 | 2200 | 8 | 8 | 1000 |
| Review | 6 | 2800 | 12 | 10 | 1200 |

On budget exhaustion with `:fail-closed` policy: the tool call MUST return an error with
remaining budget state. The agent MAY request escalation via `context.extend` if the policy
envelope permits `:request-escalation`.

---

## 3. Three-Layer Architecture

miniforge is structured as three cooperating layers:

```text
┌─────────────────────────────────────────────────────────────┐
│                    CONTROL PLANE                             │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐        │
│  │ Operator   │───►│ Workflows  │───►│  Policy    │        │
│  │ Agent      │    │ Engine     │    │  Engine    │        │
│  └────────────┘    └────────────┘    └────────────┘        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  AGENT LAYER (Workers)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Planner  │─►│Implementer│─►│  Tester  │─►│ Reviewer │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
│                                     │                        │
│                                     ▼                        │
│                            ┌──────────────┐                 │
│                            │ Inner Loop   │                 │
│                            │ (validate &  │                 │
│                            │  repair)     │                 │
│                            └──────────────┘                 │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   LEARNING LAYER                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Observer │─►│  Meta    │─►│ Heuristic│─►│ Knowledge│   │
│  │ Agent    │  │  Loop    │  │ Registry │  │  Base    │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 Control Plane

The **Control Plane** coordinates workflow execution.

#### 3.1.1 Responsibilities

The Control Plane MUST:

1. Accept workflow specifications from users
2. Initialize workflow execution context
3. Orchestrate phase transitions
4. Enforce policy gates
5. Generate evidence bundles
6. Provide operational visibility

#### 3.1.2 Components

- **Operator Agent** - Manages workflow lifecycle
- **Workflow Engine** - Executes phase graph (see N2)
- **Policy Engine** - Enforces policy packs (see N4)

### 3.2 Agent Layer

The **Agent Layer** performs the actual work of software development.

#### 3.2.1 Responsibilities

The Agent Layer MUST:

1. Execute specialized tasks (planning, coding, testing, reviewing)
2. Coordinate between agents via context handoff
3. Validate work via inner loop (see N2)
4. Communicate with other agents when needed
5. Emit status events for observability (see N3)

#### 3.2.2 Components

- **Specialized Agents** - Planner, Implementer, Tester, Reviewer (see 2.3)
- **Inner Loop** - Validation and repair mechanism (see N2)
- **Tool Integrations** - File I/O, command execution, LLM calls

### 3.3 Learning Layer

The **Learning Layer** enables continuous improvement.

#### 3.3.1 Responsibilities

The Learning Layer MUST:

1. Observe workflow executions
2. Extract patterns from successful/failed workflows
3. Update heuristics based on performance
4. Maintain knowledge base of learnings
5. Provide context to agents from past executions

#### 3.3.2 Components

- **Observer Agent** - Captures execution signals
- **Meta Loop** - Analyzes patterns, proposes improvements
- **Heuristic Registry** - Stores and versions prompt heuristics
- **Knowledge Base** - Zettelkasten-based learning repository

#### 3.3.3 Evaluation Pipeline

The Learning Layer MUST support a closed-loop evaluation pipeline for detecting regressions
and validating changes to prompts, models, policies, tool schemas, and indexes.

**Evaluation mechanisms:**

1. **Golden Sets** — Curated workflow inputs paired with known-good outcomes. Golden sets
   are stored as N6 evidence artifacts (`:artifact/type :golden-set`). Implementations
   MUST support running a golden set against the current system and comparing actual
   outcomes to expected outcomes.

2. **Replay Mode** — Re-execute a completed workflow from its evidence bundle and event
   stream to verify deterministic reconstruction. Replay MUST use the same tool versions
   and context pack contents as the original execution. LLM calls MAY differ (non-deterministic)
   but structural workflow progression MUST match.

3. **Shadow Mode** — Run a candidate system configuration (new model, prompt, policy, or
   tool schema version) in parallel with the production configuration. Shadow runs MUST
   NOT produce side effects. Implementations MUST compare shadow outcomes to production
   outcomes and record divergence as evaluation evidence.

4. **Canary Deployment** — Route a configurable fraction of new workflows to a candidate
   system configuration. Canary runs produce real side effects and MUST be monitored
   against SLIs (see §5.5). If canary SLIs regress beyond configured thresholds,
   implementations SHOULD automatically roll back to the prior configuration.

```clojure
;; Golden Set Artifact (stored per N6)
{:artifact/type :golden-set
 :artifact/content
 {:golden-set/id       string       ; REQUIRED: unique identifier
  :golden-set/entries
  [{:entry/id           string      ; REQUIRED: entry identifier
    :entry/workflow-spec map        ; REQUIRED: input workflow spec
    :entry/expected-outcome map     ; REQUIRED: expected outcome shape
    :entry/pass-criteria [map]      ; REQUIRED: predicates on outcome
    :entry/source        keyword    ; OPTIONAL: :manual :incident :production
    :entry/tags          [string]}] ; OPTIONAL: for filtering
  :golden-set/version   string      ; REQUIRED: semantic version
  :golden-set/created-at inst}}     ; REQUIRED
```

Implementations SHOULD:

1. Populate golden sets from production incidents — every classified failure (§5.3.3)
   is a candidate golden set entry.
2. Run golden sets on every change to prompts, models, policies, or tool schema versions.
3. Gate deployments on golden set pass rate — a regression in pass rate SHOULD block the
   change.
4. Track golden set coverage across workflow types and failure classes.

---

## 4. Polylith Component Boundaries

miniforge uses **Polylith architecture** for component organization.

### 4.1 Component Structure

```text
components/
├── schema/              # Domain types and schemas
├── logging/             # Structured logging (EDN)
├── llm/                 # LLM client abstraction
├── tool/                # Tool protocol and implementations
├── agent/               # Agent runtime + specialized agents
├── task/                # Task management
├── loop/                # Inner/outer loop execution
├── workflow/            # Workflow orchestration
├── knowledge/           # Knowledge base (Zettelkasten)
├── policy/              # Policy engine
├── policy-pack/         # Policy pack registry
├── heuristic/           # Heuristic registry
├── artifact/            # Artifact store and provenance
├── gate/                # Validation gates
└── observer/            # Pattern observation

bases/
├── cli/                 # Command-line interface
└── local-fleet/         # Local operations console (TUI/Web)

projects/
└── oss-cli/             # OSS build configuration
```

### 4.1.1 Distribution Profiles (OSS vs Enterprise)

miniforge supports multiple distribution profiles built from the same core architecture.

#### OSS Profile (miniforge OSS)

OSS builds MUST include:

- Workflow engine + standard phases
- Policy pack runtime (deterministic gates)
- Evidence bundle generation (N6)
- Local knowledge base
- **ETL core**: repository → sanitized packs (`feature-pack`, `policy-pack`, `agent-profile-pack`)
- Static “knowledge-safety” scanners (deterministic)
- Pack validation, hashing, and local promotion workflow

OSS builds SHOULD exclude:

- Hosted pack registry services
- Organization-wide RBAC/SSO integrations
- Multi-tenant fleet orchestration

#### Enterprise Profile (miniforge Enterprise)

Enterprise builds MAY add:

- Central pack registry + approval workflows
- Continuous ETL across org repositories and external systems (Jira/Confluence/Notion, etc.)
- Fleet orchestration and scheduling
- SSO/RBAC enforcement and policy-managed trust promotion
- Advanced compliance controls (retention, audit exports, DLP integrations)

Enterprise extensions MUST NOT weaken the OSS trust model. Instruction authority MUST remain gated by pack trust and
policy.

### 4.2 Component Interface Requirements

All components MUST:

1. Define clear public interface in `interface.clj`
2. Keep implementation details in `impl.clj`
3. Declare dependencies explicitly
4. Provide unit tests
5. Document public API

#### 4.2.1 Example Component Interface

```clojure
;; components/workflow/src/ai/miniforge/workflow/interface.clj

(ns ai.miniforge.workflow.interface)

(defn create-engine
  "Create workflow engine instance"
  [config]
  ...)

(defn execute
  "Execute workflow with given spec and context"
  [engine spec context]
  ...)

(defn get-status
  "Get workflow execution status"
  [engine workflow-id]
  ...)
```

#### 4.2.2 Polylith Stability Tagging

Implementations MUST use git tags to mark stable points for Polylith test tracking.

**When to create stable tags:**

1. After **E2E test verification** - All integration tests pass
2. After **dogfooding runs** - Core workflows tested in real usage
3. Before **release candidates** - Preparing for production deployment
4. After **major milestone completion** - Significant features fully functional

**Stable tag format:**

```bash
git tag -a stable-YYYYMMDD -m "Stable: <milestone description>"
git push --tags
```

Example:

```bash
git tag -a stable-20260206 -m "Stable: DAG orchestration + PR lifecycle integration"
```

**DO NOT tag on every merge:**

- Stable tags represent verified production-ready snapshots
- Tagging every merge defeats incremental test tracking
- Between stable points, `poly info` shows which components have changed since last verification
- This enables focused testing of changed components only

**Verification checklist before tagging stable:**

- [ ] All `poly test` runs pass (0 failures, 0 errors)
- [ ] Pre-commit hooks pass on all changed files
- [ ] E2E integration tests pass
- [ ] Dogfooding: Core workflows execute successfully in real repository
- [ ] No known P0/P1 bugs in changed components

**Stable tag lifecycle:**

```text
[Initial Commit] → [Development] → [E2E Pass] → [Dogfood Success] → [Tag Stable]
     stable-init      stx flags       verify         verify           stable-YYYYMMDD
                      (tests marked)                               (tests unmarked)
```

After tagging stable, `poly info` will show `s--` (stable, has tests, not marked) for
unchanged components and `stx` only for components modified since the tag.

### 4.3 OSS Component Boundaries

The following components are **Open Source** (Apache 2.0):

- ✅ All components listed in 4.1
- ✅ CLI base
- ✅ Local fleet base

The following components are **Enterprise** (proprietary):

- ❌ `fleet-distributed/` - Multi-instance coordination
- ❌ `fleet-analytics/` - Org-wide analytics
- ❌ `authz/` - RBAC, approvals, audit
- ❌ `knowledge-shared/` - Org-wide knowledge sharing
- ❌ `policy-distribution/` - Central policy marketplace

---

## 5. Operational Model

### 5.1 Local-First Execution

#### 5.1.1 Requirements

Implementations MUST:

1. Run entirely on local machine without external dependencies (except LLM API)
2. Store all workflow state locally
3. Support offline execution (with cached LLM responses)
4. Persist state across process restarts

#### 5.1.2 Local State Storage

Implementations MUST store:

- Workflow execution state
- Artifact store
- Event stream history
- Knowledge base
- Evidence bundles

Default location: `~/.miniforge/`

### 5.2 Reproducibility

#### 5.2.1 Determinism Requirements

Implementations MUST ensure:

1. Same workflow spec + same context → same plan (deterministic planner)
2. Same code + same tests → same validation result
3. Event stream replay → same workflow state reconstruction

#### 5.2.2 Non-Determinism Sources

Implementations MUST document and isolate:

- LLM responses (non-deterministic by nature)
- Timestamps (record but don't use in business logic)
- External tool outputs (version in tool execution records)

### 5.3 Failure Semantics

#### 5.3.1 Failure Handling Requirements

Implementations MUST:

1. **Fail-safe** - Workflow failure MUST NOT corrupt state
2. **Fail-visible** - All failures MUST emit events and create evidence bundle
3. **Fail-recoverable** - Workflows MUST be resumable from last successful phase
4. **Fail-escalatable** - Agent failures MUST escalate to human after retry budget exhausted

#### 5.3.2 Retry Budget

Implementations SHOULD implement retry budgets:

- Inner loop iterations: 3-5 attempts
- Agent retries: 2-3 attempts
- LLM timeouts: 60s with exponential backoff

#### 5.3.3 Canonical Failure Taxonomy

All workflow, agent, tool, and system failures MUST be classified into a canonical
taxonomy. This taxonomy enables structured failure analysis, SLI computation (§5.5),
and targeted remediation.

```clojure
{:failure/class keyword   ; REQUIRED on all failure events (see N3)
 ;; Canonical values:
 ;;   :failure.class/agent-error       — Agent logic defect, prompt failure, hallucination
 ;;   :failure.class/task-code         — User code, spec, or test failure
 ;;   :failure.class/tool-error        — Tool returned an error or unexpected result
 ;;   :failure.class/external          — Third-party service unavailable or errored
 ;;   :failure.class/policy            — Policy gate or validation rejected execution
 ;;   :failure.class/resource          — Budget exhausted (tokens, time, retries, cost)
 ;;   :failure.class/timeout           — Wall-clock or capability TTL exceeded
 ;;   :failure.class/concurrency       — Deadlock, resource lock contention, merge conflict
 ;;   :failure.class/data-integrity    — Content hash mismatch, stale context, schema violation
 ;;   :failure.class/unknown           — Unclassified failure
 }
```

**Classification requirements:**

1. Failure classification MUST be performed by the runtime (orchestrator or agent-runtime
   component), not by the agent itself — agents are unreliable classifiers of their own
   failures.
2. Every failure event emitted per N3 MUST carry `:failure/class` in addition to the
   existing human-readable `:failure-reason` string.
3. `:failure.class/unknown` MUST be treated as an SLI incident (see §5.5) and SHOULD
   trigger investigation to reclassify into a specific class.
4. Implementations SHOULD track the `:failure.class/unknown` rate as a meta-reliability
   indicator — a high unknown rate signals insufficient failure instrumentation.

### 5.4 Concurrency Model

#### 5.4.1 OSS Concurrency

OSS implementations MUST support:

- **Single workflow execution** at a time (simple)
- **Sequential phase execution** within workflow
- **Parallel tool invocations** within agent (where safe)
- **Single developer/user model** - OSS is focused on the individual developer use case

OSS implementations MAY support:

- Multiple concurrent workflows (resource management required)

**Integration point for Team+ plans:** OSS implementations SHOULD support event streaming to
aggregation sinks (see N3 event stream API). The value proposition for multi-user scenarios is in
what you do with the aggregated event data (analytics, coordination, visibility), not merely having
the events.

#### 5.4.2 Enterprise Concurrency

Enterprise implementations MUST support:

- **Multi-user concurrent workflows**
- **Conflict detection** across workflows
- **Resource limits** (max concurrent workflows, LLM rate limits)

### 5.5 Reliability Model

Miniforge defines reliability in terms of measurable Service Level Indicators (SLIs),
tiered Service Level Objectives (SLOs), and error budgets. These apply to miniforge's
own execution reliability, not to the target systems it manages (for which see N7 OPSV).

#### 5.5.1 Workflow Tiers

Workflows MUST be classified into tiers that determine SLO targets, required verification
layers, and degradation behavior.

```clojure
{:workflow/tier keyword   ; OPTIONAL in workflow spec; defaults to :standard
 ;; Values:
 ;;   :best-effort  — No SLO enforcement; metrics collected for advisory use only
 ;;   :standard     — Default tier; SLOs tracked and reported
 ;;   :critical     — Stricter SLOs; error budget enforcement; safe-mode triggers
 }
```

Tier assignment MAY be explicit in the workflow spec (see N2 §9.1) or derived by policy
(e.g., workflows touching production environments default to `:critical`).

#### 5.5.2 Service Level Indicators (SLIs)

Implementations MUST compute the following SLIs. SLIs are computed over rolling time
windows and MAY be filtered by workflow tier, phase, agent, or tool.

| ID | SLI | Definition | Computation |
|----|-----|-----------|-------------|
| SLI-1 | Workflow Success Rate | Fraction of workflows completing successfully or with explicit escalation | `count(completed + escalated) / count(terminal)` |
| SLI-2 | Phase Completion Latency | Wall-clock duration per phase type | p50 / p95 / p99 of `:phase/duration-ms` |
| SLI-3 | Inner Loop Convergence Rate | Fraction of inner loops that converge within retry budget | `count(converged) / count(entered-inner-loop)` |
| SLI-4 | Gate Pass Rate | Fraction of gate evaluations that pass on first attempt | `count(first-pass) / count(evaluated)` per gate type |
| SLI-5 | Tool Invocation Success Rate | Fraction of tool invocations that return success | `count(success) / count(invoked)` per tool |
| SLI-6 | Failure Class Distribution | Percentage of failures per `:failure/class` | Per window, per tier |
| SLI-7 | Context Staleness Rate | Fraction of Context Packs that trigger staleness detection | `count(stale-detected) / count(context-packs-issued)` |

SLI values MUST be emitted as `:reliability/sli-computed` events (see N3 §3.17) and
recorded in workflow outcome evidence (see N6).

#### 5.5.3 Service Level Objectives (SLOs)

SLOs are tier-dependent targets for SLIs. Implementations MUST allow SLO targets to be
configured via policy packs (see N4). Default targets:

| SLI | :best-effort | :standard | :critical |
|-----|-------------|-----------|-----------|
| Workflow Success Rate | Advisory | >= 85% | >= 95% |
| Inner Loop Convergence | Advisory | >= 90% | >= 95% |
| Tool Success Rate | Advisory | >= 95% | >= 99% |
| Unknown Failure Rate | Advisory | < 10% | < 2% |

"Advisory" means the SLI is computed and reported but does not trigger degradation or
safe-mode. Implementations MUST emit `:reliability/slo-breach` events when a `:standard`
or `:critical` SLO target is missed (see N3 §3.17).

#### 5.5.4 Error Budgets

For `:standard` and `:critical` tiers, implementations SHOULD compute error budgets over
rolling windows. The error budget represents the remaining tolerance for failures before
the SLO is breached.

```clojure
{:error-budget/tier       keyword   ; REQUIRED: :standard | :critical
 :error-budget/sli        keyword   ; REQUIRED: SLI identifier (SLI-1 through SLI-7)
 :error-budget/window     keyword   ; REQUIRED: :1h | :7d | :30d
 :error-budget/remaining  double    ; REQUIRED: 0.0-1.0 fraction of budget remaining
 :error-budget/burn-rate  double    ; REQUIRED: current burn rate (1.0 = nominal)
 :error-budget/computed-at inst}    ; REQUIRED
```

Error budget state MUST be emitted as `:reliability/error-budget-update` events (see N3
§3.17).

#### 5.5.5 Degradation Modes

The system operates in one of three degradation modes. Mode transitions MUST be emitted
as `:reliability/degradation-mode-changed` events (see N3 §3.17).

| Mode | Trigger | Behavior |
|------|---------|----------|
| `:nominal` | Default | Full autonomous execution per configured autonomy levels |
| `:degraded` | Error budget remaining < 25% for any `:critical` SLI | Increase validation (add verification layers), reduce concurrency, emit warnings |
| `:safe-mode` | Error budget exhausted for `:critical` tier, or `emergency-stop` control action (N8) | Demote autonomy to A0, queue new workflows, pause in-flight — see N8 §3.4 |

Transitions between modes:

- `:nominal` → `:degraded`: automatic on error budget threshold
- `:degraded` → `:safe-mode`: automatic on budget exhaustion or manual emergency-stop
- `:safe-mode` → `:nominal`: requires explicit operator action with justification (N8 §3.4.3)
- `:degraded` → `:nominal`: automatic when error budget recovers above threshold

### 5.6 Unified Autonomy Model

Miniforge defines a unified autonomy level taxonomy that applies across all subsystems.
Individual specifications (N8, N9, N10) retain their domain-specific vocabulary but MUST
map to these unified levels for cross-cutting governance.

#### 5.6.1 Autonomy Levels

```clojure
{:autonomy/level keyword
 ;; Values (ordered from most restrictive to most permissive):
 ;;   :A0  — Observe        Read-only; no actions taken
 ;;   :A1  — Recommend      Suggest actions for human execution; no side effects
 ;;   :A2  — Advise         Non-blocking annotations and advisory signals
 ;;   :A3  — Act-with-guard Autonomous within guardrails; approval for escalation
 ;;   :A4  — Act-autonomous Full autonomy within policy bounds
 ;;   :A5  — Govern         Modify policies and controls; requires highest trust
 }
```

#### 5.6.2 Cross-Spec Mapping

Each spec retains its own terminology but MUST map to the unified level:

| Unified | N10 Trust Level | N9 Automation Tier | N8 Capability Level |
|---------|----------------|-------------------|---------------------|
| A0 | L0 (Observation) | Tier 0 (Observe) | OBSERVE |
| A1 | L1 (Recommendation) | — | OBSERVE |
| A2 | — | Tier 1 (Advise) | ADVISE |
| A3 | L2 (Bounded Execution) | Tier 2 (Converse) | ADVISE (scoped) |
| A4 | L3/L4 (Controlled/Domain Autonomy) | — | CONTROL |
| A5 | — | Tier 3 (Govern) | CONTROL |

#### 5.6.3 Autonomy Configuration

Autonomy levels MUST be configurable per:

- **Workflow tier** — `:critical` workflows MAY default to a lower autonomy ceiling
- **Environment** — production environments SHOULD default to lower autonomy than staging
- **Degradation mode** — safe-mode MUST demote all subsystems to A0 (§5.5.5)

Implementations MUST support per-workflow autonomy ceilings:

```clojure
{:autonomy/ceiling keyword          ; REQUIRED: maximum allowed autonomy level
 :autonomy/scope   keyword          ; REQUIRED: :workflow | :environment | :system
 :autonomy/reason  string}          ; OPTIONAL: why this ceiling was set
```

Cross-cutting autonomy demotion (e.g., during safe-mode) MUST override per-workflow
settings. The effective autonomy level is `min(system-ceiling, environment-ceiling,
workflow-ceiling, configured-level)`.

### 5.7 Trust Boundary Validation

All data crossing a trust boundary MUST be validated and normalized before consumption.
This is an architectural invariant — violations MUST be treated as system defects, not
application errors.

#### 5.7.1 Trust Boundaries

Miniforge defines five trust boundaries:

| ID | Boundary | From | To | Primary Risk |
|----|----------|------|----|-------------|
| TB-1 | LLM Output | Model response | Orchestrator/Agent context | Malformed structure, hallucinated identifiers |
| TB-2 | Tool Output | External tool result | Agent context | Schema drift, injection, stale data |
| TB-3 | Ingestion | Repository content | Knowledge base / pack store | Prompt injection, trust elevation |
| TB-4 | Pack | Pack content | Execution pipeline | Malicious instructions, dependency confusion |
| TB-5 | Provider | External platform (GitHub, GitLab) | Miniforge state | Data integrity, replay attacks |

#### 5.7.2 Validation Invariants

Implementations MUST enforce:

1. **TB-INV-1:** Model output used as structured data MUST be parsed and schema-validated
   before consumption. Free-form text MAY bypass schema validation but MUST be sanitized
   for injection patterns before use as instruction authority.
2. **TB-INV-2:** Tool results MUST be validated against the tool's declared output schema
   (see N10 §7.4) before injection into agent context. Results failing validation MUST
   NOT propagate to agents.
3. **TB-INV-3:** Ingested repository content MUST be classified by trust level (§2.10.2)
   before routing to instruction or data authority channels.
4. **TB-INV-4:** All timestamps crossing any trust boundary MUST be normalized to UTC
   `inst` values at ingestion. Implementations MUST reject timestamps that cannot be
   parsed to valid instants.
5. **TB-INV-5:** All entity identifiers crossing any trust boundary MUST be normalized to
   their canonical form (UUID for internal entities; provider-specific canonical form for
   external entities) at ingestion.

#### 5.7.3 Boundary Crossing Record

Implementations SHOULD record trust boundary crossings for audit:

```clojure
{:boundary/id        keyword   ; REQUIRED: :llm-output | :tool-output | :ingestion | :pack | :provider
 :boundary/status    keyword   ; REQUIRED: :accepted | :rejected | :sanitized
 :boundary/findings  [map]     ; OPTIONAL: validation issues found and handled
 :boundary/timestamp inst}     ; REQUIRED
```

Boundary crossing records MUST be linkable to N6 evidence bundles for the enclosing
workflow.

---

## 6. Agent Protocols & Communication

### 6.1 Agent Invocation Protocol

```clojure
;; Agent receives context from control plane
{:workflow/id uuid
 :workflow/spec {...}
 :workflow/intent {...}
 :phase/name :implement
 :phase/input-context
 {:plan/tasks [...]              ; From planner agent
  :design/files-to-change [...]  ; From designer agent (if present)
  :knowledge/patterns [...]      ; From knowledge base
  :policy/active-packs [...]}}   ; From policy engine

;; Agent returns output + artifacts
{:agent/output
 {:implementation/files-changed [...]
  :implementation/approach "..."}

 :agent/artifacts [artifact-uuid ...]

 :phase/next-context
 {:implement/completed true
  :implement/files-changed [...]}}
```

### 6.2 Inter-Agent Communication

#### 6.2.1 Message Types

Agents MAY communicate via these message types:

- `:clarification-request` - Agent needs clarification from prior agent
- `:clarification-response` - Response to clarification request
- `:concern` - Agent raises concern about prior work
- `:suggestion` - Agent suggests alternative approach

#### 6.2.2 Message Schema

```clojure
{:message/id uuid
 :message/type keyword
 :message/from-agent keyword
 :message/to-agent keyword
 :message/workflow-id uuid
 :message/content string
 :message/timestamp inst}
```

All inter-agent messages MUST emit events (see N3, section 3.7).

### 6.3 Context Handoff Protocol

#### 6.3.1 Phase Context Requirements

Each phase MUST receive:

1. **Workflow spec** - Original intent and constraints
2. **Prior phase outputs** - Artifacts and context from completed phases
3. **Knowledge context** - Relevant patterns from knowledge base
4. **Policy context** - Active policy packs for validation

#### 6.3.2 Context Schema

```clojure
{:workflow/id uuid
 :workflow/spec {...}
 :workflow/intent {...}

 :phase/name keyword
 :phase/input-context
 {:prior-phases {...}            ; Map of phase → output
  :knowledge/patterns [...]
  :policy/packs [...]
  :artifacts/available [uuid ...]}

 :phase/constraints [...]
 :phase/validation-requirements [...]}
```

---

## 7. Inner Loop & Outer Loop

### 7.1 Outer Loop (Phase Graph)

The **outer loop** is the phase transition state machine (see N2).

```text
Plan → Design → Implement → Verify → Review → Release → Observe
```

#### 7.1.1 Outer Loop Requirements

Implementations MUST:

1. Execute phases in dependency order
2. Pass context between phases
3. Enforce gates before phase completion
4. Support phase skipping (e.g., skip Design if simple change)
5. Emit events for phase transitions (see N3)

### 7.2 Inner Loop (Validate → Repair)

The **inner loop** is the validation and repair mechanism within a phase.

```text
Generate → Validate → [Pass? Yes → Complete]
                  ↓ No
              Repair → Validate → ...
```

#### 7.2.1 Inner Loop Requirements

Implementations MUST:

1. Validate agent output against constraints
2. Attempt repair if validation fails
3. Limit iterations (retry budget)
4. Escalate to human if budget exhausted
5. Record iterations in phase evidence

#### 7.2.2 Inner Loop Strategies

Implementations SHOULD try multiple repair strategies:

1. **Direct fix** - Agent repairs own output
2. **Peer consultation** - Ask other agent for help
3. **Knowledge base lookup** - Find similar past failures
4. **Human escalation** - Prompt user for guidance

---

## 8. Conformance & Testing

### 8.1 Component Conformance

Implementations MUST:

1. Implement all required protocols (Agent, Tool, Gate)
2. Emit all required events (see N3)
3. Generate evidence bundles for all workflows (see N6)
4. Support all standard phases and agents
5. Validate schemas for all domain entities

### 8.2 Integration Tests

Conformance tests MUST verify:

1. **End-to-end workflow execution** - Spec → PR with evidence
2. **Agent context handoff** - Context correctly passed between phases
3. **Inner loop validation** - Validation failures trigger repair
4. **Gate enforcement** - Policy violations block phase completion
5. **Event stream completeness** - All required events emitted

### 8.3 Interoperability Tests

Implementations MUST demonstrate:

1. **Polylith component isolation** - Components can be developed/tested independently
2. **Knowledge base portability** - Can export/import learnings
3. **Evidence bundle portability** - Can read evidence from other instances
4. **Policy pack compatibility** - Can use community policy packs

---

## 9. Rationale & Design Notes

### 9.1 Why Three Layers?

The three-layer architecture separates concerns:

- **Control Plane** - Orchestration logic (workflow engine, policy enforcement)
- **Agent Layer** - Execution logic (specialized agents, inner loop)
- **Learning Layer** - Improvement logic (observation, meta loop, heuristics)

This enables:

- Independent evolution of each layer
- Testing each layer in isolation
- Clear responsibility boundaries

### 9.2 Why Polylith?

Polylith provides:

- **Component reusability** - Share components between OSS and Enterprise
- **Clean boundaries** - Interface/implementation separation
- **Incremental builds** - Only rebuild changed components
- **Monorepo benefits** - All code in one place, atomic commits

### 9.3 Why Local-First?

Local-first ensures:

- **Developer autonomy** - No dependency on central services
- **Fast execution** - No network latency
- **Privacy** - Code stays local (except LLM API calls)
- **Offline capability** - Can work without internet (with cache)

### 9.4 Why Agents vs. Tool Use?

Agents are **teammates**, not tools:

- **Autonomy** - Agents make decisions, don't just execute commands
- **Collaboration** - Agents communicate and coordinate
- **Memory** - Agents remember past interactions
- **Learning** - Agents improve over time via meta loop

---

## 10. Future Extensions

### 10.1 Custom Phases (Post-OSS)

Future versions will support:

- User-defined custom phases
- Phase plugins
- Conditional phase execution (e.g., skip Review if low-risk)

### 10.2 Multi-Workflow Orchestration (Enterprise)

Enterprise features will add:

- **PR Trains** - Linked workflows across repos (now specified in N9 §13)
- **DAG Dependencies** - Workflow execution order based on dependencies (now specified in N2 §13)
- **Cross-Workflow Context** - Share learnings across related workflows

### 10.3 Federated Learning (Future Research)

Research directions:

- Federated knowledge base across organizations
- Privacy-preserving pattern sharing
- Differential privacy for heuristic evolution

---

## 11. Repository Intelligence and Context Assembly

This section consolidates the normative requirements for the Repo Index (§2.27), Context
Pack (§2.28), staleness protocol (§2.29), and tool contract (§2.30) into a single
conformance checklist.

### 11.1 Repository Index Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N1.RI.1 | MUST | Produce a RepoIndex for every `repo@commit` used in any workflow execution. |
| N1.RI.2 | MUST | Content-address all index data. Unchanged blobs MUST NOT be re-embedded, re-summarized, or re-indexed. |
| N1.RI.3 | MUST | All symbol and snippet ranges MUST include `(path, blob-sha, start-line, end-line)` and MUST be traceable to an immutable content hash. |
| N1.RI.4 | MUST | Expose a stable `:symbol/id` scheme (§2.27.3) and, where available, a cross-commit `:symbol/key`. |
| N1.RI.5 | MUST | Range normalization: UTF-8, 1-based inclusive lines, LF-normalized content, blob-sha over normalized content (§2.27.2). |
| N1.RI.6 | SHOULD | Ingest SCIP (preferred) or LSIF outputs for precise def/refs/impls navigation beyond Tree-sitter approximations. |
| N1.RI.7 | SHOULD | Build repo map slices under a token budget using dependency-graph-aware ranking. |
| N1.RI.8 | MUST | Compute index quality metrics (§2.27.9) after each incremental update. |
| N1.RI.9 | SHOULD | Run canary queries against updated indexes and emit degradation events on recall drop (§2.27.10). |
| N1.RI.10 | MUST | Include index quality metrics in workflow evidence bundles when repo context is consumed. |

### 11.2 Context Pack Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N1.CP.1 | MUST | Every agent invocation MUST receive a ContextPack as its primary repository context. |
| N1.CP.2 | MUST NOT | Agents MUST NOT perform bulk repository reads. All context acquisition MUST go through the tool contract (§2.30). |
| N1.CP.3 | MUST | The orchestrator MUST enforce per-task limits on `open()` calls, total snippet tokens, search calls, and distinct files opened. |
| N1.CP.4 | MUST | The orchestrator MUST deduplicate snippet payloads by `(blob-sha, range)` across all agents in a task. |
| N1.CP.5 | MUST | The Policy Envelope MUST be separable from content to allow independent budget tuning. |

### 11.3 Auditability Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N1.AU.1 | MUST | All generated code edits MUST cite exact source ranges (path + blob-sha + lines) or use explicit citation modes (`:novel-code`, `:synthesis`, `:policy-output`) when no direct source exists. |
| N1.AU.2 | MUST | The system MUST log all retrieval and open operations with `(repo-id, commit-sha, blob-sha, range)`. |
| N1.AU.3 | MUST | Audit traces MUST be linkable to N6 evidence bundles for the enclosing workflow. |
| N1.AU.4 | MUST | Budget accounting (search calls, open calls, snippet tokens) MUST be recorded per-task and queryable via `context.audit`. |

### 11.4 Staleness and Invalidation Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N1.SI.1 | MUST | Detect stale Context Packs when blob-sha references no longer match the current tree. |
| N1.SI.2 | MUST | On staleness: invalidate-and-rebuild (default), merge (when no overlap), or fail (when rebuild budget exhausted). |
| N1.SI.3 | SHOULD | Prefer invalidate-and-rebuild in v1; add merge support in later versions. |

### 11.5 Reliability Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N1.RL.1 | MUST | Classify all failures into the canonical taxonomy (§5.3.3). Classification MUST be performed by the runtime, not by agents. |
| N1.RL.2 | MUST | Compute SLIs (§5.5.2) over rolling time windows and emit `:reliability/sli-computed` events. |
| N1.RL.3 | MUST | Emit `:reliability/slo-breach` events when `:standard` or `:critical` SLO targets are missed. |
| N1.RL.4 | SHOULD | Compute error budgets (§5.5.4) for `:standard` and `:critical` tiers. |
| N1.RL.5 | MUST | Support degradation mode transitions (§5.5.5) and emit mode-change events. |
| N1.RL.6 | MUST | Enforce unified autonomy level ceilings (§5.6.3). Effective level is `min(system, environment, workflow, configured)`. |
| N1.RL.7 | MUST | Validate all data crossing trust boundaries per §5.7.2 invariants (TB-INV-1 through TB-INV-5). |
| N1.RL.8 | MUST | Normalize timestamps to UTC inst and identifiers to canonical form at trust boundaries (§5.7.2). |

### 11.6 Evaluation Pipeline Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N1.EV.1 | MUST | Support golden set execution — running curated workflow inputs and comparing outcomes (§3.3.3). |
| N1.EV.2 | SHOULD | Support replay mode for deterministic workflow reconstruction from evidence bundles. |
| N1.EV.3 | SHOULD | Populate golden sets from production incidents — every classified failure is a candidate entry. |
| N1.EV.4 | SHOULD | Gate deployments of prompt, model, policy, or tool schema changes on golden set pass rate regression. |

---

## 12. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- Polylith Architecture: https://polylith.gitbook.io/
- SCIP (Source Code Intelligence Protocol): https://sourcegraph.com/docs/code-intelligence/scip
- LSIF (Language Server Index Format):
  https://microsoft.github.io/language-server-protocol/specifications/lsif/0.4.0/specification/
- N2 (Workflow Execution): Defines phase graph and transitions
- N3 (Event Stream): Defines observability events
- N4 (Policy Packs): Defines gate validation rules
- N5 (CLI/TUI/API): Defines user-facing interfaces
- N6 (Evidence & Provenance): Defines evidence bundles and artifact provenance
- N7 (Operational Policy Synthesis): Defines OPSV workflow, Experiment Packs, Operational Policies
- N8 (Observability Control Interface): Defines Listeners, Capability Levels, Control Actions
- N9 (External PR Integration): Defines PR Work Items, Providers, PR Trains, Readiness

---

## 13. Glossary

- **Actuation Mode** - Governance mode for OPSV outputs: RECOMMEND_ONLY, PR_ONLY, or APPLY_ALLOWED (N7)
- **Advisory Annotation** - Non-blocking message attached to a workflow or event by a Listener (N8)
- **Agent** - Autonomous software entity that executes workflow phases
- **Artifact** - Work product created during workflow execution
- **Autonomy Level** - Unified governance tier (A0-A5) controlling permitted actions across all subsystems; maps to N8
  capability levels, N9 automation tiers, and N10 trust levels (§5.6)
- **Capability** - Connector-scoped permission unit required by a Workflow Pack; deny-by-default for writes
- **Capability Level** - Listener permission tier: OBSERVE, ADVISE, or CONTROL (N8)
- **Control Action** - Command that modifies workflow execution state, subject to RBAC and gates (N8)
- **Degradation Mode** - System operational posture: `:nominal`, `:degraded`, or `:safe-mode`, driven by error budget
  state (§5.5.5)
- **Error Budget** - Remaining tolerance for failures before an SLO is breached; computed per tier and SLI over rolling
  windows (§5.5.4)
- **Evidence Bundle** - Immutable audit trail from intent to outcome
- **Experiment Pack** - Declarative artifact defining workload models, guardrails, and convergence for OPSV (N7)
- **External PR** - Pull request whose diff was created outside Miniforge's authoring workflow (N9)
- **Failure Class** - Canonical taxonomy category for workflow/agent/tool failures; one of 10 enumerated values (§5.3.3)
- **Gate** - Validation checkpoint for artifacts
- **Golden Set** - Curated set of workflow inputs paired with known-good outcomes, used for regression testing in the
  evaluation pipeline (§3.3.3)
- **Inner Loop** - Validate → Repair cycle within a phase
- **Listener** - External actor that subscribes to workflow events with OBSERVE/ADVISE/CONTROL capability (N8)
- **Operational Policy** - Versioned runtime configuration artifacts controlling service behavior under load (N7)
- **Context Pack** - Bounded, auditable context document assembled by orchestrator for agent consumption (§2.28)
- **Context Staleness** - Condition when blob-sha references in a Context Pack no longer match the current tree (§2.29)
- **Outer Loop** - Phase transition state machine
- **Phase** - Logical SDLC stage (Plan, Implement, Verify, etc.)
- **Pack Run** - Execution instance of a Workflow Pack entrypoint, producing evidence and audit linkage
- **Policy Envelope** - Separable budget/policy configuration within a Context Pack (§2.28.7)
- **Policy Pack** - Collection of validation rules; specialization of pack model (§2.10.3) (N4)
- **PR Train** - Ordered set of PR Work Items with dependency relationships for sequential merge (N9)
- **PR Work Item** - Canonical internal model of a PR in the Fleet control plane (N9)
- **Provider** - External code hosting platform (GitHub, GitLab) as source of PR events (N9)
- **Range** - Content-addressed code reference: path + blob-sha + 1-based inclusive line numbers (§2.27.2)
- **Readiness** - Deterministic merge-readiness assessment from provider signals and policy results (N9)
- **Repo Index** - Content-addressed, incrementally buildable index of a repository at a specific commit (§2.27)
- **Replay Mode** - Re-execution of a completed workflow from its evidence bundle to verify deterministic reconstruction
  (§3.3.3)
- **Repo Map** - Token-budgeted summary of repository structure for agent global prior (§2.27.6)
- **Risk Assessment** - Explainable evaluation of change risk for a PR, produced as N6 evidence artifact (N9)
- **Service Level Indicator (SLI)** - Measurable metric describing miniforge's own execution reliability (§5.5.2)
- **Service Level Objective (SLO)** - Tier-dependent target for an SLI; breach triggers degradation or safe-mode
  (§5.5.3)
- **Shadow Mode** - Running a candidate system configuration in parallel with production to compare outcomes without
  side effects (§3.3.3)
- **Subagent** - Specialized agent spawned by parent agent
- **Symbol Key** - Cross-commit logical identity for a symbol; best-effort continuity across renames/refactors (§2.27.3)
- **Tool** - External capability invoked by agent
- **Trust Boundary** - Interface where data crosses from an untrusted source into miniforge's execution context;
  requires validation and normalization (§5.7)
- **Verification** - Executing an Experiment Pack against a candidate Operational Policy to produce pass/fail evidence
  (N7)
- **Workflow** - Top-level unit of autonomous execution
- **Workflow Pack** - Versioned bundle containing workflows, schemas, templates, and metadata
- **Workflow Tier** - Impact classification (`:best-effort`, `:standard`, `:critical`) that determines SLO targets and
  required verification layers (§5.5.1)

---

**Version History:**

- 0.5.0-draft (2026-03-08): Reliability Nines amendments — Failure Taxonomy (§5.3.3),
  Reliability Model with SLIs/SLOs/Error Budgets (§5.5), Unified Autonomy Model (§5.6),
  Trust Boundary Validation (§5.7), Index Quality Metrics and Canary Protocol (§2.27.9–2.27.10),
  Evaluation Pipeline (§3.3.3), Reliability and Evaluation conformance (§11.5–§11.6)
- 0.4.0-draft (2026-03-04): Added Repository Intelligence and Context Assembly
  (§2.27–§2.30 domain entities, §11 conformance requirements, §13 glossary additions)
- 0.3.0-draft (2026-02-16): Added Workflow Pack, Capability, Pack Run concepts
  (§2.10.3 extended, §2.24–§2.26, §12 glossary)
- 0.2.0-draft (2026-02-07): Added extension spec concepts from N7, N8, N9
  (§2.11–§2.23, §12 glossary)
- 0.1.0-draft (2026-01-23): Initial core architecture specification
