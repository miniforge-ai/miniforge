<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# miniforge.ai UX Design Specification

**Date:** 2026-01-22
**Goal:** Information-dense, high-throughput interfaces for managing 100+ PRs/day
**Oracles:** XTreeGold (CLI), K9s (CLI), Raycast (speed), Linear (information density)

---

## Design Principles

### 1. Optimize for Throughput, Not Exploration

**Problem:** At org scale, you'll see 1000+ PRs. Can't spend 2 minutes on each.

**Solution:** Email triage model

- See critical info in <5 seconds
- Make decision in <10 seconds
- Batch similar actions
- Keyboard-driven everything

### 2. Information Density Without Clutter

**Problem:** Need to see: train status, repo, CI, approvals, dependencies, policy violations, evidence - all at once.

**Solution:** Progressive disclosure

- Level 1: Scannable table (status, repo, title, blockers)
- Level 2: Expanded row (CI details, dependencies, approvers)
- Level 3: Full details modal (evidence bundle, diffs, logs)

### 3. Context Switching Must Be Instant

**Problem:** Jump between trains, repos, PRs, evidence bundles constantly.

**Solution:** Vim-like navigation + fuzzy search

- `t` - Switch train
- `r` - Switch repo
- `/` - Fuzzy search PRs
- `g` - Go to (train/pr/repo by ID)
- `Tab` - Cycle through views

### 4. Batch Operations for Similar PRs

**Problem:** Approving 50 Dependabot PRs one-by-one is insane.

**Solution:** Multi-select + bulk actions

- `Space` - Mark PR
- `a` - Approve all marked
- `m` - Merge all marked
- Filter + select all matching

---

## Part 1: Fleet CLI TUI (K9s-Inspired)

### Reference: Why K9s Works

**K9s strengths:**

1. **Real-time updates** - Live refresh without re-running command
2. **Vim keybindings** - j/k navigation, / search, : commands
3. **Context hierarchy** - Namespaces → Pods → Logs (drill down naturally)
4. **Resource views** - Switch between views instantly (`:pods`, `:services`)
5. **Color coding** - Red=failing, Green=healthy, Yellow=warning (instant visual scan)
6. **Actions** - d=describe, l=logs, e=edit (context-aware commands)

**Apply to miniforge:**

- Trains → PRs → Evidence (natural drill-down)
- `:trains`, `:prs`, `:repos`, `:dag` (view switching)
- Color coding for PR status (red=blocked, green=ready, yellow=pending)
- Context actions (a=approve, m=merge, r=review, e=evidence)

### Main View: Train Overview (with Agent Activity)

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ miniforge fleet  [Trains: 5 | PRs: 23 | Blocked: 3 | Ready: 7]   ⟳ 15s ago │
├─────────────────────────────────────────────────────────────────────────────┤
│ TRAIN                    STATUS      PRs  PROGRESS        BLOCKING  AGE     │
├─────────────────────────────────────────────────────────────────────────────┤
│ ● add-auth              in-progress  5/5  ██████▓▓▓▓ 60%  CI #123  2h      │
│ ● rds-import            ready        3/3  ██████████ 100% -        4h      │
│ ● k8s-migration         blocked      7/7  ███▓▓▓▓▓▓▓ 30%  Deps     1d      │
│   update-terraform      completed    4/4  ██████████ 100% -        2d      │
│   dependabot-batch      paused       12/12 ████▓▓▓▓▓▓ 40%  Review  6h      │
├─────────────────────────────────────────────────────────────────────────────┤
│ ACTIVE WORKFLOWS (2)                                        [Press 'w' view]│
├─────────────────────────────────────────────────────────────────────────────┤
│ ⟳ rds-import          Implementer   Generating Terraform import blocks...  │
│   └─ terraform-planner (subagent)  Running: terraform plan                 │
│ ⟳ add-auth            Tester        Running 42 unit tests... (18/42 ✓)     │
├─────────────────────────────────────────────────────────────────────────────┤
│ [Enter] Drill down  [w] Workflow detail  [a] Approve all  [:] Command      │
│ [/] Search  [t] Switch train  [r] Refresh  [q] Quit                        │
╰─────────────────────────────────────────────────────────────────────────────╯

# Status indicators:
# ● Green - active/ready
# ○ Gray - completed/paused
# ◉ Red - blocked/failed
# ◐ Yellow - warning/pending
# ⟳ - Agent actively processing
```

**Features:**

- **Real-time auto-refresh** (every 15s, configurable)
- **Progress bars** - Visual scan of train completion
- **Blocking column** - See what's stuck at a glance
- **Color coding** - Status colors match dots
- **One-key actions** - `a` to approve all ready PRs across all trains

### Drill-Down: PR Detail View

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ Train: add-auth (5 PRs)                                    [2/5 merged]     │
├─────────────────────────────────────────────────────────────────────────────┤
│ SEQ REPO              PR#  STATUS     CI    DEPS    APPROVALS  READY  AGE   │
├─────────────────────────────────────────────────────────────────────────────┤
│ [1] acme/infra        123  ✓ merged   ✓     -       2/2        -      2h    │
│ [2] acme/k8s          456  ✓ merged   ✓     #123    2/2        -      1h    │
│>[3] acme/backend      789  ◐ approved ⟳     #456    2/2        ⏳     30m   │
│ [4] acme/frontend     234  ○ review   -     #789    0/2        ✗      20m   │
│ [5] acme/docs         567  ○ draft    -     #234    0/0        ✗      10m   │
├─────────────────────────────────────────────────────────────────────────────┤
│ ╭─ PR #789: acme/backend - Add auth middleware ──────────────────────────╮ │
│ │ Status:    Approved (2/2)                                              │ │
│ │ CI:        Running (3/5 checks passed)                                 │ │
│ │ Depends:   #456 (merged ✓) - blocks #234, #567                        │ │
│ │ Policy:    ✓ No violations                                             │ │
│ │ Evidence:  Bundle #abc123 (view with 'e')                              │ │
│ │                                                                         │ │
│ │ ⟳ CI Checks:                                                           │ │
│ │   ✓ lint                    ✓ test-unit         ⟳ test-integration    │ │
│ │   ○ test-e2e (pending)      ○ security (pending)                      │ │
│ │                                                                         │ │
│ │ Ready to merge: NO (waiting for CI)                                    │ │
│ ╰─────────────────────────────────────────────────────────────────────────╯ │
├─────────────────────────────────────────────────────────────────────────────┤
│ [m] Merge next ready  [a] Approve  [r] Review  [e] Evidence  [c] Comments  │
│ [j/k] Navigate  [Enter] Expand  [Esc] Back to trains  [/] Search          │
╰─────────────────────────────────────────────────────────────────────────────╯

# Symbols:
# ✓ - Complete/passed
# ✗ - Failed/blocked
# ○ - Pending/waiting
# ◐ - In progress
# ⟳ - Running/syncing
# ⏳ - Ready but waiting for dependency
```

