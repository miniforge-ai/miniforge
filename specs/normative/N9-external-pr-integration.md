# N9 — External PR Integration

**Version:** 0.1.0-draft
**Date:** 2026-02-07
**Status:** Draft
**Conformance:** MUST

---

## 0. Status and Scope

### 0.1 Purpose

This specification defines how Miniforge MUST ingest, model, evaluate, and
(optionally) act on pull requests that were **not created by Miniforge**
("external PRs"), providing the same monitoring, governance, and workflow
benefits available to Miniforge-originated PRs.

Miniforge already creates PRs as outputs of its workflow engine (Release phase,
DAG task executor). This spec extends the system to treat **any** PR — from any
author, in any connected repo — as a first-class work item inside the Fleet
control plane.

### 0.2 Relationship to N1–N8

N9 is an extension of existing Miniforge normative contracts:

- **N1**: introduces new concepts (External PR, PR Work Item, Provider, PR Train,
          Readiness, Risk Assessment) as specializations of Workflow/Artifact/Evidence.
          Now landed in N1 §2.19–§2.23 and §12 (glossary).
- **N2**: does NOT define new workflow phases; external PRs are not executed through
          the Plan→Implement→Verify pipeline. However, external PRs MAY be
          "adopted" into a Miniforge workflow (e.g., PR Monitoring), at which point
          N2 applies.
- **N3**: adds required event types for provider ingestion and PR state changes
          as extensions to the event stream contract.
          Now landed in N3 §3.15 (External PR Integration Events) and §2.3 (scope key).
- **N4**: defines how existing policy packs evaluate external PR diffs and how
          policy results are published as provider-native signals.
          Now landed in N4 §5.1.7 (External PR Evaluation).
- **N5**: defines CLI/TUI/API extensions for multi-repo PR monitoring, readiness
          views, and train management.
          Now landed in N5 §2.3.3 (External PR Commands), §3.2.8–§3.2.10 (TUI views), §4.2.4 (API).
- **N6**: defines how external PR assessments produce evidence artifacts using
          the existing evidence bundle schema.
          Now landed in N6 §2.10 (External PR Evidence) and §3.1.1 (artifact types).
- **N7**: N7 defines OPSV (operational policy synthesis for service fleets at
          runtime). N9 defines SDLC governance across repository fleets at
          development time. Both operate under the Fleet control plane but address
          different lifecycle stages. See §0.4 for disambiguation.
- **N8**: N9's automation tiers are a specialization of N8's capability levels
          (OBSERVE/ADVISE/CONTROL) scoped to provider interactions. See §10.

### 0.3 Non-goals

N9 SHALL NOT:

- require running full Miniforge "spec→plan→implement" workflows for external PRs.
- rebase or merge external PRs by default (requires explicit Tier 3 configuration).
- define any particular UI implementation; only data contracts and event types.
- replace or duplicate contracts already defined in N3, N4, N6, or N8.

### 0.4 Fleet Mode Disambiguation

The **Fleet control plane** is a shared coordination layer that hosts multiple
fleet capabilities:

| Capability | Spec | Lifecycle Stage | Target | Summary |
|---|---|---|---|---|
| Workflow orchestration | N2 | Development | Individual workflows | Phase graph execution |
| Operational policy synthesis | N7 | Runtime | Service fleets | Scaling/sizing experiments |
| Observability control | N8 | Cross-cutting | Agent fleets | Observe/advise/control |
| **External PR integration** | **N9** | **Development** | **Repository fleets** | **PR monitoring & governance** |

N7 answers: "How should these services scale at runtime?"
N9 answers: "What is the state of all open PRs across our repos, and are they safe to merge?"

Both share:

- The Fleet API surface (N5 `fleet` namespace)
- The event stream infrastructure (N3)
- The evidence and provenance model (N6)
- The policy evaluation engine (N4)

They do NOT share domain models. N7's domain is experiments, scaling signals, and
operational policies. N9's domain is PRs, reviews, CI checks, and merge readiness.

---

## 1. Terminology (N1 Extension)

