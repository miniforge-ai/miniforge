<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 — Interface Standard: CLI/TUI/API

**Version:** 0.4.0-draft
**Date:** 2026-02-16
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **user-facing interfaces** for miniforge autonomous software factory:

- **CLI command taxonomy** - Command structure and argument conventions
- **TUI primitives** - Terminal UI components for workflow monitoring
- **API surface** - Minimal programmatic interface for workflow control
- **Operations console purpose** - Monitoring autonomous factory, NOT PR management
- **Manual override mechanisms** - How humans intervene when needed

The operations console (CLI/TUI/API) is the **window into the factory**, not the product itself.

### 1.1 Design Principles

1. **Observe, don't micromanage** - Interfaces show what's happening, don't require constant input
2. **Minimal friction** - Autonomous workflows require minimal human interaction
3. **Real-time visibility** - Show live progress, not just final results
4. **Evidence-first** - All interfaces provide access to evidence bundles
5. **Escape hatches** - Allow human override when automation fails

---

## 2. CLI Command Taxonomy

### 2.1 Command Structure

```text
miniforge <namespace> <command> [arguments] [flags]
```

All commands MUST follow this structure for consistency.

### 2.2 Core Namespaces

Implementations MUST provide these namespaces:

| Namespace  | Purpose                       | Example Commands                                           |
| ---------- | ----------------------------- | ---------------------------------------------------------- |
| `init`     | Initialize miniforge          | `miniforge init`                                           |
| `workflow` | Workflow execution            | `miniforge workflow execute`, `miniforge workflow status`  |
| `fleet`    | Local fleet management        | `miniforge fleet watch`, `miniforge fleet list`            |
| `policy`   | Policy pack management        | `miniforge policy list`, `miniforge policy install`        |
| `evidence` | Evidence bundle access        | `miniforge evidence show`, `miniforge evidence export`     |
| `artifact` | Artifact queries              | `miniforge artifact provenance`, `miniforge artifact list` |
| `etl`      | Repository → pack ETL         | `miniforge etl repo`, `miniforge etl report`               |
| `pack`     | Pack inspection and promotion | `miniforge pack list`, `miniforge pack promote`            |

### 2.3 Command Specifications

#### 2.3.1 Init Namespace

**Purpose:** Initialize miniforge on local machine

```bash
# Initialize miniforge (creates ~/.miniforge/)
miniforge init [flags]

Flags:
  --config PATH       Path to config file (default: ~/.miniforge/config.edn)
  --llm-api-key KEY   LLM API key (or use MINIFORGE_LLM_KEY env var)
  --workspace PATH    Workspace directory (default: ~/.miniforge/workspace)
```

**Requirements:**

- MUST create `~/.miniforge/` directory structure
- MUST initialize event store, artifact store, knowledge base
- MUST validate LLM API key if provided
- MUST emit `init/completed` event

**Example:**

```bash
$ miniforge init --llm-api-key sk-ant-...
✓ Created ~/.miniforge/
✓ Initialized event store
✓ Initialized artifact store
✓ Initialized knowledge base
✓ Validated LLM API key
miniforge ready to use
```

#### 2.3.2 Workflow Namespace

**Purpose:** Execute and monitor workflows

```bash
# Execute workflow from spec file
miniforge workflow execute SPEC_FILE [flags]

Arguments:
  SPEC_FILE           Path to workflow spec (.edn or .json)

Flags:
  --auto-approve      Auto-approve plan phase (skip human approval)
  --auto-merge        Auto-merge PR if all gates pass
  --dry-run           Show what would happen without executing
  --resume WORKFLOW_ID Resume failed workflow from last phase

Returns:
  Workflow ID (UUID)

# Show workflow status
miniforge workflow status WORKFLOW_ID [flags]

Flags:
  --follow, -f        Follow workflow progress (live updates)
  --events            Show full event stream
  --json              Output as JSON

# List workflows
miniforge workflow list [flags]

Flags:
  --status STATUS     Filter by status (:executing, :completed, :failed)
  --limit N           Show last N workflows (default: 10)
  --json              Output as JSON

# Show DAG kanban board (TUI)
miniforge workflow kanban DAG_ID [flags]

Flags:
  --refresh SECONDS   Refresh interval (default: 5)
  --json              Output task states as JSON (non-interactive)

# Cancel workflow
miniforge workflow cancel WORKFLOW_ID [flags]

Flags:
  --reason REASON     Cancellation reason (recorded in evidence)
```

**Requirements:**

- MUST validate spec file before execution
- MUST emit `workflow/started` event
- MUST return workflow ID immediately (non-blocking)
- MUST support resuming failed workflows

**Example:**

```bash
$ miniforge workflow execute specs/rds-import.edn
Workflow started: abc123-def456-789...

Watching progress (Ctrl+C to detach, workflow continues):
  ● Plan phase starting...
  ● Planner agent analyzing intent...
  ✓ Plan phase completed (2m 15s)
  ● Implement phase starting...
  ● Implementer agent generating code...
  ● Inner loop iteration 1/5: Validating...
  ✓ Validation passed
  ✓ Implement phase completed (5m 32s)
  ...
```

#### 2.3.3 Fleet Namespace

**Purpose:** Monitor local fleet (operations console)

```bash
# Watch fleet in TUI (operations console)
miniforge fleet watch [flags]

Flags:
  --refresh SECONDS   Refresh interval (default: 15)

# List active workflows
miniforge fleet list [flags]

Flags:
  --json              Output as JSON

# Show fleet statistics
miniforge fleet stats [flags]

Flags:
  --time-range RANGE  Time range (e.g., "24h", "7d", "30d")
  --json              Output as JSON
```

##### OPSV Commands (N7)

```bash
# Operational policy synthesis
miniforge fleet opsv plan SERVICE [flags]     # Generate Experiment Packs and risk/gate status
miniforge fleet opsv run SERVICE [flags]      # Execute experiment and converge
miniforge fleet opsv verify SERVICE [flags]   # Run verification suite
miniforge fleet opsv propose SERVICE [flags]  # Emit policy proposals without actuation
miniforge fleet opsv emit SERVICE [flags]     # PR-only emission
miniforge fleet opsv apply SERVICE [flags]    # Gated apply (if enabled)
```

##### Listener and Control Commands (N8)

