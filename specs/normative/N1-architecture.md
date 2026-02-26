# N1 — Core Architecture & Concepts

**Version:** 0.3.0-draft
**Date:** 2026-02-16
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **core architectural concepts** and **structural model** of
miniforge.ai, the autonomous software factory. It establishes:

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

miniforge treats structured "packs" as first-class knowledge units and artifacts. Packs are EDN-serialized and schema-validated.

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

2. **Key storage:** Private keys MUST be stored securely (e.g., system keychain, HSM, or encrypted file with passphrase).

3. **Key verification:** Public keys for signature verification MUST be distributed through a
   trusted channel (e.g., configuration file, registry manifest).

4. **Key rotation:** Implementations SHOULD support key rotation with backward compatibility for previously signed packs.

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

Enterprise extensions MUST NOT weaken the OSS trust model. Instruction authority MUST remain gated by pack trust and policy.

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

## 11. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- Polylith Architecture: https://polylith.gitbook.io/
- N2 (Workflow Execution): Defines phase graph and transitions
- N3 (Event Stream): Defines observability events
- N4 (Policy Packs): Defines gate validation rules
- N5 (CLI/TUI/API): Defines user-facing interfaces
- N6 (Evidence & Provenance): Defines evidence bundles and artifact provenance
- N7 (Operational Policy Synthesis): Defines OPSV workflow, Experiment Packs, Operational Policies
- N8 (Observability Control Interface): Defines Listeners, Capability Levels, Control Actions
- N9 (External PR Integration): Defines PR Work Items, Providers, PR Trains, Readiness

---

## 12. Glossary

- **Actuation Mode** - Governance mode for OPSV outputs: RECOMMEND_ONLY, PR_ONLY, or APPLY_ALLOWED (N7)
- **Advisory Annotation** - Non-blocking message attached to a workflow or event by a Listener (N8)
- **Agent** - Autonomous software entity that executes workflow phases
- **Artifact** - Work product created during workflow execution
- **Capability** - Connector-scoped permission unit required by a Workflow Pack; deny-by-default for writes
- **Capability Level** - Listener permission tier: OBSERVE, ADVISE, or CONTROL (N8)
- **Control Action** - Command that modifies workflow execution state, subject to RBAC and gates (N8)
- **Evidence Bundle** - Immutable audit trail from intent to outcome
- **Experiment Pack** - Declarative artifact defining workload models, guardrails, and convergence for OPSV (N7)
- **External PR** - Pull request whose diff was created outside Miniforge's authoring workflow (N9)
- **Gate** - Validation checkpoint for artifacts
- **Inner Loop** - Validate → Repair cycle within a phase
- **Listener** - External actor that subscribes to workflow events with OBSERVE/ADVISE/CONTROL capability (N8)
- **Operational Policy** - Versioned runtime configuration artifacts controlling service behavior under load (N7)
- **Outer Loop** - Phase transition state machine
- **Phase** - Logical SDLC stage (Plan, Implement, Verify, etc.)
- **Pack Run** - Execution instance of a Workflow Pack entrypoint, producing evidence and audit linkage
- **Policy Pack** - Collection of validation rules; specialization of pack model (§2.10.3) (N4)
- **PR Train** - Ordered set of PR Work Items with dependency relationships for sequential merge (N9)
- **PR Work Item** - Canonical internal model of a PR in the Fleet control plane (N9)
- **Provider** - External code hosting platform (GitHub, GitLab) as source of PR events (N9)
- **Readiness** - Deterministic merge-readiness assessment from provider signals and policy results (N9)
- **Risk Assessment** - Explainable evaluation of change risk for a PR, produced as N6 evidence artifact (N9)
- **Subagent** - Specialized agent spawned by parent agent
- **Tool** - External capability invoked by agent
- **Verification** - Executing an Experiment Pack against a candidate Operational Policy to produce pass/fail evidence (N7)
- **Workflow** - Top-level unit of autonomous execution
- **Workflow Pack** - Versioned bundle containing workflows, schemas, templates, and metadata

---

**Version History:**

- 0.3.0-draft (2026-02-16): Added Workflow Pack, Capability, Pack Run concepts
  (§2.10.3 extended, §2.24–§2.26, §12 glossary)
- 0.2.0-draft (2026-02-07): Added extension spec concepts from N7, N8, N9
  (§2.11–§2.23, §12 glossary)
- 0.1.0-draft (2026-01-23): Initial core architecture specification