**Features:**

- **Sequence column** - Merge order clear at a glance
- **Dependency chain** - See what's blocking what
- **CI status inline** - No need to click through to GitHub
- **Expanded detail panel** - Context without leaving view
- **Action context** - Commands change based on selection

### Workflow Agent Activity View (NEW)

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ Workflow: rds-import (#abc123)                         [Phase: Implement]   │
├─────────────────────────────────────────────────────────────────────────────┤
│ ACTIVE AGENTS (3)                                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ ▸ Planner                                                                   │
│   └─ ✓ Completed (2m ago) - Generated 5-step plan                          │
│                                                                             │
│ ● Implementer                                                [2m 34s]       │
│   ├─ ⟳ Generating Terraform import blocks...                               │
│   │  Progress: [████████▓▓▓▓▓▓▓▓▓▓] 40%                                    │
│   ├─ 📖 Read: terraform/main.tf (6s ago)                                   │
│   ├─ 🧠 Analyzed import constraints (11s ago)                              │
│   └─ Subagent: terraform-planner                            [12s]          │
│       └─ ⟳ Running: terraform plan -out=plan.tfplan                        │
│                                                                             │
│ ○ Tester                                                     [waiting]      │
│   └─ Waiting for Implementer to complete                                   │
│                                                                             │
│ ○ Reviewer                                                   [waiting]      │
│   └─ Waiting for Tester to complete                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ RECENT ACTIVITY (last 5 minutes)                              [Toggle: 'l'] │
├─────────────────────────────────────────────────────────────────────────────┤
│ now      Implementer   ⟳ Generating Terraform import blocks...             │
│ -6s      Implementer   📖 Reading: terraform/main.tf                       │
│ -11s     Implementer   🧠 Analyzing import constraints                     │
│ -16s     Implementer   📖 Reading spec file: specs/rds-import.edn          │
│ -21s     Implementer   ▶ Started implementation phase                      │
│ -1m 36s  Planner       ✓ Plan generation complete                          │
│ -1m 41s  Planner       🧠 Evaluating risks and mitigation strategies       │
│ -1m 56s  Planner       📖 Reading: knowledge base (RDS patterns)           │
│ -2m 16s  Planner       ▶ Started planning phase                            │
│ -3m 02s  Workflow      ▶ Workflow execution started                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ LLM USAGE                                                                   │
│ ├─ Planner:      2 calls  │  4.2k tokens in  │  1.8k tokens out │  $0.02   │
│ └─ Implementer:  1 call   │  2.4k tokens in  │  850 tokens out  │  $0.01   │
├─────────────────────────────────────────────────────────────────────────────┤
│ [a] Toggle activity log  [d] Deep dive  [p] View PRs  [Esc] Back           │
╰─────────────────────────────────────────────────────────────────────────────╯

# Symbols:
# ▸ - Completed agent (collapsed)
# ● - Active agent (expanded)
# ○ - Waiting/pending agent
# ⟳ - Currently processing
# 📖 - Reading/loading
# 🧠 - Thinking/analyzing
# ✓ - Completed milestone
# ▶ - Started

Press 'Enter' on an agent to see detailed logs.
Press 'l' to toggle full activity log (scrollable).
Press 'w' to toggle between workflow view and PR view.
```

**Features:**

- **Real-time agent status** - See what each agent is doing right now
- **Subagent visibility** - See spawned subagents (terraform-planner, etc.)
- **Activity timeline** - Recent actions with timestamps
- **LLM transparency** - Token usage and costs per agent
- **Expandable details** - Press Enter on agent for full logs

### Evidence Bundle View

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ Evidence Bundle: abc123 (Train: add-auth)                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ INTENT                                                                      │
│ ├─ Type: IMPORT (import existing resources to Terraform)                   │
│ ├─ Constraints: no-creates, no-destroys, state-only                        │
│ └─ Rationale: "Bring existing RDS instances under Terraform management"    │
│                                                                             │
│ PLAN ARTIFACTS (3 repos)                                                    │
│ ├─ acme/infra/terraform-plan.txt                    [view: p]              │
│ ├─ acme/k8s/kustomize-diff.txt                      [view: k]              │
│ └─ acme/backend/migration-plan.sql                  [view: s]              │
│                                                                             │
│ POLICY VALIDATION                                                           │
│ ├─ ✓ terraform-plan-review     (0 violations)                              │
│ ├─ ✓ k8s-manifest-safety        (0 violations)                              │
│ ├─ ✓ semantic-intent-check      (0 violations)                              │
│ └─ ✓ no-secrets-in-code         (0 violations)                              │
│                                                                             │
│ GATE RESULTS                                                                │
│ ├─ ✓ syntax      (all passed)                                              │
│ ├─ ✓ lint        (all passed)                                              │
│ ├─ ✓ test        (97% coverage, threshold: 80%)                            │
│ └─ ✓ security    (0 critical, 2 info)                                      │
│                                                                             │
│ SCAN FINDINGS (2 info-level)                                                │
│ ├─ ℹ acme/infra: Terraform state file size +15% (acceptable)               │
│ └─ ℹ acme/k8s: New service account created (expected)                      │
│                                                                             │
│ APPROVAL CHAIN                                                              │
│ ├─ ✓ @alice (senior-engineer)        2h ago                                │
│ ├─ ✓ @bob (platform-lead)            1h ago                                │
│ └─ ✓ Policy gates (automated)        1h ago                                │
├─────────────────────────────────────────────────────────────────────────────┤
│ [p/k/s] View artifact  [d] Download bundle  [Esc] Back                     │
╰─────────────────────────────────────────────────────────────────────────────╯
```

**Features:**

- **Full audit trail** in one view
- **Semantic intent** visible (IMPORT vs CREATE)
- **Policy violations** front and center
- **Keyboard navigation** to artifacts
- **Downloadable** for offline review

### Command Mode (`:` like vim)

```
:trains                 # List all trains
:trains status:blocked  # Filter blocked trains
:prs ready             # Show all ready-to-merge PRs
:prs repo:acme/infra   # Filter by repo
:merge-all-ready       # Batch merge all ready PRs
:approve marked        # Approve all marked PRs (Space to mark)
:dag                   # Show repo DAG visualization
:config                # Open config
:help                  # Show keybindings
```

**Features:**

- **Vim muscle memory** - `:` for commands
- **Filtering** - View subsets quickly
- **Batch operations** - Act on filtered results
- **Discoverable** - `:help` shows all commands

---

## Part 2: Web Dashboard (Linear-Inspired)

### Reference: Why Linear Works

**Linear strengths:**

1. **Information density** - See 20+ issues without scrolling
2. **Keyboard shortcuts** - Cmd+K to search, numbers to assign, etc.
3. **Grouped views** - By status, assignee, priority (collapsible)
4. **Inline actions** - Hover reveals actions, no modal needed
5. **Command palette** - Cmd+K for anything (search, create, assign)
6. **Smart defaults** - Most common action is most accessible

**Apply to miniforge:**

- Grouped by train (collapsible)
- Cmd+K command palette
- Inline PR actions on hover
- Keyboard shortcuts for everything

### Main View: Fleet Dashboard (with Agent Activity)

```
┌────────────────────────────────────────────────────────────────────────────┐
│  miniforge                                   [Cmd+K]  [@chris]  [Settings]  │
├────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │ 🤖 ACTIVE WORKFLOWS (2)                            [Live Updates ●]  │ │
│  ├──────────────────────────────────────────────────────────────────────┤ │
│  │ ⟳ rds-import  │ Implementer → Generating Terraform imports... (40%) │ │
│  │               │ └─ terraform-planner: Running terraform plan         │ │
│  │ ⟳ add-auth    │ Tester → Running 42 unit tests... (18/42 ✓)         │ │
│  │               │ [Click to view details]                              │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  Filters: [All Trains ▾] [All Repos ▾] [Status: All ▾]      Search: [  ] │
│                                                                            │
│  Quick Actions:  [↻ Refresh]  [✓ Approve Selected]  [→ Merge Ready]      │
│                                                                            │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                            │
│  ▼ 🔴 BLOCKED (3 trains, 12 PRs)                                          │
│     └─ k8s-migration (7 PRs) - Blocked by dependency conflict             │
│        │                                                                   │
│        ├─ [#123] acme/infra  - Add VPC  [CI ✓] [2/2 ✓] [Deps: -]        │
│        │  └─ Blocks: #124, #125, #126                                     │
│        │                                                                   │
│        ├─ [#124] acme/k8s    - Add namespace  [CI ⟳] [1/2 ✓] [Deps: #123]│
│        │  ⚠ Waiting for #123 to merge                                     │
│        │                                                                   │
│        └─ ... 5 more  [Show all]                                          │
│                                                                            │
│  ▼ 🟢 READY TO MERGE (7 PRs across 2 trains)          [Merge All Ready]  │
│     ├─ rds-import (3 PRs) - All approved, CI passed                       │
│     │  │                                                                   │
│     │  ├─ [#234] acme/infra  - Import RDS  [CI ✓] [2/2 ✓] [Ready →]     │
│     │  ├─ [#235] acme/k8s    - Update secrets  [CI ✓] [2/2 ✓] [Ready →] │
│     │  └─ [#236] acme/backend - Use RDS  [CI ✓] [2/2 ✓] [Ready →]       │
│     │                                                                      │
│     └─ add-auth (4 PRs)                                                    │
│        ├─ [#345] acme/infra  - Cognito  [CI ✓] [2/2 ✓] [Ready →]        │
│        └─ ... 3 more  [Show all]                                          │
│                                                                            │
│  ▽ 🟡 IN PROGRESS (2 trains, 8 PRs)                                       │
│     └─ ... [Expand]                                                        │
│                                                                            │
│  ▽ ⚪ COMPLETED (23 trains, 142 PRs)                      [Last 7 days ▾] │
│     └─ ... [Expand]                                                        │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘

Keyboard shortcuts:
- Space      : Select/deselect PR
- Cmd+A      : Select all visible
- Cmd+K      : Command palette
- →          : Merge selected
- A          : Approve selected
- R          : Refresh
- 1-9        : Jump to train
- /          : Focus search
- Esc        : Clear selection
```

**Features:**

- **Grouped by urgency** - Blocked first, ready to merge second
- **Collapsible sections** - Reduce noise, expand when needed
- **Inline actions** - Hover over PR shows [→ Merge] [✓ Approve] [👁 Review]
- **Batch selection** - Space to select, Cmd+A for all, then batch action
- **Status badges** - CI, approvals, deps all visible inline
- **Dependency indicators** - See what's blocking/blocked

### PR Detail Modal (Cmd+Click or Enter)

```
┌────────────────────────────────────────────────────────────────────────────┐
│  PR #234: acme/infra - Import RDS instance to Terraform          [✕ Close] │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  ┌──────────────────────┬────────────────────────────────────────────────┐ │
│  │ STATUS               │ DEPENDENCIES                                   │ │
│  ├──────────────────────┼────────────────────────────────────────────────┤ │
│  │ 🟢 Ready to Merge    │ Depends on: -                                  │ │
│  │                      │ Blocks: #235, #236                             │ │
│  ├──────────────────────┼────────────────────────────────────────────────┤ │
│  │ CI STATUS            │ APPROVALS                                      │ │
│  ├──────────────────────┼────────────────────────────────────────────────┤ │
│  │ ✓ lint               │ ✓ @alice (senior-engineer)       2h ago       │ │
│  │ ✓ test-unit          │ ✓ @bob (platform-lead)           1h ago       │ │
│  │ ✓ test-integration   │ Required: 2/2                                  │ │
│  │ ✓ security           │                                                │ │
│  │ ✓ terraform-plan     │                                                │ │
│  └──────────────────────┴────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ POLICY VALIDATION                                                      │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │ ✓ terraform-plan-review      0 violations                             │ │
│  │ ✓ semantic-intent-check      IMPORT validated (no creates/destroys)   │ │
│  │ ✓ no-secrets                 0 violations                             │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ EVIDENCE BUNDLE  [View Full Bundle →]                                 │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │ Intent: IMPORT existing RDS instance (no infrastructure changes)      │ │
│  │                                                                        │ │
│  │ Artifacts:                                                             │ │
│  │ • terraform-plan.txt (23 lines) - [View]                               │ │
│  │ • import-checklist.md (verified) - [View]                              │ │
│  │                                                                        │ │
│  │ Semantic Checks:                                                       │ │
│  │ ✓ No resource creation (state-only)                                   │ │
│  │ ✓ No resource destruction                                             │ │
│  │ ✓ Import target exists in AWS                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ CHANGES  [Files: 3] [+42 -0]                                          │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │ ▸ terraform/rds.tf                              +35   [Expand diff]   │ │
│  │ ▸ terraform/terraform.tfstate (state only)      +5                    │ │
│  │ ▸ terraform/imports.tf                          +2                    │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ TIMELINE                                                               │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │ 2h ago   Created by @chris                                             │ │
│  │ 2h ago   CI checks started                                             │ │
│  │ 1.5h ago Policy validation passed                                      │ │
│  │ 1h ago   Approved by @alice                                            │ │
│  │ 1h ago   Approved by @bob                                              │ │
│  │ 30m ago  All CI checks passed                                          │ │
│  │ now      🟢 Ready to merge                                             │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ [→ Merge Now]  [✓ Approve]  [💬 Comment]  [🔗 View on GitHub]        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

**Features:**

- **All decision info visible** - No scrolling, no clicking
- **Collapsible diffs** - Expand if needed, collapsed by default
- **Evidence inline** - Intent + semantic checks front and center
- **Timeline** - See progression at a glance
- **One-click actions** - Merge/approve without leaving modal

### Workflow Agent Activity Modal (NEW)

**Triggered by:** Clicking "Active Workflows" or workflow name

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Workflow: rds-import (#abc123)                     [Phase: Implement] [✕] │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  Agent Activity                                            [Live Updates] │
│                                                                            │
│  ● Implementer                                              2m 34s        │
│    ┌──────────────────────────────────────────────────────────────────┐   │
│    │ ⟳ Generating Terraform import blocks...                         │   │
│    │                                                                  │   │
│    │ Progress: [████████▓▓▓▓▓▓▓▓▓▓] 40%                              │   │
│    │                                                                  │   │
│    │ Recent actions:                                                 │   │
│    │ • 6s ago   📖 Reading terraform/main.tf                         │   │
│    │ • 11s ago  🧠 Analyzing import constraints                      │   │
│    │ • 16s ago  📖 Reading spec file                                 │   │
│    │                                                                  │   │
│    │ ↳ Subagent: terraform-planner (12s)                             │   │
│    │   ⟳ Running: terraform plan -out=plan.tfplan                    │   │
│    │   Output:                                                        │   │
│    │   > Acquiring state lock...                                     │   │
│    │   > Refreshing Terraform state in-memory prior to plan...       │   │
│    └──────────────────────────────────────────────────────────────────┘   │
│                                                                            │
│  ✓ Planner                                                 Completed       │
│    └─ Generated 5-step implementation plan (2m ago)                        │
│       [View plan artifacts]                                                │
│                                                                            │
│  ○ Tester                                                  Waiting         │
│    └─ Queued - waiting for Implementer                                    │
│                                                                            │
│  ○ Reviewer                                                Waiting         │
│    └─ Queued - waiting for Tester                                         │
│                                                                            │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                            │
│  Activity Timeline                                          [Show All →]  │
│  ────────────────────────────────────────────────────────────────────     │
│                                                                            │
│  Now     ● Implementer: Generating Terraform import blocks...             │
│  -6s     📖 Implementer: Reading terraform/main.tf                        │
│  -11s    🧠 Implementer: Analyzing import constraints                     │
│  -16s    📖 Implementer: Reading spec file                                │
│  -21s    ▶ Implementer: Started implementation phase                      │
│  -1m 36s ✓ Planner: Plan generation complete                              │
│  -1m 41s 🧠 Planner: Evaluating risks                                     │
│  -1m 56s 📖 Planner: Reading knowledge base                               │
│  -2m 16s ▶ Planner: Started planning phase                                │
│                                                                            │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                            │
│  LLM Usage                                                                 │
│                                                                            │
│  Planner:      2 calls  │  4.2k tokens in  │  1.8k tokens out  │  $0.02   │
│  Implementer:  1 call   │  2.4k tokens in  │  850 tokens out   │  $0.01   │
│                                                                            │
│  [View Full Log]  [Pause Updates]  [View PRs →]                           │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

**Features:**

- **Real-time streaming** - Updates every second via SSE/WebSocket
- **Expandable agents** - Click to see detailed activity
- **Subagent visibility** - See spawned subagents with their output
- **Activity timeline** - Scrollable log of all actions
- **LLM transparency** - Token usage and costs
- **Live progress bars** - Visual feedback on long-running operations

### Command Palette (Cmd+K)

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Search or run a command...                                                │
│  ────────────────────────────────────────────────────────────────────────  │
│  > merge                                                                   │
│                                                                            │
│  ACTIONS                                                                   │
│  → Merge all ready PRs                                    Cmd+Shift+M     │
│  → Merge selected PRs                                     →               │
│  → Merge train: rds-import                                                │
│                                                                            │
│  NAVIGATION                                                                │
│  🚂 Go to train: add-auth                                                 │
│  🚂 Go to train: k8s-migration                                            │
│  📦 Go to repo: acme/infra                                                │
│  🤖 View workflow: rds-import (agents active)                             │
│                                                                            │
│  SEARCH                                                                    │
│  🔍 PR #234: Import RDS                                                   │
│  🔍 PR #235: Update secrets                                               │
│                                                                            │
│  [Esc] Cancel                                                              │
└────────────────────────────────────────────────────────────────────────────┘
```

**Features:**

- **Fuzzy search** - Type "mer re" → finds "Merge ready PRs"
- **Keyboard shortcuts** - Common actions have shortcuts
- **Contextual** - Shows actions relevant to current view
- **Fast** - No page loads, instant command execution

---

## Part 3: High-Throughput Workflow Design

### Scenario: Burning Through 100 PRs

**Goal:** Triage 100 PRs in 60 minutes (36 seconds per PR)

**Workflow:**

#### Step 1: Filter & Batch (5 min)

```
1. Open fleet dashboard
2. Filter to "Ready to Merge"
3. Scan for groups:
   - Dependabot (security updates)
   - Infrastructure (Terraform)
   - Application (code changes)
4. Select all Dependabot PRs (20 PRs)
5. Cmd+Shift+M to merge batch
```

**Result:** 20 PRs merged in 5 minutes

#### Step 2: Quick Scan for Blockers (10 min)

```
1. Filter to "Blocked"
2. Scan blocking reasons:
   - CI failures → assign to owner
   - Dependency issues → investigate
   - Policy violations → fix or escalate
3. For each blocked PR:
   - Read error message (5s)
   - Decide: fix, assign, or escalate (5s)
   - Execute action (5s)
```

**Result:** 40 blocked PRs triaged in 10 minutes

#### Step 3: Review Ready PRs (30 min)

```
1. Filter to "Approved, CI Passed"
2. For each PR:
   a. Scan evidence bundle (10s)
      - Intent matches? ✓
      - Policy violations? None ✓
      - CI all green? ✓
   b. Glance at diff summary (5s)
      - Lines changed reasonable? ✓
   c. Check dependencies (3s)
      - Will merge in correct order? ✓
   d. Press → to merge (2s)
```

**Result:** 30 PRs merged in 30 minutes

#### Step 4: Review & Approve Pending (15 min)

```
1. Filter to "Needs Approval"
2. For each PR:
   a. Check policy gates (5s)
   b. Review intent (5s)
   c. Approve with 'A' (2s)
```

**Result:** 10 PRs approved in 15 minutes

**Total:** 100 PRs processed in 60 minutes

### Key Optimizations

#### 1. Smart Grouping

- Group by similarity (Dependabot, linting, security, features)
- Batch identical changes
- Defer complex reviews to end

#### 2. Progressive Detail

- **Scan view:** Status, repo, title, blockers (1-2 seconds)
- **Expanded row:** CI, approvals, dependencies (5-10 seconds)
- **Full modal:** Diffs, evidence, comments (20-30 seconds)

#### 3. Visual Scanning

- **Colors:** Red=blocked, Yellow=attention, Green=ready
- **Icons:** ✓=done, ⟳=running, ✗=failed, ⚠=warning
- **Progress bars:** Train completion at a glance

#### 4. Keyboard Shortcuts

```
# Navigation
j/k or ↓/↑    - Move selection
Space         - Select/deselect
Cmd+A         - Select all visible
Enter         - Expand detail
Esc           - Collapse/back
/             - Focus search

# Actions
→ or m        - Merge
A             - Approve
R             - Review (open full evidence)
C             - Comment
D             - Diff view

# Batch
Cmd+Shift+M   - Merge all selected
Cmd+Shift+A   - Approve all selected

# Views
1-9           - Jump to train N
:             - Command mode (vim-style)
Cmd+K         - Command palette
T             - Switch train
P             - Switch to PR view
G             - Go to (train/pr/repo)
```

---

## Part 4: Implementation Specs

### CLI TUI Stack

**Library:** Bubble Tea (Go) or Ink (React for terminals)

**Recommendation:** **Bubble Tea** (Go)

- Mature, well-documented
- Fast rendering
- Vim-like keybinding support
- Real-time updates easy

**Alternative:** Build in Clojure with JLine3 + Lanterna

- Pros: Same language, integrates with components
- Cons: Less polished TUI libraries

**Structure:**

```
bases/cli-tui/
├── src/
│   ├── main.go (or main.clj)
│   ├── views/
│   │   ├── train_list.go
│   │   ├── pr_detail.go
│   │   ├── evidence.go
│   │   └── dag.go
│   ├── components/
│   │   ├── table.go
│   │   ├── detail_panel.go
│   │   └── progress_bar.go
│   └── state/
│       └── manager.go
└── test/
```

**Data Flow:**

```
1. TUI connects to API via HTTP + SSE stream for real-time updates
2. HTTP GET: Initial state {trains, prs, dag, workflows, ...}
3. SSE stream: Real-time events (agent status, PR updates, CI changes)
4. TUI renders current view with live agent activity
5. User action → API call → state update → re-render
6. Agent events auto-update via SSE (no polling needed for status)
```

**Streaming Implementation (TUI):**

```go
// Example using Bubble Tea + SSE

func (m model) Init() tea.Cmd {
    return tea.Batch(
        loadInitialState(),
        subscribeToSSE(),  // NEW: Subscribe to real-time events
    )
}

func subscribeToSSE() tea.Cmd {
    return func() tea.Msg {
        resp, _ := http.Get("/api/fleet/stream")
        scanner := bufio.NewScanner(resp.Body)

        for scanner.Scan() {
            line := scanner.Text()
            if strings.HasPrefix(line, "data: ") {
                event := parseSSEEvent(line)
                return agentStatusMsg{event}
            }
        }
    }
}

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
    switch msg := msg.(type) {
    case agentStatusMsg:
        // Update agent activity in real-time
        m.updateAgentStatus(msg.event)
        return m, subscribeToSSE()  // Continue listening
    }
}
```

### Web Dashboard Stack

**Framework:** ClojureScript + Re-frame

**Libraries:**

- **Re-frame** - State management
- **Reagent** - React wrapper
- **Garden** - CSS in Clojure
- **Day8/re-frame-10x** - Dev tools

**Alternative:** Remix (TypeScript)

- Pros: Better ecosystem for web components
- Cons: Not Clojure

**Structure:**

```
bases/web-dashboard/
├── src/
│   ├── miniforge/
│   │   ├── core.cljs          # Entry point
│   │   ├── events.cljs        # Re-frame events
│   │   ├── subs.cljs          # Re-frame subscriptions
│   │   ├── views/
│   │   │   ├── fleet.cljs     # Main fleet view
│   │   │   ├── train.cljs     # Train detail
│   │   │   ├── pr_modal.cljs  # PR detail modal
│   │   │   └── command.cljs   # Command palette
│   │   ├── components/
│   │   │   ├── table.cljs
│   │   │   ├── status.cljs
│   │   │   └── badge.cljs
│   │   └── api.cljs           # API client
└── resources/public/
    └── css/
        └── styles.css
```

**Data Flow:**

```
1. Initial load: HTTP GET for trains, PRs, workflows
2. SSE/WebSocket connection for real-time updates
3. Real-time events pushed to client (agent status, PR updates, CI changes)
4. Re-frame stores state in app-db
5. Views subscribe to relevant state (including live agent activity)
6. User action → dispatch event → API call → state update
```

**Streaming Implementation (Web):**

```javascript
// Example using EventSource (SSE)

// Initialize SSE connection
const eventSource = new EventSource('/api/fleet/stream');

// Handle agent status events
eventSource.addEventListener('agent-status', (event) => {
  const status = JSON.parse(event.data);

  // Dispatch to Re-frame
  dispatch(['agent/status-updated', status]);

  // Update UI shows:
  // "● Implementer: Generating Terraform import blocks... (40%)"
});

eventSource.addEventListener('subagent-spawned', (event) => {
  const spawn = JSON.parse(event.data);

  dispatch(['agent/subagent-spawned', spawn]);

  // UI shows nested subagent:
  // "↳ terraform-planner: Running terraform plan..."
});

eventSource.addEventListener('milestone-reached', (event) => {
  const milestone = JSON.parse(event.data);

  dispatch(['agent/milestone', milestone]);

  // UI highlights milestone:
  // "✓ Planner: Plan generation complete"
});

// Handle connection errors
eventSource.onerror = () => {
  console.error('SSE connection lost, reconnecting...');
  setTimeout(() => {
    // Reconnect logic
  }, 3000);
};
```

**Re-frame Events (for agent status):**

```clojure
;; bases/web-dashboard/src/miniforge/events.cljs

(rf/reg-event-db
 ::agent-status-updated
 (fn [db [_ status]]
   (assoc-in db [:agent-activity (:workflow-id status) (:agent-id status)]
             {:status (:status-type status)
              :message (:message status)
              :detail (:detail status)
              :timestamp (:timestamp status)})))

(rf/reg-event-db
 ::subagent-spawned
 (fn [db [_ spawn]]
   (update-in db [:agent-activity (:workflow-id spawn) (:parent-agent-id spawn) :subagents]
              conj {:id (:subagent-id spawn)
                    :purpose (:purpose spawn)
                    :spawned-at (:timestamp spawn)})))

(rf/reg-event-db
 ::agent-milestone
 (fn [db [_ milestone]]
   (update-in db [:agent-activity (:workflow-id milestone) (:agent-id milestone) :milestones]
              conj {:milestone (:milestone milestone)
                    :reached-at (:timestamp milestone)})))
```

**Re-frame Subscriptions:**

```clojure
;; bases/web-dashboard/src/miniforge/subs.cljs

(rf/reg-sub
 ::active-workflows
 (fn [db _]
   (->> (:agent-activity db)
        (filter (fn [[wf-id agents]]
                  (some #(not= (:status %) :completed) (vals agents))))
        (into {}))))

(rf/reg-sub
 ::workflow-agent-activity
 (fn [db [_ workflow-id]]
   (get-in db [:agent-activity workflow-id])))

(rf/reg-sub
 ::agent-status-message
 (fn [db [_ workflow-id agent-id]]
   (get-in db [:agent-activity workflow-id agent-id :message])))
```

### API Requirements (for both UIs)

**REST Endpoints:**

```clojure
;; List operations
GET  /api/trains              # List all trains
GET  /api/trains/:id          # Get train detail
GET  /api/prs                 # List all PRs
GET  /api/prs/:id             # Get PR detail
GET  /api/repos               # List repos in DAG
GET  /api/dag/:id             # Get DAG

;; Actions
POST /api/prs/:id/approve     # Approve PR
POST /api/prs/:id/merge       # Merge PR
POST /api/prs/:id/comment     # Add comment
POST /api/trains/:id/pause    # Pause train
POST /api/trains/:id/resume   # Resume train

;; Batch operations
POST /api/prs/batch/approve   # Batch approve
POST /api/prs/batch/merge     # Batch merge

;; Filters & search
GET  /api/prs?status=ready    # Filter by status
GET  /api/prs?repo=acme/infra # Filter by repo
GET  /api/search?q=rds        # Search PRs
```

**WebSocket/SSE Events:**

```clojure
;; Subscribe to updates
{:type :subscribe
 :channels [:train/:id :fleet :prs :workflows]}

;; Server pushes updates - Train/PR events
{:type :train-updated
 :train-id uuid
 :data {...}}

{:type :pr-status-changed
 :pr-number 123
 :status :merged}

{:type :ci-status-changed
 :pr-number 123
 :check :test-unit
 :status :passed}

;; NEW: Agent status events (see AGENT_STATUS_STREAMING.md for full protocol)
{:type :agent-status
 :workflow-id uuid
 :agent-id :implementer
 :status-type :generating
 :message "Generating Terraform import blocks..."
 :detail {...}}

{:type :subagent-spawned
 :workflow-id uuid
 :parent-agent-id :implementer
 :subagent-id :terraform-planner
 :message "Spawned terraform-planner subagent"}

{:type :agent-tool-use
 :workflow-id uuid
 :agent-id :implementer
 :tool-name :read-file
 :message "Reading file: terraform/main.tf"}

{:type :llm-request
 :workflow-id uuid
 :agent-id :planner
 :model "claude-sonnet-4"
 :tokens 2400
 :message "Calling Claude Sonnet (2.4k tokens)..."}

{:type :milestone-reached
 :workflow-id uuid
 :agent-id :planner
 :milestone :plan-complete
 :message "Plan generation complete"}
```

**Streaming Endpoints:**

```clojure
;; Server-Sent Events for real-time agent status
GET /api/workflows/:id/stream    # Stream status events for workflow
GET /api/fleet/stream            # Stream all fleet events

;; WebSocket alternative
WS /api/ws                       # Bidirectional WebSocket for all events
```

---

## Part 5: Visual Design Language

### Color Palette (Accessible)

```css
/* Status colors */
--status-blocked:  #EF4444;  /* Red */
--status-ready:    #10B981;  /* Green */
--status-progress: #F59E0B;  /* Amber */
--status-pending:  #6B7280;  /* Gray */
--status-merged:   #3B82F6;  /* Blue */

/* Backgrounds (dark mode first) */
--bg-primary:      #111827;  /* Dark gray */
--bg-secondary:    #1F2937;
--bg-tertiary:     #374151;

/* Text */
--text-primary:    #F9FAFB;
--text-secondary:  #D1D5DB;
--text-tertiary:   #9CA3AF;

/* Accents */
--accent-blue:     #3B82F6;
--accent-purple:   #8B5CF6;
```

### Typography

```css
/* Interface font (monospace for CLI feel) */
font-family: 'JetBrains Mono', 'Fira Code', 'Monaco', monospace;

/* Sizes */
--text-xs:   0.75rem;  /* Status badges */
--text-sm:   0.875rem; /* Table cells */
--text-base: 1rem;     /* Body text */
--text-lg:   1.125rem; /* Section headers */
--text-xl:   1.25rem;  /* Page titles */
```

### Component Library

**Badge:**

```
[CI ✓]  [2/2 ✓]  [Deps: #123]  [Ready →]
```

**Progress Bar:**

```
██████▓▓▓▓ 60%
```

**Status Dot:**

```
● Green - active/ready
○ Gray - inactive/completed
◉ Red - blocked/error
◐ Yellow - warning/pending
```

---

## Part 6: Next Steps

### Week 1: CLI TUI Prototype

**Goal:** Working train list + PR detail views

**Tasks:**

1. Choose stack (Bubble Tea vs Clojure/Lanterna)
2. Build train list view
3. Add PR drill-down
4. Implement keyboard navigation
5. Add auto-refresh

**Exit Criteria:**

- Can navigate trains/PRs with keyboard
- Real-time updates work
- Readable on 80x24 terminal

### Week 2: Web Dashboard Prototype

**Goal:** Working fleet view + PR modal

**Tasks:**

1. Re-frame setup
2. Fleet list view with grouping
3. PR detail modal
4. Command palette (Cmd+K)
5. WebSocket connection

**Exit Criteria:**

- Can view all trains/PRs
- Can approve/merge from UI
- Keyboard shortcuts work
- Real-time updates via WebSocket

### Week 3: Polish & Optimize

**Goal:** Production-ready UIs

**Tasks:**

1. Color coding + visual polish
2. Loading states + error handling
3. Batch operations
4. Filter/search
5. User testing with 100+ PRs

**Exit Criteria:**

- Can triage 100 PRs in <60 min
- No performance issues
- Keyboard shortcuts muscle memory
- Clear visual hierarchy

---

## Part 7: Agent Status Streaming Benefits

### Transparency & Trust

**Problem:** Users don't trust systems they can't see working

**Solution:** Real-time agent status creates transparency

- See agents reading files, thinking, generating code
- Watch subagents spawn for specialized tasks
- View LLM calls with token counts
- Trust builds through visibility

**Example:**

```
Instead of seeing:
  "Workflow running... (5 minutes)"

User sees:
  "● Implementer: Generating Terraform import blocks... (2m 34s)
   ├─ Read terraform/main.tf
   ├─ Analyzed import constraints
   └─ terraform-planner: Running terraform plan..."
```

### Debugging & Problem Resolution

**Problem:** When workflows fail, users have no idea where/why

**Solution:** Activity timeline shows exact failure point

- See last action before failure
- Identify stuck agents (reading same file repeatedly)
- Notice infinite loops in subagent spawning
- Trace back through activity log

**Example:**

```
Workflow failed at 3m 12s:
  3m 12s  ✗ Implementer: Policy validation failed
  3m 10s  ⟳ Implementer: Validating against terraform-aws policy
  3m 05s  ⟳ Implementer: Generated code (35 lines)
  2m 50s  🧠 Implementer: Analyzing constraints
  → Problem: Code generation didn't respect IMPORT constraint
```

### Performance Monitoring

**Problem:** Don't know which agents are slow or bottlenecks

**Solution:** See duration of every operation

- Identify slow LLM calls
- Notice agents spending too long reading/validating
- Optimize by fixing bottlenecks
- Compare agent performance across workflows

**Example:**

```
LLM Usage:
  Planner:      2 calls  │  4.2k tokens  │  6.8s total  │  $0.02
  Implementer:  1 call   │  2.4k tokens  │  3.2s total  │  $0.01
  Tester:       3 calls  │  8.1k tokens  │  18.5s total │  $0.05  ← SLOW!
  → Optimization target: Reduce Tester token usage
```

### User Experience Parity with LLM Tools

**Expectation:** Users are trained by Claude Code, Cursor, etc.

- Expect to see "Thinking..."
- Expect to see "Reading file X..."
- Expect real-time feedback

**Delivery:** miniforge matches UX expectations

- Same streaming status patterns
- Same transparency
- Feels familiar to LLM tool users

### Confidence During Long Operations

**Problem:** Workflows can run for 5-30 minutes

- User worried: "Is it stuck?"
- User impatient: "What's taking so long?"
- User anxious: "Did it crash?"

**Solution:** Constant feedback eliminates anxiety

- See progress bars (40% complete)
- See recent activity updates (6s ago: Reading X)
- See subagents working (terraform-planner running)
- Know it's working, not stuck

### Implementation Complexity

**Cost:** Moderate effort to add streaming

- Agent interface: Add `emit-status` calls (~50 LOC per agent)
- Event bus: Pub/sub for status events (~200 LOC)
- SSE endpoint: Stream events to clients (~150 LOC)
- UI components: Display agent activity (~300 LOC TUI, ~400 LOC Web)

**Total:** ~1,100 LOC for full streaming status

**Benefit:** Massive improvement in UX, trust, debuggability

**Decision:** Worth the investment - matches user expectations from LLM tools

---

## Summary: Why This Will Work at Scale

### CLI TUI

- **K9s-proven** - Kubernetes ops teams manage 1000s of pods this way
- **Vim-efficient** - Keyboard > mouse for high throughput
- **Low latency** - No browser, direct API calls
- **Works remotely** - SSH + tmux friendly
- **Real-time agent visibility** - See autonomous work happening

### Web Dashboard

- **Linear-proven** - Teams manage 100s of issues daily
- **Information dense** - See 20+ PRs without scrolling
- **Batch-friendly** - Select + merge 50 PRs in one action
- **Collaborative** - Share links, real-time updates for team
- **Live agent streaming** - Watch workflows execute in real-time

### Agent Status Streaming

- **Transparency** - Users see agents working, builds trust
- **Debugging** - Activity log shows exact failure points
- **Performance** - Identify bottlenecks and optimization targets
- **UX parity** - Matches expectations from Claude Code, Cursor, etc.
- **Confidence** - No anxiety during long operations

### Together

- **CLI for ops** - DevOps running trains from terminal with live agent feedback
- **Web for managers** - Leadership monitoring fleet + agent activity
- **Same API** - Consistent data model, real-time sync
- **Complementary** - Use both depending on context
- **Full transparency** - Know exactly what autonomous system is doing

**The key:** Progressive disclosure + keyboard shortcuts + batch operations + real-time agent visibility = 100+ PRs/day throughput with full confidence

---

## Dependencies & References

- **Agent Status Protocol:** See [AGENT_STATUS_STREAMING.md](./AGENT_STATUS_STREAMING.md) for complete event schema and implementation details
- **Agent Interface Changes:** Agents must emit status events via `emit-status` function
- **Event Bus:** Required for pub/sub of status events to UI subscribers
- **SSE/WebSocket:** Server-Sent Events or WebSocket for streaming to browser

Ready to start with the CLI TUI or Web prototype with agent streaming?