```bash
# Listener management
miniforge listener list                       # List active listeners
miniforge listener attach WORKFLOW_ID         # Attach as OBSERVE listener
miniforge listener advise WORKFLOW_ID         # Attach as ADVISE listener
miniforge listener control WORKFLOW_ID        # Attach as CONTROL listener (requires auth)

# Workflow control actions
miniforge workflow pause WORKFLOW_ID          # Pause workflow execution
miniforge workflow resume WORKFLOW_ID         # Resume paused workflow
miniforge workflow retry WORKFLOW_ID          # Retry current phase
miniforge workflow rollback WORKFLOW_ID       # Rollback to checkpoint

# Agent control actions
miniforge agent quarantine AGENT_ID           # Quarantine agent
miniforge agent budget AGENT_ID --tokens=N    # Adjust agent budget

# Gate control actions
miniforge gate approve GATE_ID               # Approve pending gate
miniforge gate override GATE_ID --reason=     # Override gate failure

# Fleet control actions
miniforge fleet emergency-stop                # Emergency stop all workflows
miniforge fleet drain                         # Drain fleet (stop accepting, complete existing)
```

##### External PR Commands (N9)

```bash
# PR monitoring
miniforge fleet prs [flags]                   # List PR Work Items across repos
  --repo REPO                                 # Filter by repo
  --author AUTHOR                             # Filter by author
  --readiness STATE                           # Filter by readiness state
  --risk LEVEL                                # Filter by risk level
  --policy OUTCOME                            # Filter by policy outcome
  --json                                      # Output as JSON

miniforge fleet pr REPO#NUMBER [flags]        # Show PR Work Item detail
  --evidence                                  # Include evidence artifact pointers
  --json                                      # Output as JSON

# Train management (if trains enabled)
miniforge fleet trains [flags]                # List active trains
miniforge fleet train TRAIN_ID [flags]        # Show train detail and membership
```

**Requirements:**

- MUST show real-time workflow status
- MUST support keyboard navigation (vim-style)
- MUST refresh automatically (default: every 15s)
- MUST show agent activity, inner loop progress, gate status

**Example TUI (see Section 3):**

```text
╭─────────────────────────────────────────────────────────────╮
│ miniforge local fleet  [Workflows: 5 | Active: 2]   ⟳ 15s  │
├─────────────────────────────────────────────────────────────┤
│ WORKFLOW              STATUS       PHASE      PROGRESS      │
├─────────────────────────────────────────────────────────────┤
│ ● rds-import         executing    implement  ████▓▓▓▓▓▓ 40% │
│ ● k8s-migration      blocked      plan       ██▓▓▓▓▓▓▓▓ 20% │
│ ✓ vpc-update         completed    -          ██████████100% │
│ ● lambda-deploy      executing    verify     ██████▓▓▓▓ 60% │
╰─────────────────────────────────────────────────────────────╯
[j/k] Navigate  [Enter] Details  [e] Evidence  [q] Quit
```

#### 2.3.4 Policy Namespace

**Purpose:** Manage policy packs

```bash
# List installed policy packs
miniforge policy list [flags]

Flags:
  --available         Show available packs from registry
  --json              Output as JSON

# Install policy pack
miniforge policy install PACK_ID[@VERSION] [flags]

Arguments:
  PACK_ID             Policy pack ID (e.g., "terraform-aws")
  VERSION             Optional version (default: latest)

Flags:
  --from FILE         Install from local file
  --registry URL      Custom registry URL

# Show policy pack details
miniforge policy show PACK_ID [flags]

Flags:
  --rules             Show all rules in pack
  --json              Output as JSON

# Update policy packs
miniforge policy update [PACK_ID] [flags]

Flags:
  --all               Update all packs

# Repair violations (manual trigger)
miniforge policy repair WORKFLOW_ID [flags]

Flags:
  --rule RULE_ID      Only repair specific rule violations
  --dry-run           Show what would be repaired
```

**Requirements:**

- MUST validate pack schema before installation
- MUST support versioning (semantic versions)
- MUST show pack details (rules, severity, auto-fix capability)

**Example:**

```bash
$ miniforge policy install terraform-aws
Installing terraform-aws@1.2.3...
✓ Downloaded policy pack
✓ Validated schema
✓ Installed 15 rules
terraform-aws@1.2.3 ready to use

$ miniforge policy show terraform-aws
Policy Pack: terraform-aws (v1.2.3)
Description: AWS-specific Terraform validations
Author: miniforge.ai
License: Apache-2.0

Rules (15):
  [CRITICAL] no-public-s3-buckets - S3 buckets must not be public
  [HIGH]     require-encryption    - RDS/S3/EBS must be encrypted
  [MEDIUM]   require-tags          - Resources must have required tags
  ...
```

#### 2.3.5 Evidence Namespace

**Purpose:** Access evidence bundles

```bash
# Show evidence bundle for workflow
miniforge evidence show WORKFLOW_ID [flags]

Flags:
  --phase PHASE       Show evidence for specific phase only
  --format FORMAT     Output format (text, json, edn)
  --verbose, -v       Show full details

# Export evidence bundle
miniforge evidence export WORKFLOW_ID OUTPUT_PATH [flags]

Flags:
  --format FORMAT     Export format (edn, json, html)

# List evidence bundles
miniforge evidence list [flags]

Flags:
  --time-range RANGE  Time range filter
  --limit N           Show last N bundles
  --json              Output as JSON

# Validate evidence bundle integrity
miniforge evidence validate WORKFLOW_ID [flags]
```

**Requirements:**

- MUST show intent, phase evidence, validation results, outcome
- MUST support multiple output formats (text, JSON, EDN, HTML)
- MUST validate content hashes and provenance
- MUST make evidence queryable

**Example:**