The following concepts are defined in N1 §2.19–§2.23 and §12 (Glossary).
Detailed semantics are specified below.

### 1.1 External PR

An **External PR** is a pull request whose diff was created outside Miniforge's
own authoring workflow. External PRs have no `:workflow/id` association unless
explicitly adopted.

### 1.2 PR Work Item

A **PR Work Item** is the canonical internal model of a PR used by the Fleet
control plane. All PRs — Miniforge-originated and external — MUST be represented
as PR Work Items. This is distinct from N1's Workflow; a PR Work Item tracks
provider state, not workflow execution state.

### 1.3 Provider

A **Provider** is an external code hosting platform (GitHub, GitLab, etc.) that
is a source of PR events and state. Providers are analogous to N7's Environment
Targets — they are external systems that Miniforge connects to but does not own.

### 1.4 PR Train

A **PR Train** is an ordered set of PR Work Items with explicit dependency
relationships that MUST be merged in sequence. Trains are analogous to N2's DAG
task dependencies, applied to PR merge ordering.

### 1.5 Readiness

**Readiness** is a deterministic assessment of whether a PR is ready to merge,
computed from provider signals (CI, reviews, merge conflicts) and policy
evaluation results.

### 1.6 Risk Assessment

A **Risk Assessment** is an explainable evaluation of change risk for a PR,
produced as an evidence artifact (N6) with traceable factors.

---

## 2. PR Work Item Model

### 2.1 Schema

Fleet Mode MUST represent each PR as a PR Work Item with the following schema.
All fields use Clojure namespaced keywords per N1 convention.

```clojure
{;; Identity
 :pr/id uuid                          ; REQUIRED: stable internal id
 :pr/provider keyword                 ; REQUIRED: :github, :gitlab, etc.
 :pr/repo string                      ; REQUIRED: "org/name"
 :pr/number long                      ; REQUIRED: provider PR number
 :pr/external? boolean                ; REQUIRED: true if not Miniforge-originated

 ;; Head/Base
 :pr/head-sha string                  ; REQUIRED: current head commit SHA
 :pr/base-ref string                  ; REQUIRED: target branch
 :pr/head-ref string                  ; OPTIONAL: source branch

 ;; Metadata
 :pr/author string                    ; REQUIRED: PR author
 :pr/title string                     ; REQUIRED: PR title
 :pr/state keyword                    ; REQUIRED: :open, :closed, :merged
 :pr/mergeable keyword                ; REQUIRED: :true, :false, :unknown
 :pr/labels [string ...]              ; OPTIONAL: provider labels

 ;; Timestamps
 :pr/created-at inst                  ; REQUIRED
 :pr/updated-at inst                  ; REQUIRED
 :pr/last-seen-at inst                ; REQUIRED: last provider sync

 ;; Provider Signals
 :pr/checks                           ; REQUIRED: CI/check run summary
 {:checks/overall keyword             ; :passing, :failing, :pending, :unknown
  :checks/items [{:name string :status keyword :url string}]}

 :pr/reviews                          ; REQUIRED: review state summary
 {:reviews/overall keyword            ; :approved, :changes-requested, :pending, :unknown
  :reviews/items [{:author string :state keyword :submitted-at inst}]}

 :pr/threads                          ; OPTIONAL: unresolved discussion threads
 {:threads/unresolved long
  :threads/total long}

 ;; Derived State (computed by N9)
 :pr/readiness                        ; REQUIRED: see §2.2
 {:readiness/state keyword
  :readiness/blockers [...]}

 :pr/risk                             ; REQUIRED: see §5
 {:risk/level keyword
  :risk/factors [...]
  :risk/requires-human? boolean}

 :pr/policy                           ; REQUIRED: see §8
 {:policy/overall keyword             ; :pass, :fail, :unknown
  :policy/results [...]}

 ;; Automation
 :pr/automation-tier keyword          ; REQUIRED: see §10

 ;; Workflow Linkage (Miniforge PRs only)
 :pr/workflow-id uuid                 ; OPTIONAL: absent for external PRs
 :pr/dag-id uuid                      ; OPTIONAL: DAG run that produced this PR
 :pr/task-id uuid}                    ; OPTIONAL: task that produced this PR
```

