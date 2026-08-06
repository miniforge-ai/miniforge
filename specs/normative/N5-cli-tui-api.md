<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 — Interface Standard: CLI/TUI/API

**Version:** 0.5.0-draft
**Date:** 2026-08-05
**Status:** Draft
**Conformance:** MUST

_v0.5.0 adds the localization contract (§9), the CLI output and stability
contracts (§8.4–§8.5), terminal capability degradation (§8.6), configuration
precedence and validation (§7.3–§7.4), and conformance requirement IDs
(§8.7–§8.8); and reconciles the override path with N4 §6.3.1._

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
| `listener` | Listener attach/detach (N8)   | `miniforge listener list`, `miniforge listener attach`     |
| `agent`    | Agent control actions (N8)    | `miniforge agent quarantine`, `miniforge agent budget`     |
| `gate`     | Gate control actions (N8)     | `miniforge gate approve`, `miniforge gate override`        |

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

#### 2.3.3.1 Listener and Control Commands (N8)

These commands span the `listener`, `workflow`, `agent`, `gate`, and `fleet`
namespaces. They are grouped here because they share the N8 control-interface
contract, not because they belong to `fleet`.

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
j/k:nav  Enter:detail  O:open  Space:select  p:filter  C:chat  t:train  /:search  ::cmd
```

**Requirements:**

- MUST show PR Work Items across repos with readiness/risk/policy/recommend columns
- MUST derive from event stream (N3) and PR Work Item state — projections, not separate data
- MUST compute readiness per N9 §2.2, showing the weighted factors it defines
  (deps, CI, approval, gates, age, staleness)
- MUST compute risk per N9 §1.6 with explainable factors (change size, dep
  fanout, coverage, author, staleness, complexity, critical files)
- MUST evaluate policy per N4 §5.1.7 against the applicable policy packs
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

- `C` key MUST open conversational handoff to miniforge workflows (§3.3;
  lowercase `c` is Cancel everywhere in the TUI)
- In fleet context: passes selected PRs + active filter as chat context
- Dispatches through orchestrator (N7) for full agent capability

#### 3.2.9 PR Detail View (N9)

**Requirements:**

- MUST show readiness blockers, risk factors, policy results
- MUST allow drill-down to evidence artifacts (N6)
- MUST show automation tier and recent provider actions
- MUST display the readiness factor breakdown defined by N9 §2.2:
  - deps-merged, ci-passed, approved, gates-passed, age-penalty, staleness-penalty
  - Each factor shows weight, score, and contribution
- MUST display the risk factor breakdown defined by N9 §1.6:
  - change-size, dependency-fanout, test-coverage-delta, author-experience, review-staleness, complexity-delta, critical-files
  - Each factor shows weight, score, value, and explanation
- MUST display policy evaluation results per N4 §5.1.7:
  - Per-rule outcome (pass/fail/warn) with severity and message
  - Summary counts (critical/major/minor/info)
- MUST show recommended action with explanation (why this action is suggested)
- `C` key MUST open a chat pane scoped to this PR (risk, approach, etc.)
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
| `C`       | Conversational handoff (§3.2.8) |
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
GET /api/streams/:scope-type/:scope-id
  Returns: Server-Sent Events (SSE) stream

GET /api/workflows/:id/stream
  Alias for scope-type=workflow

Event format:
  event: agent-status
  data: {"event/type":"agent/status","workflow/id":"...","message":"..."}
```

**Requirements:**

- MUST support Server-Sent Events (SSE)
- MUST emit all events for the subscribed scope (see N3)
- MAY support WebSocket as alternative

**N3 owns this wire contract.** N3 §5.3 defines the endpoint shape,
authentication, listener attach handshake, subscription filters,
resume-from-sequence, backpressure, and wire format. This section names the
endpoint the console consumes; it MUST NOT restate or diverge from N3 §5.3.

In particular, the stream is single-scope: N3 §2.3 defines six scopes
(workflow, PR Work Item, pack, repository, supervisory entity, deployment) and
the console subscribes to one per connection.

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