```bash
$ miniforge evidence show abc123

Evidence Bundle: abc123-def456-789
Workflow: rds-import
Created: 2026-01-23 10:30:00 UTC

Intent:
  Type: IMPORT
  Description: Import existing RDS instance to Terraform state
  Constraints:
    - No resource creation
    - No resource destruction

Phase Evidence:
  ✓ Plan (2m 15s)
    Agent: Planner
    Artifacts: plan-document-xyz
    Output: Use Terraform import blocks

  ✓ Implement (5m 32s)
    Agent: Implementer
    Artifacts: code-changes-abc, terraform-plan-def
    Inner Loop: 2 iterations
    Output: Generated import blocks for RDS instance

  ✓ Verify (1m 45s)
    Agent: Tester
    Artifacts: test-results-ghi
    Output: Terraform plan shows 0 changes (state-only)

  ✓ Review (30s)
    Agent: Reviewer
    Artifacts: review-report-jkl
    Semantic Validation: PASS (IMPORT intent matches behavior)
    Policy Checks: PASS (0 violations)

Outcome:
  Status: Success
  PR: #234 (https://github.com/acme/terraform/pull/234)
  Merged: 2026-01-23 11:00:00 UTC
```

#### 2.3.6 Artifact Namespace

**Purpose:** Query artifacts and provenance

```bash
# Show artifact provenance (trace back to intent)
miniforge artifact provenance ARTIFACT_ID [flags]

Flags:
  --format FORMAT     Output format (text, json, graph)
  --verbose, -v       Show full provenance chain

# List artifacts for workflow
miniforge artifact list WORKFLOW_ID [flags]

Flags:
  --phase PHASE       Filter by phase
  --type TYPE         Filter by artifact type
  --json              Output as JSON

# Show artifact content
miniforge artifact show ARTIFACT_ID [flags]

Flags:
  --format FORMAT     Force output format (auto-detect by default)

# Search artifacts
miniforge artifact search QUERY [flags]

Flags:
  --type TYPE         Filter by type
  --time-range RANGE  Time range filter
  --limit N           Max results (default: 10)
```

**Requirements:**

- MUST show complete provenance chain (workflow → phase → agent → tools)
- MUST link artifact to original intent
- MUST support full-text search
- MUST validate artifact integrity (content hash)

**Example:**

```bash
$ miniforge artifact provenance terraform-plan-def

Artifact: terraform-plan-def
Type: terraform-plan
Created: 2026-01-23 10:08:10 UTC
Size: 1.2 KB
Hash: sha256:abc123...

Provenance:
  Workflow: abc123-def456 (rds-import)
  Original Intent: IMPORT existing RDS instance

  Created By:
    Phase: Implement
    Agent: Implementer (instance: xyz789)
    Event: event-id-456

  Source Artifacts:
    - plan-document-xyz (from Plan phase)

  Tool Executions:
    1. write-file (terraform/main.tf) - 45ms
    2. run-command (terraform plan) - 2.3s

  Subsequent Artifacts:
    - test-results-ghi (Verify phase used this plan)
    - review-report-jkl (Review phase validated this plan)

  Validation Results:
    ✓ Policy Check: terraform-aws (0 violations)
    ✓ Semantic Intent: IMPORT matches (0 creates, 0 destroys)

Full Evidence Bundle: miniforge evidence show abc123
```

---

#### 2.3.7 ETL Namespace

**Purpose:** Convert existing repositories (docs/specs/rules) into sanitized, schema-valid packs for workflow execution.

```bash
# Generate packs from a local repository
miniforge etl repo PATH [flags]

Arguments:
  PATH                Path to repository root

Flags:
  --emit DIR           Output directory for packs (default: ./packs)
  --report DIR         Output directory for reports (default: ./reports)
  --strict             Fail on any high-risk findings (default: false)
  --max-files N        Limit files considered (default: 5000)
  --include-globs G    Additional globs to include (repeatable)
  --exclude-globs G    Globs to exclude (repeatable)
  --dry-run            Show what would be processed without generating packs

# Show latest ETL report
miniforge etl report [flags]

Flags:
  --json               Output as JSON
```

**Requirements:**

- MUST emit a `:pack-index` manifest containing content hashes and trust labels
- MUST run `knowledge-safety` scanners (see N4) over untrusted sources
- MUST default generated packs to `:trust-level :untrusted`
- MUST emit `etl/*` lifecycle events (see N3)

---

#### 2.3.8 Pack Namespace

**Purpose:** Manage, inspect, install, and run packs (including Workflow Packs per N1 §2.24).

```bash
# Search for packs across configured registry roots
miniforge pack search QUERY [flags]

Flags:
  --type TYPE          Filter by pack type (feature|policy|agent-profile|workflow|index)
  --publisher PUB      Filter by publisher
  --capability CAP     Filter by required capability
  --json               Output as JSON

# List installed packs
miniforge pack list [flags]

Flags:
  --root DIR           Add an additional registry root
  --type TYPE          Filter by pack type (feature|policy|agent-profile|workflow|index)
  --json               Output as JSON

# Show pack details (including provenance, hash, capabilities, entrypoints)
miniforge pack show PACK_ID [flags]

# Install a pack from a registry root or local bundle
miniforge pack install PACK_ID[@VERSION] [flags]

Flags:
  --root DIR           Registry root to install from (or local path)
  --grant CAP          Pre-grant capability (repeatable; prompted interactively if omitted)
  --dry-run            Show what would be installed without installing

# Update an installed pack
miniforge pack update PACK_ID [flags]

Flags:
  --to VERSION         Target version (default: latest)
  --accept-capabilities  Accept capability changes without interactive prompt

# Remove an installed pack
miniforge pack remove PACK_ID [flags]

# Promote pack trust level (local OSS workflow)
miniforge pack promote PACK_ID [flags]

Flags:
  --to TRUST           Target trust level (trusted)
  --policy PACK_ID     Policy pack(s) used for promotion gate (repeatable, default: knowledge-safety)
  --sign               Sign promoted pack manifest (if key configured)

Policy Enforcement:
  By default, pack promotion requires passing ALL configured policy packs (AND logic).
  If multiple --policy flags are provided, the pack MUST pass all of them to be promoted.
  This is configurable in ~/.miniforge/config.edn under :pack-promotion/require-all-policies
  (default: true).

# Verify pack signature/hash
miniforge pack verify PACK_ID [flags]

# Run a Workflow Pack entrypoint
miniforge pack run PACK_ID[@VERSION] [flags]

Flags:
  --entry ENTRYPOINT   Entrypoint name (required if pack has multiple entrypoints)
  --input KEY=VALUE    Input parameter (repeatable)
  --inputs-file FILE   JSON/EDN file with input parameters
  --grant CAP          Grant capability for this run (repeatable)
  --pin-digest         Pin to exact content digest (no auto-update)
  --dry-run            Show what would be executed without running

# Configure pack trust policies
miniforge pack trust [flags]

Flags:
  --allow-publisher PUB    Add publisher to allowlist
  --deny-publisher PUB     Add publisher to denylist
  --min-trust-level LEVEL  Set minimum trust level for installs
  --show                   Show current trust configuration
```