### 2.2 Readiness

`:pr/readiness` MUST be computed deterministically from available signals.

#### 2.2.1 Readiness State

`:readiness/state` MUST be one of:

| State | Condition |
|---|---|
| `:merge-ready` | All checks pass, approved, no conflicts, policy pass |
| `:needs-review` | No reviews or insufficient approvals |
| `:changes-requested` | At least one reviewer requested changes |
| `:ci-failing` | One or more required checks failing |
| `:policy-failing` | One or more policy pack rules failing |
| `:merge-conflicts` | Provider reports merge conflict |
| `:unknown` | Provider data incomplete (e.g., first sync) |

#### 2.2.2 Blockers

`:readiness/blockers` MUST be a vector of:

```clojure
{:blocker/type keyword                ; REQUIRED: :ci, :review, :policy, :conflict, :thread
 :blocker/message string              ; REQUIRED: human-readable description
 :blocker/source string               ; REQUIRED: what produced this blocker
 :blocker/evidence-id uuid}           ; OPTIONAL: link to N6 evidence artifact
```

### 2.3 Compatibility with Miniforge-Originated PRs

All Miniforge-originated PRs (from Release phase or DAG task executor) MUST also
be represented as PR Work Items. For these PRs:

- `:pr/external?` MUST be `false`
- `:pr/workflow-id`, `:pr/dag-id`, `:pr/task-id` MUST be populated
- The existing N3 PR lifecycle events (§3.10: `pr/opened`, `pr/merged`, etc.)
  continue to be emitted for workflow correlation
- N9 readiness/risk/policy are additive enrichments; they do NOT change
  workflow phase semantics (N2)

---

## 3. Provider Ingestion

### 3.1 Provider Model

N9 implementations MUST support at least one provider. For GitHub-hosted repos,
the RECOMMENDED implementation is a **GitHub App** for scalable multi-repo
webhook delivery and token management.

If not using a GitHub App, the system MUST provide equivalent:

- webhook ingestion (or polling)
- per-repo token management
- rate limit handling

### 3.2 Event Normalization

Provider-native events (e.g., GitHub webhook payloads) MUST be normalized to
canonical N3 event types before processing. The normalizer:

- MUST map provider events to canonical types (§7)
- MUST extract correlation fields (repo, PR number, head SHA)
- MUST be idempotent: replayed provider events MUST NOT create duplicate state
- MUST tolerate out-of-order delivery
- MAY retain raw provider payload for debugging, subject to data minimization (§11.3)

### 3.3 Reconciliation

The system MUST reconcile PR Work Item state against provider state when
inconsistencies are detected. Reconciliation polling SHOULD exist as a backstop
for missed webhooks and MUST be bounded to avoid API abuse (see §6).

---

## 4. Multi-Repo Configuration

### 4.1 Configuration Sources

A repo MAY opt into external PR integration via:

1. **Repo-local config:** `.miniforge/config.edn` (or equivalent) in the repository
2. **Org-level defaults:** in Fleet control plane configuration

Repo-local config MUST override org-level defaults.

### 4.2 Required Configuration Keys

```clojure
{:external-pr/enabled? boolean        ; REQUIRED: opt-in to external PR monitoring

 :external-pr/automation-tier keyword ; REQUIRED: see §10
                                      ; default: :tier-1

 :policies/enabled [string ...]       ; OPTIONAL: policy pack ids to evaluate
                                      ; uses N4 policy pack identifiers

 :policies/mode keyword               ; OPTIONAL: :advisory or :enforcing
                                      ; default: :advisory

 :notifications {...}                 ; OPTIONAL: routing and thresholds

 :trains/enabled? boolean}            ; OPTIONAL: enable PR train support
                                      ; default: false
```

### 4.3 Safe Defaults

If external PR integration is enabled but `:policies/enabled` is not specified:

- The system SHOULD apply a minimal default policy pack set in **advisory** mode.
- The system MUST NOT publish enforcing checks without explicit configuration.

---