```text
Authorization: Bearer <token>
```

Per N3 §5.3.2, which owns this contract:

- Unauthenticated requests MAY succeed only in local mode bound to
  `localhost`. Any network-exposed deployment MUST fail with HTTP 401.
- Tokens resolve to a principal and RBAC role (N8 §2.3).

For local fleet (OSS), implementations MAY use a token stored in
`~/.miniforge/token`, or no token while bound to loopback.

For enterprise fleet, implementations MUST use SSO integration and RBAC.

The earlier "SHOULD require authentication" phrasing is withdrawn: it read as
optional for a network-exposed console, which N3 §5.3.2 forbids.

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
3. **A chat-first interface** - The console does not require conversation to
   operate, and no primary workflow is driven through a chat prompt. It does
   provide a **conversational handoff** (§3.2.8, §3.2.9): a key that carries
   the current selection and filter into a workflow, so an operator who has
   found something can act on it without retyping context. That is an
   affordance on top of the views, not the way the console is used.
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
  3. [c] Cancel workflow

Choose action [a/m/c]:

Override is not offered: CRITICAL and HIGH violations are not overridable
here (N4 §6.3.1). Bypassing them requires multi-party approval (N8 §3).
```

**Override availability is not a UI choice.** Implementations MUST offer `[o]`
only when N4 §6.3.1 permits it — the gate declares `:gate/allow-override?` and
every unrepaired violation is `:medium` or lower. Presenting an override that
the policy layer will refuse trains operators to expect a bypass that does not
exist; presenting one the policy layer would _accept_ for a `:critical`
violation is worse.

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

An override of a policy gate MUST produce a **Waiver** as defined in
N5-delta-supervisory-control-plane §3.1 and required by N4 §6.3.1:

```clojure
{:waiver/id            uuid
 :waiver/evaluation-id uuid      ; the PolicyEvaluation being waived
 :waiver/violations    [keyword] ; rule IDs waived (N4 §2.3)
 :waiver/actor         string    ; who granted it
 :waiver/reason        string    ; justification — REQUIRED
 :waiver/timestamp     inst}