**Requirements:**

- MUST treat pack promotion as a policy-gated operation
- MUST record pack hashes and promotion evidence in N6 evidence bundles
- MUST NOT allow untrusted packs to gain instruction authority without promotion/signature
- MUST present required capabilities before install and before run (interactive prompt)
- MUST deny write capabilities by default unless explicitly granted
- MUST require re-approval when pack update increases capabilities
- MUST emit pack lifecycle events (N3 §3.12) for install/update/remove
- MUST emit Pack Run events (N3 §3.12) for run start/complete/fail

## 3. TUI Primitives

### 3.1 TUI Purpose

The **TUI (Terminal UI)** is the primary operations console for monitoring the autonomous factory.

**It is NOT:**

- A PR review interface
- A code editor
- A chat interface

**It IS:**

- A real-time workflow monitor
- An evidence viewer
- An agent activity dashboard

### 3.2 TUI Views

Implementations MUST provide these views:

#### 3.2.1 Workflow List View (Primary View)

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ miniforge local fleet  [Workflows: 5 | Agents: 4 | Active: 2]   ⟳ 15s ago  │
├─────────────────────────────────────────────────────────────────────────────┤
│ WORKFLOW                  STATUS       PHASE      PROGRESS       AGE        │
├─────────────────────────────────────────────────────────────────────────────┤
│ ● rds-import             executing    implement  ████▓▓▓▓▓▓ 40%  2h        │
│ ● k8s-migration          blocked      plan       ██▓▓▓▓▓▓▓▓ 20%  1d        │
│ ✓ vpc-update             completed    review     ██████████ 100% 4h        │
│ ○ elasticache-import     pending      -          ▓▓▓▓▓▓▓▓▓▓ 0%   10m       │
│ ● lambda-deploy          executing    verify     ██████▓▓▓▓ 60%  30m       │
╰─────────────────────────────────────────────────────────────────────────────╯

[j/k] Navigate  [Enter] Details  [e] Evidence  [a] Artifacts  [q] Quit
```

**Requirements:**

- MUST show workflow ID (truncated), status, current phase, progress
- MUST update in real-time (default: 15s refresh)
- MUST support vim-style navigation (j/k to scroll)
- MUST show status indicators (●=active, ✓=completed, ✗=failed, ○=pending)

#### 3.2.2 Workflow Detail View

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ Workflow: rds-import (abc123-def456)                                        │
│ Status: Executing | Phase: Implement | Started: 2h ago                      │
├─────────────────────────────────────────────────────────────────────────────┤
│ Intent:                                                                     │
│   Type: IMPORT                                                              │
│   Description: Import existing RDS instance to Terraform state             │
│   Constraints: No creates, no destroys                                     │
│                                                                             │
│ Phases:                                                                     │
│   ✓ Plan       (2m 15s)  - Planner analyzed intent                         │
│   ○ Design     (skipped) - Low complexity                                  │
│   ● Implement  (5m 32s)  - Implementer generating code                     │
│      Agent: Implementer                                                     │
│      Status: Validating (inner loop iteration 2/5)                         │
│      Last activity: Writing file terraform/main.tf (10s ago)               │
│   ○ Verify     (pending)                                                   │
│   ○ Review     (pending)                                                   │
│   ○ Release    (pending)                                                   │
│                                                                             │
│ Inner Loop Progress:                                                        │
│   Iteration 1: FAIL - Found resource creates (violates IMPORT intent)      │
│                Repair: Removed resource blocks                             │
│   Iteration 2: Validating...                                               │
│                                                                             │
│ Artifacts: 2                                                                │
│ Events: 45                                                                  │
╰─────────────────────────────────────────────────────────────────────────────╯

[Esc] Back  [e] Evidence  [a] Artifacts  [v] Events  [c] Cancel
```

**Requirements:**

- MUST show workflow intent and constraints
- MUST show all phases with status (completed ✓, active ●, pending ○, failed ✗, skipped ○)
- MUST show current agent activity in real-time
- MUST show inner loop progress with iteration details
- MUST provide navigation to evidence, artifacts, events

#### 3.2.3 Evidence Viewer

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ Evidence Bundle: abc123-def456                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│ Intent                                                                      │
│   Type: IMPORT                                                              │
│   Description: Import existing RDS instance to Terraform state             │
│   Business Reason: Enable infrastructure-as-code management                │
│   Constraints:                                                              │
│     • No resource creation                                                  │
│     • No resource destruction                                               │
│                                                                             │
│ ▼ Plan Phase (2m 15s)                                                      │
│   Agent: Planner                                                            │
│   Approach: Use Terraform import blocks                                    │
│   Tasks: 3                                                                  │
│   Risks: State drift if import fails (LOW)                                 │
│   Artifacts: plan-document-xyz                                             │
│                                                                             │
│ ▼ Implement Phase (5m 32s)                                                │
│   Agent: Implementer                                                        │
│   Inner Loop: 2 iterations                                                  │
│   Files Changed: terraform/main.tf, terraform/rds.tf                       │
│   Artifacts: code-changes-abc, terraform-plan-def                          │
│                                                                             │
│ ▼ Semantic Validation                                                      │
│   Declared Intent: IMPORT                                                   │
│   Actual Behavior: IMPORT                                                   │
│   Creates: 0 | Updates: 0 | Destroys: 0                                   │
│   Status: ✓ PASS                                                           │
│                                                                             │
│ ▼ Policy Checks                                                            │
│   terraform-aws (v1.2.3): ✓ PASS (0 violations)                           │
│   foundations (v1.0.0):   ✓ PASS (0 violations)                           │
╰─────────────────────────────────────────────────────────────────────────────╯