## 5. Risk Assessment (N6 Extension)

Risk assessments MUST be produced as N6 evidence artifacts. They are NOT a
separate evidence model — they use the existing `:evidence-bundle/*` schema
with risk-specific content.

### 5.1 Risk Artifact Schema

```clojure
{:artifact/id uuid
 :artifact/type :risk-assessment       ; New artifact type for N6 §3.1.1
 :artifact/content-hash string
 :artifact/created-at inst

 :artifact/content
 {:risk/level keyword                  ; REQUIRED: :low, :medium, :high, :critical
  :risk/factors                        ; REQUIRED: explainable factors
  [{:factor/code keyword               ; e.g., :large-diff, :sensitive-paths, :no-tests
    :factor/description string
    :factor/evidence-refs [uuid ...]}] ; Links to other N6 artifacts

  :risk/blast-radius string            ; REQUIRED: qualitative description
  :risk/requires-human? boolean}       ; REQUIRED

 :artifact/provenance
 {:provenance/workflow-id uuid         ; OPTIONAL: nil for external PRs
  :provenance/phase :external-pr-eval  ; Distinguishes from workflow phases
  :provenance/agent :pr-evaluator
  :provenance/created-at inst}}
```

Risk scoring MUST be explainable via `:factor/evidence-refs` and MUST NOT be a
black-box number without factors.

---

## 6. Rate Limits, Backpressure, and Resilience

- Provider API calls MUST be rate-limited and retried with exponential backoff.
- Ingestion MUST queue work and MUST degrade gracefully under load.
- Reconciliation polling SHOULD be bounded (e.g., one full sync per repo per
  15 minutes maximum) to avoid API abuse.
- If policy evaluation fails, the system MUST set `:policy/overall` to `:unknown`
  and record a diagnostic evidence artifact (N6) explaining the failure.

---

## 7. Event Types (N3 Extension)

N9 adds these event types to the N3 event stream. All events MUST use the N3
event envelope (N3 §2) with standard required fields.

### 7.1 Scope Key

Because external PR events are not associated with a Miniforge workflow, the
`:workflow/id` field in the N3 envelope MUST be handled as follows:

- For Miniforge-originated PRs: `:workflow/id` MUST reference the originating workflow
- For external PRs: `:workflow/id` MAY be nil. Events MUST instead include
  `:pr/id` (the PR Work Item id) as a correlation key.

Implementations MUST support subscribing to events by `:pr/id` in addition to
`:workflow/id`.

### 7.2 Provider Ingestion Events

#### provider/event-received

Emitted when a provider event is received and normalized.

```clojure
{:event/type :provider/event-received
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pr/id uuid                          ; PR Work Item id (if PR-scoped)
 :provider/type keyword               ; :github, :gitlab
 :provider/event-type keyword         ; Canonical type that was mapped
 :provider/repo string                ; "org/name"
 :provider/pr-number long             ; OPTIONAL
 :provider/head-sha string            ; OPTIONAL
 :provider/dedupe-key string          ; For idempotency

 :message "Provider event received: {type} for {repo}#{pr-number}"}
```

### 7.3 PR State Events

These events are emitted when PR Work Item derived state changes. They are
**derived-state-change events** — they fire when computation produces a new
value, not on every provider event.

#### pr.readiness/changed

```clojure
{:event/type :pr.readiness/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long

 :readiness/previous-state keyword
 :readiness/new-state keyword
 :readiness/blockers [...]            ; Current blockers

 :message "PR {repo}#{number} readiness: {previous} → {new}"}
```

#### pr.risk/changed

```clojure
{:event/type :pr.risk/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long

 :risk/previous-level keyword
 :risk/new-level keyword
 :risk/factors [...]
 :risk/evidence-id uuid               ; N6 artifact id

 :message "PR {repo}#{number} risk: {previous} → {new}"}
```

#### pr.policy/changed

```clojure
{:event/type :pr.policy/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long

 :policy/previous-overall keyword
 :policy/new-overall keyword
 :policy/results [...]
 :policy/evidence-id uuid             ; N6 artifact id

 :message "PR {repo}#{number} policy: {previous} → {new}"}
```