```

The Waiver is the record; the console is the surface that collects it. An
override with no `:waiver/reason` MUST be refused at the prompt rather than
recorded with an empty justification.

Per N4 §6.3.1 the waived gate stays failed and its violations stay present.
Implementations MUST NOT render a waived gate as passing in any view — §3.2.3's
evidence viewer, §3.2.8's POLICY column, or elsewhere. "Waived" is its own
state, and collapsing it into "pass" destroys the audit trail the waiver exists
to create.

Overrides at non-gate decision points (§6.1.1 plan approval, §6.1.3 budget
exhaustion) are not policy waivers. They MUST be recorded in the evidence
bundle per N6 with the deciding principal, timestamp, and justification, but
they do not produce a Waiver — there is no PolicyEvaluation to waive.

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

 :locale "en-US"                   ; User locale for console output (§9.4)

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
| `MINIFORGE_LOCALE`    | User locale for console output (§9.4)     |
| `NO_COLOR`            | Disable color output (§8.6)              |

### 7.3 Configuration Precedence

A setting can arrive from four places. Implementations MUST resolve them in
this order, first match winning:

1. Command-line flag
2. Environment variable
3. Configuration file (`~/.miniforge/config.edn`, or `MINIFORGE_CONFIG`)
4. Built-in default

Precedence MUST be uniform. A setting that reads its flag but ignores its
environment variable, or vice versa, is non-conformant — an operator cannot
reason about configuration that resolves differently per setting.

`miniforge config show` SHOULD render the effective configuration and, for each
setting, which layer supplied it. Debugging a wrong value otherwise requires
guessing.

### 7.4 Configuration Validation

Implementations MUST validate the configuration file against a schema at load:

- An unparseable or schema-invalid config MUST fail startup with exit code 3
  (§8.4.2) and an error naming the offending key and why.
- An **unknown** key MUST warn rather than fail. A typo'd key silently ignored
  is how an operator concludes a setting does not work; failing outright makes
  a config written for a newer version unusable on an older one.
- Secrets MUST NOT be stored in the config file. `:api-key-env` names an
  environment variable precisely so the key itself never lands on disk;
  implementations MUST reject a config that inlines a key value.

Configuration is data, not code. Implementations MUST NOT evaluate the config
file as a program or resolve arbitrary symbols from it.

---

## 8. Conformance & Testing

### 8.1 CLI Conformance

Implementations MUST:

1. Support every namespace §2.2 marks MUST — currently init, workflow, fleet,
   policy, evidence, artifact, etl, pack, listener, agent, gate
2. Accept standard flags (`--help`, `--version`, `--json`, `--locale`)
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

### 8.4 CLI Output Contract

§8.1 requires results on stdout and logs on stderr. This section makes that
usable by a script.

#### 8.4.1 Streams

- **stdout** carries the command's result and nothing else. A command whose
  result is data MUST NOT interleave progress, warnings, or decoration into
  stdout.
- **stderr** carries progress, warnings, and diagnostics.
- With `--json`, a non-streaming command's stdout MUST contain exactly one JSON
  document. A streaming command MUST instead emit newline-delimited JSON, one
  document per line. Whether a command streams is a property of the command,
  MUST be stated in its `--help`, and MUST NOT vary by invocation — a consumer
  chooses its parser before it sees output.

A command that writes a progress spinner to stdout breaks every pipeline that
consumes it, which is why the split is normative rather than stylistic.

#### 8.4.2 Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Command failed — the operation ran and did not succeed |
| 2 | Usage error — bad flags, missing arguments, unknown command |
| 3 | Configuration error — config invalid or unreadable (§7) |
| 4 | Policy refusal — a gate blocked the operation (N4) |
| 5 | Not found — the named workflow, artifact, or pack does not exist |
| 130 | Interrupted (SIGINT) |

A workflow that runs and fails its gates exits 4, not 1: a caller MUST be able
to distinguish "the tool broke" from "the tool worked and said no".

Detaching from a followed workflow (Ctrl+C during `--follow`) exits 0 — the
workflow continues, and the console said so (§2.3.2). Interrupting a command
that was itself doing the work exits 130.

#### 8.4.3 JSON Output Stability

`--json` output is a wire contract, not a rendering:

- Keys MUST be stable across patch and minor releases. Adding a key is minor;
  removing or renaming one is major (§8.5).
- Values MUST NOT vary by locale (§9.2). A localized JSON payload cannot be
  parsed by a script that does not know the operator's locale.
- Enum values MUST be the keyword's name, not a display label — `merge-ready`,
  never "Merge Ready".
- A consumer MUST treat an absent key and a `null` value identically. Producers
  MAY do either. Requiring consumers to distinguish them would make adding an
  optional key a breaking change.

#### 8.4.4 Error Format

An error MUST be reported with a stable machine-readable code and a
catalog-sourced message (§9):

```json
{"error": {"code": "workflow-not-found",
           "message": "No workflow with id abc123",
           "details": {"workflow-id": "abc123"}}}