[j/k] Scroll  [Space] Expand/Collapse  [Esc] Back  [x] Export
```

**Requirements:**

- MUST show complete evidence bundle
- MUST support expand/collapse for phase details
- MUST highlight validation results (pass/fail)
- MUST show semantic intent validation prominently
- MUST allow exporting evidence bundle

#### 3.2.4 Artifact Browser

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ Artifacts: abc123-def456                                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│ TYPE               PHASE       SIZE     CREATED                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ ▸ plan-document    Plan        2.4 KB   2h ago                             │
│ ▸ code-changes     Implement   8.1 KB   2h ago                             │
│ ▸ terraform-plan   Implement   1.2 KB   2h ago                             │
│ ▸ test-results     Verify      450 B    1h ago                             │
│ ▸ review-report    Review      3.2 KB   1h ago                             │
╰─────────────────────────────────────────────────────────────────────────────╯

[j/k] Navigate  [Enter] View  [p] Provenance  [Esc] Back
```

**Requirements:**

- MUST list all artifacts with type, phase, size, timestamp
- MUST allow viewing artifact content
- MUST allow viewing artifact provenance
- MUST support syntax highlighting for code artifacts

#### 3.2.5 DAG Kanban View

For DAG-based multi-task execution, implementations SHOULD provide a Kanban board
view derived as a **projection** of the DAG state and event stream.

The Kanban view is NOT a separate data model — it is computed from task states.

```text
╭────────────────────────────────────────────────────────────────────────────────╮
│ DAG: feature-auth-overhaul (5 tasks)                             ⟳ 5s ago     │
├──────────┬───────────┬───────────┬───────────┬───────────┬──────────────────┤
│ BLOCKED  │ READY     │ ACTIVE    │ IN REVIEW │ MERGING   │ DONE             │
├──────────┼───────────┼───────────┼───────────┼───────────┼──────────────────┤
│ ○ models │ ◉ routes  │ ● auth-svc│           │           │ ✓ schema-migrate │
│   └ auth │           │   impl    │           │           │                  │
│   └ svc  │           │   ⏱ 2m30s │           │           │                  │
│          │           │           │           │           │                  │
│ ○ tests  │           │           │           │           │                  │
│   └ auth │           │           │           │           │                  │
│   └ svc  │           │           │           │           │                  │
╰──────────┴───────────┴───────────┴───────────┴───────────┴──────────────────╯
[j/k] Navigate  [Enter] Task detail  [d] Dependency graph  [Esc] Back
```

**Column Mapping:**

| Column    | Task Statuses                                |
|-----------|----------------------------------------------|
| BLOCKED   | `:pending` with unmet dependencies            |
| READY     | `:pending` with all deps `:merged` (frontier) |
| ACTIVE    | `:implementing`, `:pr-opening`, `:responding` |
| IN REVIEW | `:ci-running`, `:review-pending`              |
| MERGING   | `:ready-to-merge`, `:merging`                 |
| DONE      | `:merged`, `:failed`, `:skipped`              |

**Requirements:**