#### pr.state/changed

```clojure
{:event/type :pr.state/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long

 :pr/previous-state keyword           ; :open, :closed, :merged
 :pr/new-state keyword
 :pr/head-sha string

 :message "PR {repo}#{number} state: {previous} → {new}"}
```

### 7.4 Train Events

#### train/changed

```clojure
{:event/type :train/changed
 :train/id uuid
 :train/members [uuid ...]            ; Ordered PR Work Item ids
 :train/change-type keyword           ; :member-added, :member-removed,
                                      ; :order-changed, :member-merged

 :message "Train {id}: {change-type}"}
```

### 7.5 Event Ordering

- Provider ingestion events MUST be idempotent per `:provider/dedupe-key`.
- Derived-state-change events MUST only fire when computed state actually changes.
- All events MUST conform to N3 §2.2 ordering guarantees where a workflow scope
  exists. For external PRs (no workflow), events MUST be ordered per PR Work Item.

---

## 8. Policy Evaluation (N4 Extension)

### 8.1 Evaluation Context

External PRs are evaluated using existing N4 policy packs. The evaluation context
differs from workflow gate evaluation:

- **Artifacts:** The PR diff, file list, and metadata are presented as artifacts
  to policy pack check functions (N4 §3.1).
- **Context:** Instead of a workflow spec with declared intent, the context
  contains PR metadata (author, repo, labels, base branch).
- **No repair:** Policy evaluation for external PRs MUST NOT invoke repair
  functions (N4 §3.2). External PRs are read-only unless adopted.

### 8.2 Evaluation Triggers

Policy evaluation MUST run on at least these provider events:

- PR opened (new PR detected)
- PR synchronized (new commits pushed)
- Check run completed (CI results changed)
- Configuration changed (repo `.miniforge/config.edn` updated)

### 8.3 Policy Result

`:pr/policy` uses the existing N4 violation schema (N4 §3.3) and MUST include:

```clojure
{:policy/overall keyword              ; :pass, :fail, :unknown
 :policy/results
 [{:rule/id string                    ; From N4 policy pack rule
   :rule/outcome keyword              ; :pass, :fail, :warn, :skip, :unknown
   :rule/message string               ; Human-readable, concise
   :rule/evidence-id uuid}]           ; N6 artifact with full details

 :policy/evaluated-at inst
 :policy/packs-applied                ; Which packs ran
 [{:policy-pack/id string
   :policy-pack/version string}]}
```

### 8.4 Provider Feedback

If `:policies/mode` is `:enforcing`, the system SHOULD publish outcomes as
provider-native signals (e.g., GitHub Check Runs).

If publishing provider-native checks, the system MUST:

- use a stable check name per policy pack (e.g., `miniforge/terraform-aws`)
- include a summary linking to evidence
- respect automation tier constraints (§10)

---

## 9. Evidence (N6 Extension)

External PR evaluations produce evidence using the existing N6 schema. N9 does
NOT define a separate evidence model.

### 9.1 New Artifact Types

N6 §3.1.1 MUST be extended with:

- `:risk-assessment` — Risk evaluation for a PR (see §5.1)
- `:pr-policy-result` — Policy evaluation result for an external PR
- `:pr-readiness-snapshot` — Point-in-time readiness assessment

All artifacts MUST have `:artifact/content-hash`, `:artifact/provenance`, and
`:artifact/created-at` per N6 §3.

### 9.2 Evidence Immutability

Evidence artifacts produced by N9 MUST be immutable and addressable per N6 §5.1.
Policy results and risk factors MUST reference evidence artifacts, not inline
their content.

---

## 10. Automation Tiers (N8 Specialization)

N9's automation tiers are a **specialization** of N8's capability levels, scoped
to provider interactions (writing comments, checks, reviews on external PRs).

### 10.1 Mapping to N8