```

`code` is dispatch and MUST NOT be localized. `message` is prose and MUST come
from the catalog. Without a stable code, the only way to branch on an error is
to match its text, which breaks the moment the text is translated.

### 8.5 Command Stability

The CLI is a contract. Scripts depend on it.

| Change | Bump |
|--------|------|
| Adding a command, namespace, or flag | MINOR |
| Adding a key to `--json` output | MINOR |
| Changing a default | MAJOR |
| Removing or renaming a command, namespace, or flag | MAJOR |
| Removing or renaming a `--json` key | MAJOR |
| Changing an exit code's meaning | MAJOR |

A removed or renamed command MUST first be deprecated: it keeps working, warns
on stderr naming its replacement, and is removed no earlier than the next MAJOR.
Deprecation warnings MUST go to stderr so they never corrupt piped output.

Because the product is pre-release, this spec does not require a deprecation
period to have already elapsed for changes made before 1.0. It requires that
the classification above be applied from now on.

### 8.6 Terminal Capability Degradation

§8.2 requires the TUI to render in common emulators. It MUST also render in
constrained ones.

Implementations MUST detect and degrade for:

- **No color** — when `NO_COLOR` is set, `TERM=dumb`, or stdout is not a TTY.
  Status MUST remain distinguishable without color; the glyphs of §3.2.1
  (`●`, `✓`, `✗`, `○`) carry status independently, which is why they are
  normative rather than decorative.
- **No Unicode** — when the locale or terminal cannot render the box-drawing
  and status glyphs, implementations MUST substitute ASCII equivalents rather
  than emitting replacement characters.
- **Narrow terminals** — below the 80×24 minimum, implementations MUST render a
  reduced view or a clear message, and MUST NOT emit corrupted layout.

Color MUST NOT be the only carrier of meaning anywhere in the TUI. An operator
using a monochrome terminal, or with a color-vision deficiency, sees the same
information.

### 8.7 Conformance Requirements

Requirement IDs are stable identifiers for the normative statements of this
spec, so a conformance suite can cite what it tests. IDs are never reused; a
withdrawn requirement is marked withdrawn, not deleted.

#### CLI

| ID | Level | Requirement |
|----|-------|-------------|
| N5.CLI.1 | MUST | Follow `miniforge <namespace> <command>` structure (§2.1). |
| N5.CLI.2 | MUST | Provide every namespace §2.2 marks MUST (§8.1). |
| N5.CLI.3 | MUST | Accept `--help`, `--version`, `--json`, `--locale` on every command (§8.1, §9.4). |
| N5.CLI.4 | MUST | Results on stdout, diagnostics on stderr, never interleaved (§8.4.1). |
| N5.CLI.5 | MUST | Emit exactly one JSON document under `--json`, or documented NDJSON (§8.4.1). |
| N5.CLI.6 | MUST | Use the exit codes of §8.4.2, distinguishing failure from policy refusal. |
| N5.CLI.7 | MUST NOT | Vary `--json` keys or values by locale (§8.4.3, §9.2). |
| N5.CLI.8 | MUST | Report errors with a stable code and catalog-sourced message (§8.4.4). |
| N5.CLI.9 | MUST | Classify command changes per §8.5 and warn on stderr when deprecated. |

#### TUI

| ID | Level | Requirement |
|----|-------|-------------|
| N5.TUI.1 | MUST | Provide the views of §3.2 that its feature set implies. |
| N5.TUI.2 | MUST | Support the key bindings of §3.3, unmodified by locale (§9.3). |
| N5.TUI.3 | MUST | Derive every view from the event stream and entity state, never a separate model (§3.2.5, §3.2.8). |
| N5.TUI.4 | MUST | Update in real time and throttle to at most one repaint per second (§3.4). |
| N5.TUI.5 | MUST | Render within 80×24 and degrade below it without corruption (§8.2, §8.6). |
| N5.TUI.6 | MUST NOT | Use color as the only carrier of meaning (§8.6). |
| N5.TUI.7 | MUST | Substitute ASCII when Unicode glyphs are unavailable (§8.6). |
| N5.TUI.8 | MUST NOT | Render a waived gate as passing (§6.2). |

#### API

| ID | Level | Requirement |
|----|-------|-------------|
| N5.API.1 | MUST | Follow REST conventions and return JSON or EDN by content negotiation (§8.3). |
| N5.API.2 | MUST | Consume the event stream per N3 §5.3 without restating or diverging from it (§4.2.2). |
| N5.API.3 | MUST | Fail 401 for unauthenticated requests in any network-exposed deployment (§4.3, N3 §5.3.2). |
| N5.API.4 | MUST | Provide an OpenAPI description of the exposed surface (§8.3). |

#### Configuration and localization

| ID | Level | Requirement |
|----|-------|-------------|
| N5.CFG.1 | MUST | Resolve settings flag → env → file → default, uniformly (§7.3). |
| N5.CFG.2 | MUST | Fail startup with exit 3 on an invalid config, naming the key (§7.4). |
| N5.CFG.3 | MUST | Warn, not fail, on an unknown config key (§7.4). |
| N5.CFG.4 | MUST NOT | Accept an inlined secret in the config file (§7.4). |
| N5.L10N.1 | MUST NOT | Emit an authored prose string as a literal at the emit site (§9.1). |
| N5.L10N.2 | MUST | Route user-facing prose to the user catalog and developer-facing prose to the system catalog (§9.1). |
| N5.L10N.3 | MUST NOT | Localize command names, flag names, key bindings, or enum values (§9.2, §9.3). |
| N5.L10N.4 | MUST | Resolve locale per §9.4 and fall back to `en-US` on a missing key. |
| N5.L10N.5 | MUST NOT | Render a raw catalog key to a user (§9.4). |

#### Override

| ID | Level | Requirement |
|----|-------|-------------|
| N5.OV.1 | MUST | Offer gate override only where N4 §6.3.1 permits it (§6.1.2). |
| N5.OV.2 | MUST | Produce a Waiver, with a justification, for every gate override (§6.2). |
| N5.OV.3 | MUST | Record non-gate overrides in the evidence bundle per N6 (§6.2). |

### 8.8 Test Obligations

A conformance suite MUST cover, at minimum:

1. **Stream separation** — piping a `--json` command's stdout to a parser
   succeeds while progress output is present on stderr (N5.CLI.4, N5.CLI.5).
2. **Exit code discrimination** — a workflow whose gates fail exits 4; a
   malformed flag exits 2; an unreadable config exits 3 (N5.CLI.6).
3. **Locale invariance of data** — the same command under two locales produces
   byte-identical `--json` output (N5.CLI.7).
4. **Catalog coverage** — no emitted prose originates at an emit site, and
   every key referenced exists in `en-US` (N5.L10N.1, N5.L10N.5).
5. **Binding invariance** — key bindings are identical under every locale
   (N5.L10N.3, N5.TUI.2).
6. **Degradation** — the TUI renders usably with `NO_COLOR`, with `TERM=dumb`,
   and in a terminal narrower than 80 columns (N5.TUI.5, N5.TUI.6, N5.TUI.7).
7. **Config precedence** — a setting supplied at all four layers resolves to
   the flag, and removing layers falls through in order (N5.CFG.1).
8. **Waiver visibility** — a waived gate renders as waived, never as passing,
   in every view that shows gate state (N5.TUI.8, N5.OV.2).

---

## 9. Localization

Every interface this spec defines emits prose: CLI output, TUI labels and
footers, prompts, error messages, API error bodies. This section states the
contract those strings obey.

### 9.1 No Raw Prose

Implementations MUST NOT emit an authored, human-readable string as a literal
at the emit site. Every such string MUST be looked up from a **catalog** by
key. This is the `foundations/localization` standard (dewey 050) applied to
the console surface; it is normative here because N5 defines the surface that
produces the most prose in the system.

Two catalogs, chosen by who reads the string:

| Catalog | Holds |
|---------|-------|
| User locale (`en-US.edn`, and future locales) | Anything a user sees — TUI labels, CLI output, prompts, formatted error responses |
| System locale (`system.edn`) | Anything a user never sees — logs, telemetry attributes, internal error tags |

The split is by destination, not by whether the string deserves translating.
A developer-facing string reclassified onto a user surface moves catalogs
without touching the emit site.

### 9.2 What Is Not Prose

These are exempt and MUST NOT be routed through a catalog:

- Machine dispatch values: command names, flag names, namespace names, event
  type keywords, JSON and EDN keys.
- Dynamically rendered values: IDs, counts, durations, file paths, user data.
- Programmer-error assertions that indicate an invariant break.

A `--json` payload carries data, not prose. Its keys are a wire contract
(§8.4) and MUST NOT vary by locale. Human-readable values _inside_ a JSON
payload — a rendered message field — are prose and come from the catalog.

### 9.3 Consequences for the Interface

- **CLI**: `--help` text, error messages, and progress lines are prose.
  Command and flag names are not, and MUST NOT be localized — a script that
  runs `miniforge workflow execute` MUST work under any locale.
- **TUI**: column headings, status labels, footer hints, and prompt text are
  prose. Key bindings are not: `j`/`k`/`q` are dispatch, and MUST NOT be
  rebound by locale (§3.3).
- **API**: error `:message` fields are prose. Error codes, field names, and
  enum values are not.

### 9.4 Locale Selection

Implementations MUST resolve the user locale in this order, first match
winning: the `--locale` flag, the `MINIFORGE_LOCALE` environment variable, the
`:locale` config key (§7.1), the host locale, then `en-US`.

A key missing from the resolved locale's catalog MUST fall back to `en-US`
rather than rendering the key or an empty string. A key missing from `en-US`
is a defect: implementations MUST surface it in development and MUST NOT ship
an interface that renders a raw key to a user.

---

## 10. Example User Journeys

### 10.1 First-Time User

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

### 10.2 Regular User

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

### 10.3 Power User

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

## 11. Rationale & Design Notes

### 11.1 Why Minimal CLI?

The CLI is minimal because:

- **Autonomous workflows need minimal input** - Most commands are just "execute" and "status"
- **TUI is the primary interface** - For monitoring and exploration
- **API for programmatic access** - For CI/CD and scripting

### 11.2 Why TUI Over Web Dashboard?

TUI is prioritized because:

- **Faster to build** - No web framework, no frontend complexity
- **Fits terminal workflow** - Platform engineers live in terminals
- **Low latency** - No HTTP overhead, direct access to local state
- **Offline capable** - Works without network

Web dashboard is **Enterprise feature** for multi-user visibility.

### 11.3 Why Minimal API?

API is minimal because:

- **OSS is local-first** - Most users don't need remote access
- **Enterprise will expand API** - For fleet coordination, analytics
- **Simple is maintainable** - Fewer endpoints, less to test

---

## 12. Future Extensions

### 12.1 Web Dashboard (Enterprise)

Enterprise features will add:

- Multi-user web dashboard
- Team collaboration
- Org-wide analytics
- Central policy management

### 12.2 IDE Integrations (Post-OSS)

Future versions may support:

- VS Code extension (inline evidence viewing)
- JetBrains plugin
- Neovim integration

### 12.3 Mobile App (Future Research)

Research directions:

- Mobile app for workflow monitoring
- Push notifications for workflow events
- Quick approval from mobile

---

## 13. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N1 (Architecture): Defines core concepts
- N2 (Workflow Execution): Defines workflow lifecycle
- N3 (Event Stream): API consumes event stream
- N4 (Policy Packs): CLI manages policy packs
- N6 (Evidence & Provenance): CLI/TUI views evidence bundles
- N7 (Operational Policy Synthesis): OPSV CLI commands and TUI drill-down (§2.3.3, §3.2.6)
- N8 (Observability Control Interface): Listener/control CLI commands and TUI panels (§2.3.3, §3.2.7)
- N9 (External PR Integration): Fleet PR CLI/TUI/API commands (§2.3.3, §3.2.8-3.2.10, §4.2.4);
  readiness (§2.2) and risk (§1.6) contracts the TUI renders
- N5-delta-supervisory-control-plane: supervisory entities the TUI projects; Waiver (§3.1)
- N5-delta-2-pr-scoring: PR readiness/risk scoring surfaced in §3.2.8–§3.2.9
- N5-delta-3-observational-entities: evidence, artifact, task-node, decision, and
  pack-manifest entities; Run Launcher (§3.2.12) scope
- N5-delta-4-automation-edge-correlator: automation edges surfaced in fleet views
- `standards/miniforge/foundations/localization` (dewey 050): the catalog rule §9 applies

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**. It records where the miniforge implementation
diverges from the contract above, as of 2026-08-05. It is not a relaxation of
any requirement in §1–§13.

### A.1 Specified, Not Implemented

- **Namespaces.** The CLI implements `init`, `workflow`, `fleet`, `policy`,
  `evidence`, `artifact`, and `etl`. §2.2 also marks `pack`, `listener`,
  `agent`, and `gate` MUST; none has a command surface (N5.CLI.2). The `pack`
  namespace is the largest gap — §2.3.8 specifies eleven commands.
- **`NO_COLOR` and terminal degradation (§8.6).** No handling anywhere in the
  tree. The TUI has no documented behaviour under `TERM=dumb`, a non-TTY
  stdout, or a terminal narrower than 80 columns (N5.TUI.5–7).
- **Exit code taxonomy (§8.4.2).** Policy refusal is not distinguished from
  command failure, so a caller cannot tell "the tool broke" from "the tool
  worked and said no" (N5.CLI.6).
- **Config precedence and validation (§7.3–§7.4).** No uniform resolution
  order and no schema validation at load.
- **Command stability (§8.5).** No deprecation mechanism.

### A.2 Implemented, Matching

- **Localization scaffolding (§9).** `bases/cli`, `components/tui-views`, and
  `components/web-dashboard` each carry
  `resources/config/<component>/messages/en-US.edn`, and
  `bases/cli/.../messages.clj` resolves an active locale with an `en-US`
  default. §9 writes down the contract that machinery already implements.
  Coverage is not audited: §9.1 requires _every_ emitted prose string to route
  through a catalog, and nothing verifies that today (N5.L10N.1).

### A.3 Structural

- **Locale resolution order.** `messages.clj` derives the locale from the host
  language. §9.4 puts `--locale` and `MINIFORGE_LOCALE` ahead of it; neither
  is currently consulted.

---

**Version History:**

- 0.5.0-draft (2026-08-05): Spec-completion pass.
  **New normative sections:** localization contract (§9) applying dewey 050 to
  the console surface — no raw prose, two catalogs by destination, what is not
  prose, locale resolution; CLI output contract (§8.4) — stream separation,
  exit codes, `--json` stability, error format; command stability and
  deprecation (§8.5); terminal capability degradation (§8.6); configuration
  precedence and validation (§7.3–§7.4); conformance requirement IDs and test
  obligations (§8.7–§8.8).
  **Contract fixes:** §5.2 claimed the console is not a chat interface while
  §3.2.8 and §3.2.9 mandated a chat key — reworded to distinguish chat-first
  operation from conversational handoff; `c` collided between Cancel (§3.3)
  and chat, so chat moved to `C`; §2.2's namespace table gained `listener`,
  `agent`, and `gate`, which §2.3.3 already defined commands for; §2.3.3's
  control commands moved out of the fleet namespace they were nested under;
  §8.1's required-namespace list resynced with §2.2; §6.1.2 no longer offers
  override for a CRITICAL violation, which N4 §6.3.1 forbids; §6.2's bespoke
  `:override/*` record replaced by the Waiver of
  N5-delta-supervisory-control-plane §3.1; §4.2.2 and §4.3 aligned with N3
  §5.3's streaming and authentication contract; §3.2.8–§3.2.9 stopped
  mandating implementation namespaces (`pr-train/*`, `policy-pack/*`) and now
  reference the N9 and N4 contracts, per standard 020.
  **Structural:** §9 inserted; former §9–§12 renumbered to §10–§13.
  Annex A records implementation divergence.
- 0.4.0-draft (2026-02-16): Extended pack namespace with search/install/update/remove/run/trust
  commands (§2.3.8); added Pack Browser (§3.2.11) and Run Launcher (§3.2.12) TUI views
- 0.3.0-draft (2026-02-07): Added extension spec interfaces from N7, N8, N9
  (§2.3.3, §3.2.6–§3.2.10, §4.2.4)
- 0.2.0-draft (2026-02-04): Added DAG Kanban view and task lifecycle CLI command (§3.2.5)
- 0.1.0-draft (2026-01-23): Initial CLI/TUI/API specification