- MUST derive columns from task state machine (N2 §13.2), NOT from separate state
- MUST show dependency edges for blocked tasks (which tasks they're waiting on)
- MUST update in real-time via event stream subscription
- SHOULD show elapsed time for active tasks
- SHOULD show `:failed` and `:skipped` tasks distinctly in DONE column (✗ vs ○)

#### 3.2.6 OPSV Drill-Down View (N7)

For operational policy synthesis, the TUI SHALL provide drill-down navigation:

```text
Fleet → Service → OPSV Runs → (Experiment Pack, Events, Evidence, Policy Diff, Verification)
```

**Requirements:**

- MUST show per-service "policy state" view (current vs proposed vs verified)
- MUST allow drill-down into evidence bundles and event streams per N6
- MUST show experiment progress, convergence iterations, and verification results

#### 3.2.7 Listener and Control Panel (N8)

The TUI MUST provide:

- **Listener panel**: Show active listeners and their capabilities (OBSERVE/ADVISE/CONTROL)
- **Control palette**: Quick access to control actions via keyboard shortcuts
- **Annotation overlay**: Display advisory annotations inline with workflow events
- **Approval queue**: Pending multi-party approvals for High/Critical actions

#### 3.2.8 PR Fleet View (N9)

```text
╭──────────────────────────────────────────────────────────────────────────────────────╮
│ miniforge fleet PRs  [Repos: 12 | PRs: 34 | Merge-Ready: 8]   ⟳ 15s ago            │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ REPO            PR#   TITLE             READINESS       RISK   POLICY  RECOMMEND     │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ acme/api        #42   Add auth endpoint ✓ merge-ready   low    pass    → merge       │
│ acme/api        #45   Fix rate limiter  ● ci-failing    med    pass    ◌ wait        │
│ acme/infra      #18   Scale RDS         ✓ merge-ready   high   pass    ⊘ approve     │
│ acme/frontend   #99   Dark mode         ○ needs-review  low    unknown ⊙ review      │
│ acme/api        #51   Migrate DB        ○ needs-review  low    FAIL    ⚡ remediate   │
│ acme/platform   #77   Monolith refactor ○ needs-review  med    pass    ◇ decompose   │
╰──────────────────────────────────────────────────────────────────────────────────────╯
j/k:nav  Enter:detail  O:open  Space:select  p:filter  c:chat  t:train  /:search  ::cmd
```

**Requirements:**

- MUST show PR Work Items across repos with readiness/risk/policy/recommend columns
- MUST derive from event stream (N3) and PR Work Item state — projections, not separate data
- MUST compute readiness using `pr-train/explain-readiness` weighted factors (deps, CI, approval, gates, age, staleness)
- MUST compute risk using `pr-train/assess-risk` explainable factors
  (change size, dep fanout, coverage, author, staleness, complexity, critical files)
- MUST evaluate policy using `policy-pack/evaluate-external-pr` against applicable policy packs
- MUST derive RECOMMEND column from enriched readiness, risk, policy, and PR size:
  - `→ merge` — all gates green, low risk, policy passes
  - `⊘ approve` — merge-ready but elevated risk requires human approval
  - `⊙ review` — awaiting review or non-auto-fixable policy violations
  - `⚡ remediate` — auto-fixable policy violations detected
  - `◇ decompose` — large PR (>500 lines) not yet reviewed
  - `◌ wait` — CI failing, draft, or awaiting signals

**Filter Palette:**

- `p` key MUST enter filter mode with field-qualified query syntax
- Supported field qualifiers: `repo:X`, `author:X`, `readiness:STATE`, `risk:LEVEL`, `policy:pass|fail`, `recommend:ACTION`
- Free text tokens MUST fuzzy-match against PR title
- Multiple qualifiers MUST compose with AND semantics
- Filter MUST update results incrementally on each keystroke
- `Enter` confirms filter, `Esc` clears

**Batch Actions:**

- Selection via `Space` key (toggle) and visual mode (`v`)
- When items are selected, footer MUST show `{n} selected` with available batch commands
- Supported batch commands:
  - `:review` — evaluate selected PRs against policy packs
  - `:remediate` — auto-fix policy violations for selected PRs (rules with repair functions)
  - `:decompose` — break a single selected PR into a DAG of smaller PRs
  - `:create-train NAME` — create a new merge train from selected PRs
  - `:add-to-train` — add selected PRs to active train

**Chat Integration:**

- `c` key MUST open conversational handoff to miniforge workflows
- In fleet context: passes selected PRs + active filter as chat context
- Dispatches through orchestrator (N7) for full agent capability

#### 3.2.9 PR Detail View (N9)

**Requirements:**

- MUST show readiness blockers, risk factors, policy results
- MUST allow drill-down to evidence artifacts (N6)
- MUST show automation tier and recent provider actions
- MUST display readiness factor breakdown from `pr-train/explain-readiness`:
  - deps-merged, ci-passed, approved, gates-passed, age-penalty, staleness-penalty
  - Each factor shows weight, score, and contribution
- MUST display risk factor breakdown from `pr-train/assess-risk`:
  - change-size, dependency-fanout, test-coverage-delta, author-experience, review-staleness, complexity-delta, critical-files
  - Each factor shows weight, score, value, and explanation
- MUST display policy evaluation results from `policy-pack/evaluate-external-pr`:
  - Per-rule outcome (pass/fail/warn) with severity and message
  - Summary counts (critical/major/minor/info)
- MUST show recommended action with explanation (why this action is suggested)
- `c` key MUST open chat pane for conversing about this specific PR (risk, approach, etc.)
- `O` key MUST open PR URL in default browser

#### 3.2.10 Train View (N9)

**Requirements:**

- MUST show ordered train members with merge readiness status
- MUST indicate which member is next for merge
- MUST show dependency edges between train members
- MUST support `:create-train NAME` — create a new merge train
- MUST support `:add-to-train` — add selected PRs from fleet to active train
- MUST support `:merge-next` — trigger merge of next ready PR in train
- MUST show per-PR readiness score and recommended action within train context
- Train merge orchestration MUST respect automation tier constraints (N9 §10)

#### 3.2.11 Pack Browser View

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ miniforge pack browser  [Installed: 12 | Available: 47]         ⟳ 30s ago  │
├─────────────────────────────────────────────────────────────────────────────┤
│ PACK                   PUBLISHER       TYPE       VER    STATUS    TRUST    │
├─────────────────────────────────────────────────────────────────────────────┤
│ ✓ pr-review            miniforge       workflow   1.2.0  installed trusted  │
│ ✓ tf-aws-foundations   miniforge       policy     2.0.1  installed trusted  │
│ ● risk-report          miniforge       workflow   0.9.0  update    trusted  │
│ ○ sprint-metrics       acme-co         workflow   1.0.0  available verified │
│ ○ k8s-drift-check      community       workflow   0.5.2  available unsigned │
╰─────────────────────────────────────────────────────────────────────────────╯

[j/k] Navigate  [Enter] Details  [i] Install  [u] Update  [x] Remove  [/] Search
```

**Requirements:**

- MUST list installed and available packs with publisher, type, version, status, trust
- MUST show signature/trust status prominently
- MUST support search and filtering by publisher, type, capability
- MUST allow installing, updating, and removing packs with capability grant review
- MUST present required capabilities for review before confirming install

#### 3.2.12 Run Launcher View

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ Run Pack: pr-review@1.2.0 (miniforge)                   ✓ signature valid  │
├─────────────────────────────────────────────────────────────────────────────┤
│ Entrypoint: [review-pr ▼]                                                  │
│                                                                             │
│ Inputs:                                                                     │
│   repo:     [acme/api         ]                                            │
│   pr_number:[42               ]                                            │
│   depth:    [standard ▼       ]                                            │
│                                                                             │
│ Capabilities Requested:                     Granted:                        │
│   github.pr.read                            ✓ auto                         │
│   github.pr.comment.write                   ○ requires approval            │
│   git.repo.checkout                         ✓ auto                         │
│                                                                             │
│ Trust: trusted | Digest: sha256:a1b2c3...                                  │
╰─────────────────────────────────────────────────────────────────────────────╯

[Tab] Next field  [Enter] Submit/Grant  [Esc] Cancel  [p] Pin digest
```

**Requirements:**

- MUST allow entrypoint selection when pack has multiple entrypoints
- MUST generate input forms from pack entrypoint schemas
- MUST show required capabilities with grant status (auto-granted reads vs pending writes)
- MUST show pack trust and signature status
- MUST support pinning to exact pack digest for reproducible runs
- MUST allow re-running with same inputs and pinned version

### 3.3 TUI Keyboard Navigation

Implementations MUST support:

| Key       | Action                          |
| --------- | ------------------------------- |
| `j` / `↓` | Move down                       |
| `k` / `↑` | Move up                         |
| `Enter`   | Select / Drill into             |
| `Esc`     | Go back / Exit                  |
| `Space`   | Expand/Collapse (in tree views) |
| `e`       | View evidence                   |
| `a`       | View artifacts                  |
| `v`       | View events                     |
| `c`       | Cancel workflow                 |
| `q`       | Quit TUI                        |
| `r`       | Refresh now                     |
| `b`       | Kanban board (DAG view)         |
| `?`       | Show help                       |

### 3.4 TUI Real-Time Updates

Implementations MUST:

1. Subscribe to event stream for active workflows
2. Update TUI on relevant events (status changes, phase transitions)
3. Throttle updates to avoid flickering (max 1 update per second)
4. Show "last updated" timestamp
5. Allow manual refresh with `r` key

---

## 4. API Surface

### 4.1 API Purpose

The **API** provides programmatic access for:

- CI/CD integration
- Custom tooling
- Third-party integrations
- Scripting and automation

The API is **minimal** - only essential operations exposed.

### 4.2 API Endpoints (HTTP REST)

Implementations SHOULD provide HTTP REST API:

#### 4.2.1 Workflow Control

```text
POST /api/workflows
  Body: Workflow spec (JSON or EDN)
  Returns: {:workflow-id uuid}

GET /api/workflows/:id
  Returns: Workflow status and details

GET /api/workflows
  Query params: ?status=executing&limit=10
  Returns: List of workflows

DELETE /api/workflows/:id
  Body: {:reason "cancellation reason"}
  Returns: {:cancelled true}
```

#### 4.2.2 Event Stream Subscription

```text
GET /api/workflows/:id/stream
  Returns: Server-Sent Events (SSE) stream

Event format:
  event: agent-status
  data: {"event/type":"agent/status","workflow/id":"...","message":"..."}
```

**Requirements:**

- MUST support Server-Sent Events (SSE)
- MUST emit all workflow events (see N3)
- MAY support WebSocket as alternative

#### 4.2.3 Evidence & Artifacts

```text
GET /api/evidence/:workflow-id
  Returns: Evidence bundle (JSON or EDN)

GET /api/artifacts/:artifact-id
  Returns: Artifact metadata and content

GET /api/artifacts/:artifact-id/provenance
  Returns: Provenance chain
```

#### 4.2.4 Fleet PR API (N9)

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

The Fleet event stream (§4.2.2) MUST support subscription filters for N9 event types,
enabling clients to subscribe to PR state changes, readiness changes, and policy changes.

### 4.3 API Authentication

Implementations SHOULD require authentication:

```text
Authorization: Bearer <token>
```

For local fleet (OSS), implementations MAY use:

- Local token stored in `~/.miniforge/token`
- No auth (localhost only)

For enterprise fleet, implementations MUST use:

- SSO integration (Okta, Auth0)
- RBAC for access control

### 4.4 API Rate Limiting

Implementations SHOULD enforce rate limits:

- 100 requests/minute per client (default)
- Configurable in `~/.miniforge/config.edn`

---

## 5. Operations Console Purpose

### 5.1 What the Console IS

The operations console (CLI/TUI/API) is:

1. **A monitoring interface** - Watch autonomous factory work
2. **An evidence viewer** - Access complete audit trails
3. **A manual override mechanism** - Intervene when automation fails

### 5.2 What the Console IS NOT

The operations console is NOT:

1. **A PR management tool** - PRs are artifacts, not the focus
2. **A code review interface** - Agents review code, humans review evidence
3. **An AI chat interface** - No conversational interaction needed
4. **A micromanagement tool** - Don't require human input for every step

### 5.3 User Mental Model

**Shift from:** "I'm reviewing PRs and approving code changes"
**To:** "I'm monitoring an autonomous factory and reviewing evidence bundles"

The console shows:

- What workflows are running (not just PRs)
- What agents are working on (not just code changes)
- What phase each workflow is in (not just PR status)
- Inner loop progress (validation/repair cycles)
- Evidence bundles (complete audit trail)

PRs are **outputs** of the factory, visible in Release phase and Evidence bundles.

---

## 6. Manual Override Mechanisms

### 6.1 Override Points

Implementations MUST provide manual override at these points:

#### 6.1.1 Plan Approval

After Plan phase, implementations SHOULD prompt for approval:

```bash
Plan generated for workflow abc123:

Approach: Use Terraform import blocks
Tasks:
  1. Write import block for RDS instance
  2. Validate terraform plan shows 0 changes
  3. Create PR with evidence bundle

Risks:
  [LOW] State drift if import fails

Approve plan? [Y/n]
```

Override options:

- Approve and continue
- Reject and modify spec
- Cancel workflow

#### 6.1.2 Gate Failure

When gate fails, implementations MUST prompt for action:

```bash
Policy gate failed: terraform-aws

Violations (2):
  [CRITICAL] No public S3 buckets
    Location: terraform/s3.tf:45 (aws_s3_bucket.data)
    Problem: S3 bucket 'my-data-bucket' has public ACL
    Auto-fix available

  [HIGH] Require encryption
    Location: terraform/rds.tf:12 (aws_db_instance.main)
    Problem: RDS instance missing encryption
    Auto-fix available

Actions:
  1. [a] Auto-repair all violations
  2. [m] Manual fix (pause workflow, resume after fix)
  3. [o] Override gate (requires justification)
  4. [c] Cancel workflow

Choose action [a/m/o/c]:
```

#### 6.1.3 Budget Exhausted

When inner loop retry budget exhausted:

```bash
Inner loop exhausted (5/5 iterations)

Last validation failure:
  [CRITICAL] Semantic intent mismatch
    Declared: IMPORT (no creates)
    Actual: CREATE (3 resource creates)

Agent repair attempts:
  Iteration 1: Removed resource blocks → Still had creates
  Iteration 2: Consulted Planner → Still had creates
  Iteration 3: Searched knowledge base → Still had creates
  Iteration 4: Asked human via message → Still had creates
  Iteration 5: Final attempt → Still had creates

Actions:
  1. [f] Fix manually and resume
  2. [s] Skip phase (dangerous!)
  3. [c] Cancel workflow

Choose action [f/s/c]:
```

### 6.2 Override Logging

All overrides MUST be logged in evidence bundle:

```clojure
{:override/type :gate-override
 :override/gate-id :policy-validation
 :override/workflow-id uuid
 :override/phase :implement

 :override/reason "Approved by security team: bucket needs public access for CDN"
 :override/approved-by "chris@example.com"
 :override/approved-at inst

 :override/violations-overridden [...]
 :override/justification "Public bucket required for static website hosting"}
```

---

## 7. Configuration

### 7.1 Configuration File

Implementations MUST support configuration file at `~/.miniforge/config.edn`:

```clojure
{:miniforge/version "0.1.0"

 :llm
 {:provider :anthropic
  :api-key-env "MINIFORGE_LLM_KEY"  ; Environment variable
  :model "claude-sonnet-4"
  :timeout-ms 60000
  :max-retries 3}

 :workflow
 {:default-policy-packs ["foundations"]
  :require-evidence? true
  :semantic-intent-check? true
  :inner-loop-max-iterations 5
  :auto-approve-plan? false
  :auto-merge-pr? false}

 :fleet
 {:tui-refresh-interval-seconds 15
  :max-concurrent-workflows 1}      ; OSS: single workflow

 :storage
 {:workspace-path "~/.miniforge/workspace"
  :event-store-path "~/.miniforge/events"
  :artifact-store-path "~/.miniforge/artifacts"
  :knowledge-base-path "~/.miniforge/knowledge"}

 :api
 {:enabled? false                   ; OSS: disabled by default
  :port 8080
  :host "127.0.0.1"}}
```

### 7.2 Environment Variables

Implementations MUST support these environment variables:

| Variable              | Purpose                                  |
| --------------------- | ---------------------------------------- |
| `MINIFORGE_LLM_KEY`   | LLM API key                              |
| `MINIFORGE_CONFIG`    | Path to config file                      |
| `MINIFORGE_WORKSPACE` | Workspace directory                      |
| `MINIFORGE_LOG_LEVEL` | Logging level (debug, info, warn, error) |

---

## 8. Conformance & Testing

### 8.1 CLI Conformance

Implementations MUST:

1. Support all required namespaces (init, workflow, fleet, policy, evidence, artifact)
2. Accept standard flags (--help, --version, --json)
3. Return 0 exit code on success, non-zero on failure
4. Emit structured logs to stderr, results to stdout
5. Support piping output to other commands

### 8.2 TUI Conformance

Implementations MUST:

1. Render correctly in terminal emulators (xterm, iTerm2, Terminal.app)
2. Support minimum terminal size (80x24)
3. Handle terminal resize gracefully
4. Update in real-time (max 15s lag)
5. Support vim-style keyboard navigation

### 8.3 API Conformance

Implementations MUST:

1. Follow REST conventions (GET for reads, POST for writes, DELETE for deletions)
2. Return JSON or EDN (accept via Content-Type header)
3. Provide OpenAPI spec (for API documentation)
4. Support CORS for browser clients (if enabled)

---

## 9. Example User Journeys

### 9.1 First-Time User

```bash
# Day 1: Install and initialize
$ brew install miniforge
$ miniforge init --llm-api-key sk-ant-...

# Run first workflow
$ miniforge workflow execute examples/rds-import.edn
Workflow started: abc123
Watching progress...
  ✓ Plan phase completed
  ✓ Implement phase completed
  ✓ Verify phase completed
  ✓ Review phase completed
  ✓ Release phase completed
Workflow completed! PR #234 created.

# View evidence
$ miniforge evidence show abc123
[Shows complete evidence bundle]
```

### 9.2 Regular User

```bash
# Check fleet status
$ miniforge fleet list
3 workflows active, 0 blocked, 12 completed today

# Watch fleet in TUI
$ miniforge fleet watch
[TUI shows real-time workflow progress]

# Workflow fails, check why
$ miniforge workflow status xyz789 --events
[Shows event stream with failure details]

# Resume failed workflow
$ miniforge workflow execute --resume xyz789
```

### 9.3 Power User

```bash
# Create custom workflow spec
$ cat > my-workflow.edn <<EOF
{:workflow/type :infrastructure-change
 :workflow/intent {:intent/type :update
                   :intent/description "Scale up RDS instance"}
 ...}
EOF

# Execute with auto-merge
$ miniforge workflow execute my-workflow.edn --auto-merge

# Query artifacts programmatically
$ miniforge artifact list $(miniforge workflow list --json | jq -r '.[0].id') --json

# Export evidence for compliance audit
$ miniforge evidence export abc123 /tmp/audit-report.html --format html
```

---

## 10. Rationale & Design Notes

### 10.1 Why Minimal CLI?

The CLI is minimal because:

- **Autonomous workflows need minimal input** - Most commands are just "execute" and "status"
- **TUI is the primary interface** - For monitoring and exploration
- **API for programmatic access** - For CI/CD and scripting

### 10.2 Why TUI Over Web Dashboard?

TUI is prioritized because:

- **Faster to build** - No web framework, no frontend complexity
- **Fits terminal workflow** - Platform engineers live in terminals
- **Low latency** - No HTTP overhead, direct access to local state
- **Offline capable** - Works without network

Web dashboard is **Enterprise feature** for multi-user visibility.

### 10.3 Why Minimal API?

API is minimal because:

- **OSS is local-first** - Most users don't need remote access
- **Enterprise will expand API** - For fleet coordination, analytics
- **Simple is maintainable** - Fewer endpoints, less to test

---

## 11. Future Extensions

### 11.1 Web Dashboard (Enterprise)

Enterprise features will add:

- Multi-user web dashboard
- Team collaboration
- Org-wide analytics
- Central policy management

### 11.2 IDE Integrations (Post-OSS)

Future versions may support:

- VS Code extension (inline evidence viewing)
- JetBrains plugin
- Neovim integration

### 11.3 Mobile App (Future Research)

Research directions:

- Mobile app for workflow monitoring
- Push notifications for workflow events
- Quick approval from mobile

---

## 12. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N1 (Architecture): Defines core concepts
- N2 (Workflow Execution): Defines workflow lifecycle
- N3 (Event Stream): API consumes event stream
- N4 (Policy Packs): CLI manages policy packs
- N6 (Evidence & Provenance): CLI/TUI views evidence bundles
- N7 (Operational Policy Synthesis): OPSV CLI commands and TUI drill-down (§2.3.3, §3.2.6)
- N8 (Observability Control Interface): Listener/control CLI commands and TUI panels (§2.3.3, §3.2.7)
- N9 (External PR Integration): Fleet PR CLI/TUI/API commands (§2.3.3, §3.2.8-3.2.10, §4.2.4)

---

**Version History:**

- 0.4.0-draft (2026-02-16): Extended pack namespace with search/install/update/remove/run/trust
  commands (§2.3.8); added Pack Browser (§3.2.11) and Run Launcher (§3.2.12) TUI views
- 0.3.0-draft (2026-02-07): Added extension spec interfaces from N7, N8, N9
  (§2.3.3, §3.2.6–§3.2.10, §4.2.4)
- 0.2.0-draft (2026-02-04): Added DAG Kanban view and task lifecycle CLI command (§3.2.5)
- 0.1.0-draft (2026-01-23): Initial CLI/TUI/API specification