| N9 Tier | N8 Level | Provider Permissions |
|---|---|---|
| **Tier 0 — Observe** | `OBSERVE` | No provider writes. Compute readiness/risk/policy internally only. |
| **Tier 1 — Advise** | `ADVISE` | MAY publish non-blocking advisory checks. MAY comment when explicitly requested (e.g., `/miniforge summarize`). |
| **Tier 2 — Converse** | `ADVISE` + scoped interaction | MAY respond to questions when bot is mentioned or command is used. MUST NOT approve, request changes, or merge. |
| **Tier 3 — Govern** | `CONTROL` | MAY publish enforcing checks. MAY request changes. MAY manage trains and merge orchestration if configured. MUST log all actions per N8 §3.2 and §11. |

### 10.2 Tier Constraints

- Default tier SHOULD be Tier 1 for external PRs.
- Tier MUST be configured per repo via §4.2.
- Tier 3 MUST implement a per-repo allowlist of permitted actions
  (comment/review/check/merge) per N8 §2.3 RBAC requirements.
- All provider write actions at Tier 1+ MUST be audit-logged (§11).

### 10.3 Tier Escalation

Moving from a lower tier to a higher tier MUST require explicit configuration
change. The system MUST NOT auto-escalate tiers based on heuristics.

---

## 11. Audit and Security

### 11.1 Audit Log (N8 Extension)

All provider write actions MUST be logged using the N8 control action evidence
schema (N8 §11.1), extended with:

```clojure
{:action/id uuid
 :action/type keyword                 ; :comment, :check-publish, :review,
                                      ; :merge, :label, :re-request-review
 :action/timestamp inst
 :action/actor string                 ; System identity
 :action/target
 {:pr/repo string
  :pr/number long
  :pr/id uuid}                        ; PR Work Item id

 :action/inputs {...}                 ; Redacted as needed
 :action/result {:status keyword}
 :action/reason                       ; REQUIRED: why this action was taken
 {:policy/evidence-id uuid            ; Link to policy result evidence
  :readiness/evidence-id uuid         ; Link to readiness evidence
  :human-request? boolean}}           ; Was this triggered by a human command?
```

### 11.2 Secrets and Tokens

- Provider tokens/credentials MUST be stored encrypted at rest.
- The system MUST support key rotation without downtime.
- Token scopes MUST be minimized to required permissions per tier.

### 11.3 Data Minimization

- Raw provider payload storage SHOULD be optional and time-bounded.
- Evidence artifacts MUST NOT include secrets; secret redaction MUST be enforced
  prior to persistence (per N6 §7.2).

---

## 12. CLI/TUI/API Extensions (N5 Extension)

### 12.1 CLI Commands

N5 §2.2 `fleet` namespace MUST be extended with:

```bash
# PR monitoring
mf fleet prs [flags]                  # List PR Work Items across repos
  --repo REPO                         # Filter by repo
  --author AUTHOR                     # Filter by author
  --readiness STATE                   # Filter by readiness state
  --risk LEVEL                        # Filter by risk level
  --policy OUTCOME                    # Filter by policy outcome
  --json                              # Output as JSON

mf fleet pr REPO#NUMBER [flags]       # Show PR Work Item detail
  --evidence                          # Include evidence artifact pointers
  --json                              # Output as JSON

# Train management (if trains enabled)
mf fleet trains [flags]               # List active trains
mf fleet train TRAIN_ID [flags]       # Show train detail and membership
```

### 12.2 TUI Extensions

The TUI (N5 §3) SHOULD provide:

- **PR Fleet View:** List of PR Work Items across repos with readiness/risk
  columns, sortable and filterable
- **PR Detail View:** Readiness blockers, risk factors, policy results with
  drill-down to evidence artifacts
- **Train View:** Ordered train members with merge readiness status

These views derive from the event stream (N3) and PR Work Item state — they
are projections, not separate data models (same principle as N5 §3.2.5 DAG
Kanban View).

### 12.3 Fleet API Extensions

N5 §4.2 Fleet API MUST be extended with:

```text
GET /api/fleet/prs
  Query params: ?repo=org/name&readiness=merge-ready&risk=high
  Returns: List of PR Work Items

GET /api/fleet/prs/:pr-id
  Returns: PR Work Item with evidence pointers

GET /api/fleet/trains
  Returns: List of active trains

GET /api/fleet/trains/:train-id
  Returns: Train detail with ordered members
```

### 12.4 Event Stream Subscriptions

The Fleet event stream (N3 §5, N5 §4.2.2) MUST support subscription filters for
N9 event types, enabling clients to subscribe to:

- All PR state changes across repos
- Readiness changes for specific repos
- Policy changes for specific policy packs

---

## 13. PR Trains

If `:trains/enabled?` is `true` in repo configuration:

### 13.1 Dependency Declaration

The system MUST support explicit dependency declaration via:

- **Labels:** e.g., `train:<train-id>` on provider PRs
- **PR body directives:** e.g., `depends-on: org/repo#123`

The system MAY infer soft ordering suggestions, but MUST NOT enforce inferred
dependencies without explicit declaration.

### 13.2 Train Schema

```clojure
{:train/id uuid                       ; REQUIRED: stable train identifier
 :train/members                       ; REQUIRED: ordered PR Work Item refs
 [{:pr/id uuid
   :pr/repo string
   :pr/number long
   :train/position long}]             ; 1-indexed merge order

 :train/policy                        ; REQUIRED
 {:train/merge-strategy keyword       ; :sequential, :batch
  :train/required-readiness keyword   ; Minimum readiness for merge
  :train/auto-merge? boolean}}        ; Requires Tier 3
```

### 13.3 Train Operations

- Train merge orchestration MUST respect automation tier constraints (Tier 3
  required for automated merge sequencing).
- Train membership changes MUST emit `:train/changed` events (§7.4).
- Train operations MUST be audit-logged (§11).

---

## 14. Versioning

- PR Work Item schema, N9 event types, and API extensions MUST be versioned.
- Breaking changes MUST increment a major version and MUST be supported in
  parallel for at least one deprecation cycle.
- Version is tracked in the N3 event envelope `:event/version` field.

---

## 15. Minimal Compliant Implementation (MCI)

A minimal compliant N9 implementation MUST:

1. Support at least one provider (GitHub recommended)
2. Normalize provider events to N3 canonical event types (§7)
3. Represent PRs as PR Work Items with readiness computation (§2)
4. Evaluate at least one policy pack against external PR diffs (§8)
5. Produce evidence artifacts per N6 (§9)
6. Support Tier 0 and Tier 1 automation (§10)
7. Provide CLI command `mf fleet prs` for listing PR Work Items (§12)
8. Audit-log all provider write actions (§11)

A minimal compliant implementation MAY defer:

- Tier 2 and Tier 3 automation
- PR trains
- Multi-provider support
- Risk assessment beyond basic diff-size heuristics
- TUI views (CLI-only is sufficient for MCI)

---

## 16. Packaging Guidance (Informative)

A common pattern that preserves adoption while capturing value:

**Include in core (OSS-friendly):**

- PR Work Item modeling and readiness computation
- Basic multi-repo monitoring (Tier 0–1)
- Risk and readiness summaries via CLI
- Advisory policy evaluation (`:policies/mode :advisory`)

**Charge for (clear willingness-to-pay):**

- GitHub App managed auth/SSO/RBAC for multi-org
- Enforcing policy checks at scale + evidence retention/analytics
- PR trains and merge orchestration (Tier 3)
- Tier 2–3 automation (conversational + governance) with audit
- Web dashboard PR fleet views + notifications + cross-org aggregation
- Compliance/reporting exports and long-term evidence retention

---

## 17. References

- **N1**: Core Architecture & Concepts — new concepts land here
- **N3**: Event Stream & Observability — event envelope and PR lifecycle events
- **N4**: Policy Packs & Gates — policy evaluation contract
- **N5**: CLI/TUI/API — command surface and TUI views
- **N6**: Evidence & Provenance — evidence artifacts and provenance
- **N7**: Operational Policy Synthesis — Fleet Mode disambiguation
- **N8**: Observability Control Interface — capability levels and audit
- **RFC 2119**: Requirement level keywords

---

**Version History:**

- 0.1.0-draft (2026-02-07): Initial external PR integration specification
