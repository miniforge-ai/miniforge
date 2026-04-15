<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# miniforge.ai: AI-Powered PR Management Features

**Date:** 2026-01-22
**Extension of:** UX_DESIGN_SPEC.md
**Focus:** AI summaries, chat, and batch AI-powered gate analysis

---

## Design Philosophy

### The Problem with Current AI Code Review

- **Too slow** - Waiting 30s for AI review on each PR kills throughput
- **Too verbose** - 500-word summary when you need 2 sentences
- **No batch operations** - Can't ask "which of these 20 PRs is risky?"
- **No context** - Doesn't know about dependencies, train status, or policy
- **No transparency** - Can't see AI working, just wait for results

### miniforge's Approach

- **Pre-computed summaries** - AI review runs in background, cached
- **Progressive detail** - One-line summary → full analysis on demand
- **Batch-aware** - Select 10 PRs → "summarize all" or "find dangerous changes"
- **Context-aware** - AI knows train dependencies, intent, policy violations
- **Real-time streaming** - See AI agents working in real-time (see
  [AGENT_STATUS_STREAMING.md](../deprecated/AGENT_STATUS_STREAMING.md))

---

## Part 1: AI PR Summaries (Cached & Instant)

### Trigger Points for AI Review

**Automatic (background):**

1. **On PR creation** - Generate summary within 60s
2. **On new commits** - Update summary incrementally
3. **On CI completion** - Incorporate test results
4. **On policy scan** - Highlight violations in summary

**Manual:**

1. **Refresh summary** - Re-run with latest context
2. **Deep dive** - Full AI analysis with reasoning
3. **Comparative** - "Compare this PR to previous similar changes"

### Summary Levels

#### Level 1: One-Line Summary (Always Visible)

```text
🤖 AI: Imports existing RDS, no infrastructure changes, low risk ✓
```

**Generated from:**

- Intent type (IMPORT)
- Policy scan results (0 violations)
- Diff analysis (adds only, no deletes)
- Risk heuristic (low/medium/high)

**Prompt:**

```text
Summarize this PR in one sentence (max 80 chars):
- Intent: {intent-type}
- Changes: {+lines} additions, {-lines} deletions
- Policy: {violations-count} violations
- CI: {ci-status}
Focus on risk and action needed.
```

#### Level 2: Executive Summary (On Hover/Expand)

```text
🤖 AI Summary:

Risk: LOW
Type: Infrastructure - State Import

This PR imports an existing RDS instance into Terraform state without
making any infrastructure changes. The Terraform plan shows only state
additions (import blocks). No resources will be created, modified, or
destroyed.

Policy Compliance: ✓ All gates passed
Semantic Check: ✓ Intent matches changes (IMPORT = state-only)
Dependencies: None - can merge independently

Recommendation: Safe to merge after approval.
```

**Prompt:**

```text
Generate a 4-paragraph executive summary for this PR:

Context:
- Train: {train-name}
- Intent: {intent-description}
- Repo: {repo-name} ({repo-type})

Technical Details:
- Files changed: {file-list}
- Diff summary: {diff-stats}
- Terraform plan: {plan-summary}
- Policy violations: {violations}
- CI status: {ci-results}

Answer these questions:
1. What is the risk level? (LOW/MEDIUM/HIGH)
2. What type of change is this? (feature/bugfix/refactor/infra/etc)
3. Does the implementation match the stated intent?
4. Are there any concerns or blockers?
5. Is it safe to merge?

Format as 4 short paragraphs (2-3 sentences each).
```

#### Level 3: Deep Analysis (On Demand)

```text
🤖 Deep Analysis:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CHANGE SUMMARY
──────────────
Files Modified: 3
  • terraform/rds.tf          (+35 lines)
  • terraform/imports.tf      (+2 lines)
  • terraform/terraform.tfstate (+5 lines, state only)

Total: +42 lines, -0 lines

RISK ASSESSMENT
───────────────
Overall Risk: LOW

Rationale:
• No resource creation/deletion (state-only import)
• No changes to existing infrastructure
• Import target verified to exist in AWS
• Rollback is trivial (remove from state)

Potential Issues: None identified

SEMANTIC VALIDATION
────────────────────
Intent: IMPORT existing RDS instance
Actual Changes: ✓ Matches intent

Checks:
✓ No aws_db_instance resources created
✓ Only import blocks added
✓ State file changes are append-only
✓ No data-altering operations

POLICY COMPLIANCE
──────────────────
✓ terraform-plan-review      (0 violations)
✓ semantic-intent-check      (IMPORT validated)
✓ no-secrets                 (0 violations)

DEPENDENCIES & IMPACT
──────────────────────
Upstream:  None (independent)
Downstream: #235 (k8s secrets), #236 (backend config)

Impact Radius: Low
• Downstream PRs depend on this state existing
• No service disruption
• No database downtime

RECOMMENDED ACTIONS
────────────────────
1. ✓ Approve - No concerns identified
2. → Merge - Safe to merge immediately
3. Monitor: Verify import succeeded post-merge

SIMILAR CHANGES
────────────────
This PR is similar to:
• #210 - RDS import (staging) - merged 2w ago, no issues
• #189 - ElastiCache import - merged 1m ago, no issues

Pattern: Infrastructure imports are typically low-risk when state-only.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Prompt:**

```text
Perform a deep technical analysis of this PR.

Full Context:
{evidence-bundle-json}

Include:
1. Change Summary - What files, what changed
2. Risk Assessment - Overall risk + rationale + potential issues
3. Semantic Validation - Does implementation match intent?
4. Policy Compliance - Gate results
5. Dependencies & Impact - What depends on this, blast radius
6. Recommended Actions - What should reviewer do?
7. Similar Changes - Has this pattern been done before?

Use structured markdown with clear sections.
Be concise but comprehensive.
Highlight risks and concerns prominently.
```

---

## Part 2: Contextual AI Chat

### Chat Interface (Web)

```text
┌────────────────────────────────────────────────────────────────────────────┐
│  Chat with miniforge AI                                          [✕ Close] │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  💬 Context: PR #234 (acme/infra - Import RDS)                            │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │                                                                      │ │
│  │  You:                                                                │ │
│  │  Is this safe to merge?                                              │ │
│  │                                                                      │ │
│  │  🤖 miniforge AI:                                                    │ │
│  │  Yes, this is safe to merge. Here's why:                             │ │
│  │                                                                      │ │
│  │  1. **No infrastructure changes** - This is a state-only import,     │ │
│  │     meaning we're just adding the existing RDS instance to Terraform │ │
│  │     state without modifying it.                                      │ │
│  │                                                                      │ │
│  │  2. **Intent validated** - The semantic-intent-check gate confirmed  │ │
│  │     that the IMPORT intent matches the actual changes (no creates,   │ │
│  │     no destroys).                                                    │ │
│  │                                                                      │ │
│  │  3. **Rollback is trivial** - If something goes wrong, we can simply │ │
│  │     remove the resource from state with `terraform state rm`.        │ │
│  │                                                                      │ │
│  │  4. **Similar pattern succeeded** - PRs #210 and #189 did similar    │ │
│  │     imports with no issues.                                          │ │
│  │                                                                      │ │
│  │  The only dependency is that PRs #235 and #236 need this merged      │ │
│  │  first, but they're already in the train sequence.                   │ │
│  │                                                                      │ │
│  │  [Approve] [Merge]                                                   │ │
│  │                                                                      │ │
│  ├──────────────────────────────────────────────────────────────────────┤ │
│  │                                                                      │ │
│  │  You:                                                                │ │
│  │  What if the import fails?                                           │ │
│  │                                                                      │ │
│  │  🤖 miniforge AI:                                                    │ │
│  │  Good question. If the Terraform import fails:                       │ │
│  │                                                                      │ │
│  │  **What happens:**                                                   │ │
│  │  - The `terraform import` command will exit with an error            │ │
│  │  - No state changes will be written (imports are atomic)             │ │
│  │  - The PR merge will succeed, but the import won't                   │ │
│  │                                                                      │ │
│  │  **Recovery:**                                                       │ │
│  │  1. Check the import logs to see why it failed                       │ │
│  │  2. Common causes:                                                   │ │
│  │     - RDS instance ID is wrong (check AWS console)                   │ │
│  │     - IAM permissions missing (needs rds:DescribeDBInstances)        │ │
│  │     - Region mismatch                                                │ │
│  │  3. Fix the issue and re-run `terraform import` manually             │ │
│  │  4. Or revert the PR and fix in a new one                            │ │
│  │                                                                      │ │
│  │  **Important:** The evidence bundle shows the RDS instance           │ │
│  │  "miniforge-prod-db" exists in AWS (verified pre-merge), so import   │ │
│  │  failure is unlikely.                                                │ │
│  │                                                                      │ │
│  │  Want me to add a post-merge verification step to the train?         │ │
│  │  [Yes, add verification] [No, I'll monitor manually]                 │ │
│  │                                                                      │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │ Ask a question...                                          [Cmd+Enter]│ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  Quick questions:                                                          │
│  • What's the risk?                                                        │
│  • What could go wrong?                                                    │
│  • Compare to similar PRs                                                  │
│  • What are the dependencies?                                              │
│  • Should I approve this?                                                  │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### Chat Context Injection

**When chatting about a PR, AI has access to:**

```clojure
{:context/type :pr
 :context/pr-number 234
 :context/train-id uuid

 ;; Full PR data
 :pr/data
 {:repo "acme/infra"
  :title "Import RDS instance"
  :status :approved
  :branch "feat/rds-import"
  :ci-status :passed
  :approvals [{:user "alice" :role "senior-engineer"}
              {:user "bob" :role "platform-lead"}]
  :dependencies []
  :blocks [235 236]}

 ;; Evidence bundle
 :evidence/bundle
 {:intent/type :import
  :intent/description "Import existing RDS to Terraform state"
  :intent/constraints [:no-creates :no-destroys :state-only]
  :artifacts [{:type :terraform-plan :content "..."}
              {:type :import-checklist :content "..."}]
  :policy-results [{:pack :terraform-plan-review :violations []}]
  :gate-results {:syntax :passed :lint :passed :test :passed}}

 ;; Train context
 :train/data
 {:name "rds-import"
  :dag-id uuid
  :prs [234 235 236]
  :sequence [234 235 236]
  :status :in-progress}

 ;; Historical context
 :history/similar-prs
 [{:pr-number 210 :similarity 0.87 :outcome :success}
  {:pr-number 189 :similarity 0.79 :outcome :success}]

 ;; User context
 :user/role :senior-engineer
 :user/permissions [:approve :merge]}
```

**AI System Prompt:**

```text
You are miniforge AI, an expert at reviewing infrastructure changes.

Current Context:
- Reviewing PR #{pr-number} in the {train-name} train
- User is a {user-role} with {permissions}
- PR is {status}, CI is {ci-status}

Evidence Bundle:
{evidence-bundle-json}

Your role:
1. Answer questions about this PR clearly and concisely
2. Highlight risks and concerns
3. Reference specific evidence (terraform plan, policy results, etc.)
4. Compare to similar past changes when relevant
5. Recommend actions (approve, request changes, merge)
6. Offer to take actions (if user approves)

Be direct and actionable. If the user asks "is this safe?", give a
yes/no answer first, then justify.

Use formatting:
- **Bold** for emphasis
- `code` for technical terms
- Lists for multiple points
- [Action Buttons] to suggest next steps
```

### Batch Chat (Multiple PRs)

```text
┌────────────────────────────────────────────────────────────────────────────┐
│  Chat with miniforge AI                                          [✕ Close] │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  💬 Context: 12 PRs selected (rds-import train)                           │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │                                                                      │ │
│  │  You:                                                                │ │
│  │  Any dangerous changes in these PRs?                                 │ │
│  │                                                                      │ │
│  │  🤖 miniforge AI:                                                    │ │
│  │  Analyzed 12 PRs. **No dangerous changes detected.**                 │ │
│  │                                                                      │ │
│  │  **Summary by Risk:**                                                │ │
│  │                                                                      │ │
│  │  ✓ **Low Risk (10 PRs):**                                            │ │
│  │    - 3 PRs: State-only imports (RDS, ElastiCache, S3)                │ │
│  │    - 4 PRs: Configuration updates (no infrastructure changes)        │ │
│  │    - 3 PRs: Documentation/comments only                              │ │
│  │                                                                      │ │
│  │  ⚠ **Medium Risk (2 PRs):**                                          │ │
│  │    - PR #245: Modifies security group rules (adds port 5432)         │ │
│  │      → Review recommended: Adding PostgreSQL port to SG              │ │
│  │    - PR #248: Updates IAM policy (adds S3 read permissions)          │ │
│  │      → Review recommended: Verify S3 bucket scope                    │ │
│  │                                                                      │ │
│  │  🔴 **High Risk (0 PRs):**                                           │ │
│  │    - None                                                            │ │
│  │                                                                      │ │
│  │  **Recommendation:**                                                 │ │
│  │  - Batch approve the 10 low-risk PRs                                 │ │
│  │  - Individually review PRs #245 and #248                             │ │
│  │                                                                      │ │
│  │  [Approve 10 Low-Risk PRs] [Show Details for PR #245] [Show #248]   │ │
│  │                                                                      │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

**Batch Analysis Prompt:**

```text
Analyze these {count} PRs for dangerous changes.

PRs:
{pr-summaries-json}

For each PR, you have:
- Intent and semantic constraints
- Terraform plan output
- Policy scan results
- Diff summary

Tasks:
1. Categorize each PR by risk (Low/Medium/High)
2. Group by risk level
3. Highlight any dangerous patterns:
   - Network resource recreations (-/+)
   - Database changes
   - Security group/IAM changes
   - Forced resource recreation
   - Large-scale deletions
4. Recommend action (batch approve vs individual review)

Format:
- Summary by risk level
- Specific concerns for medium/high risk
- Clear recommendation with action buttons
```

---

## Part 3: Batch AI Gate Operations

### Use Case: Terraform Plan Review on 20 PRs

**Workflow:**

1. Filter PRs: `repo:*/terraform status:approved`
2. Select all (20 PRs)
3. Press `G` (run gate) or right-click → "Run Gate"
4. Select gate: "Terraform Plan Safety Review"
5. AI analyzes all 20 plans in parallel
6. Results displayed in table

```text
┌────────────────────────────────────────────────────────────────────────────┐
│  Terraform Plan Safety Review - 20 PRs analyzed                            │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  🤖 Analysis complete in 8.3s                                             │
│                                                                            │
│  ✓ SAFE (15 PRs)                  ⚠ NEEDS REVIEW (4 PRs)    🔴 BLOCKED (1)│
│                                                                            │
├────────────────────────────────────────────────────────────────────────────┤
│ PR#  REPO           FINDING                               RISK    ACTION  │
├────────────────────────────────────────────────────────────────────────────┤
│ 234  acme/infra    State-only import                      ✓       Approve │
│ 235  acme/infra    Config update (variables)              ✓       Approve │
│ 236  acme/infra    Add S3 bucket (new resource)           ✓       Approve │
│ ...  (12 more)                                            ✓       Approve │
│                                                                            │
│ 240  acme/infra    ⚠ Modifies security group              ⚠       Review  │
│                    → Changes ingress rules                                 │
│                                                                            │
│ 242  acme/infra    ⚠ Updates RDS instance                 ⚠       Review  │
│                    → Changes instance class (downsize)                     │
│                                                                            │
│ 244  acme/infra    ⚠ Modifies IAM role                    ⚠       Review  │
│                    → Adds S3 full access (scope?)                          │
│                                                                            │
│ 247  acme/infra    ⚠ Changes VPC route table              ⚠       Review  │
│                    → New route to NAT gateway                              │
│                                                                            │
│ 250  acme/infra    🔴 DESTROYS NAT gateway (-/+)          🔴       BLOCK   │
│                    → Will cause outage during recreation                   │
│                    → Recommendation: Use create_before_destroy             │
│                                                                            │
├────────────────────────────────────────────────────────────────────────────┤
│ [✓ Approve 15 Safe PRs]  [📋 Export Report]  [🔍 Review Findings]        │
└────────────────────────────────────────────────────────────────────────────┘
```

### Gate Types for Batch Analysis

#### 1. Terraform Plan Safety

**Checks:**

- Network resource recreations (-/+)
- Resource deletions
- Forced new resources
- Security group/IAM changes
- Database instance changes
- Large-scale changes (>50 resources)

**Output:**

- Safe / Needs Review / Blocked
- Specific finding (what triggered alert)
- Recommendation (what to do)

#### 2. Kubernetes Manifest Safety

**Checks:**

- Deployment strategy changes
- Resource limit changes
- PVC deletions (data loss risk)
- Service type changes (LoadBalancer → NodePort)
- Ingress changes (public exposure)
- RBAC permission escalation

**Output:**

- Safe / Needs Review / Blocked
- Impact radius (which pods/services affected)

#### 3. Semantic Intent Validation

**Checks:**

- IMPORT → no creates/destroys
- CREATE → no unexpected modifies
- MODIFY → no unexpected creates/destroys
- DELETE → no orphaned resources

**Output:**

- Intent matches / Intent violation
- What violated (e.g., "IMPORT but found 3 creates")

#### 4. Cross-PR Conflict Detection

**Checks:**

- Same file modified in multiple PRs
- Same Terraform resource in multiple PRs
- Conflicting Kubernetes manifests
- Dependency order violations

**Output:**

- No conflicts / Conflicts detected
- Which PRs conflict
- Recommendation (merge order or split)

---

## Part 4: AI-Powered Quick Actions

### CLI TUI: AI Actions Menu

```text
╭─────────────────────────────────────────────────────────────────────────────╮
│ AI Actions (12 PRs selected)                                    [Esc] Cancel│
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [1] 🤖 Summarize all PRs                                                   │
│      → Generate one-paragraph summary per PR                               │
│                                                                             │
│  [2] 🔍 Find dangerous changes                                              │
│      → Run Terraform plan safety review                                    │
│                                                                             │
│  [3] ⚠️  Semantic intent check                                              │
│      → Validate that changes match declared intent                         │
│                                                                             │
│  [4] 🔗 Check for conflicts                                                 │
│      → Find PRs that modify same files/resources                           │
│                                                                             │
│  [5] 📊 Risk assessment                                                     │
│      → Categorize by risk level (low/medium/high)                          │
│                                                                             │
│  [6] 💬 Open batch chat                                                     │
│      → Ask questions about all selected PRs                                │
│                                                                             │
│  [7] ✅ Smart approve                                                       │
│      → Approve only low-risk PRs (with confirmation)                       │
│                                                                             │
│  [8] 📝 Generate comparison report                                          │
│      → Compare selected PRs side-by-side                                   │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  [1-8] Select action  [Esc] Cancel                                          │
╰─────────────────────────────────────────────────────────────────────────────╯
```

### Web: Right-Click Context Menu

```text
┌─────────────────────────────────┐
│  12 PRs Selected               │
├─────────────────────────────────┤
│  ✓ Approve Selected             │
│  → Merge Selected               │
│  💬 Comment on Selected         │
│  ──────────────────────────     │
│  🤖 AI Actions ▸                │
│     ├─ Summarize All            │
│     ├─ Find Dangerous Changes   │
│     ├─ Semantic Intent Check    │
│     ├─ Risk Assessment          │
│     ├─ Conflict Detection       │
│     └─ Open Batch Chat          │
│  ──────────────────────────     │
│  🔍 Run Gate ▸                  │
│     ├─ Terraform Plan Review    │
│     ├─ K8s Manifest Safety      │
│     ├─ Security Scan            │
│     └─ Custom Gate...           │
│  ──────────────────────────     │
│  📊 Export ▸                    │
│     ├─ CSV                      │
│     ├─ JSON                     │
│     └─ Evidence Bundle          │
└─────────────────────────────────┘
```

---

## Part 5: Implementation Architecture

### AI Pipeline

```clojure
;; AI analysis is cached and async

{:ai-analysis/pr-number 234
 :ai-analysis/created-at inst
 :ai-analysis/expires-at inst  ; cache for 1 hour

 ;; Summaries at different levels
 :ai-analysis/one-liner "Imports existing RDS, no infra changes, low risk ✓"
 :ai-analysis/executive-summary "..."
 :ai-analysis/deep-analysis "..."

 ;; Risk assessment
 :ai-analysis/risk-level :low  ; :low, :medium, :high
 :ai-analysis/risk-factors []

 ;; Comparison to similar PRs
 :ai-analysis/similar-prs [{:pr-number 210 :similarity 0.87}]

 ;; Cached gate results
 :ai-analysis/gate-results
 {:terraform-plan-safety {:safe? true :findings []}
  :semantic-intent {:valid? true :violations []}}}
```

### Background Processing

```text
┌──────────────────────────────────────────────────────────────────┐
│                     PR Event Stream                               │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                 AI Analysis Worker Pool                           │
│                                                                   │
│  Worker 1: Generate one-liner summary                             │
│  Worker 2: Generate executive summary                             │
│  Worker 3: Deep analysis                                          │
│  Worker 4: Run Terraform plan safety gate                         │
│  Worker 5: Semantic intent validation                             │
│  Worker 6: Find similar PRs                                       │
│                                                                   │
└────────────────────┬─────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Cache Store                                    │
│  Key: pr:{pr-number}:ai-analysis                                  │
│  TTL: 1 hour                                                      │
│  Invalidate on: new commit, new approval, CI status change        │
└──────────────────────────────────────────────────────────────────┘
```

**Performance:**

- **Initial PR creation:** 30-60s to complete all AI analyses
- **User opens PR:** <100ms (read from cache)
- **New commit pushed:** Invalidate cache, regenerate in background
- **Batch analysis (20 PRs):** Parallel processing, ~10-15s total

### Chat Architecture

```clojure
;; Chat session tied to PR or batch of PRs

{:chat-session/id uuid
 :chat-session/type :pr  ; or :batch
 :chat-session/context
 {:pr-numbers [234]  ; or [234 235 236] for batch
  :train-id uuid
  :user-id "chris"}

 :chat-session/messages
 [{:role :user
   :content "Is this safe to merge?"
   :timestamp inst}

  {:role :assistant
   :content "Yes, this is safe to merge. Here's why:\n\n1. **No infrastructure changes**..."
   :timestamp inst
   :actions [{:type :approve :pr 234}
             {:type :merge :pr 234}]}]

 :chat-session/available-context
 {:evidence-bundle {...}
  :pr-data {...}
  :train-data {...}
  :similar-prs [...]
  :user-permissions [...]}}
```

**Chat Flow:**

1. User opens chat → Create session with context
2. User types question → Send to LLM with full context
3. LLM responds with answer + suggested actions
4. User clicks action button → Execute + log in chat
5. Context updates (e.g., PR approved) → Automatically injected into next message

### LLM Selection Strategy

**Different tasks = different models:**

| Task | Model | Reasoning |
|------|-------|-----------|
| One-liner summary | Haiku | Fast, cheap, simple task |
| Executive summary | Sonnet | Balance of quality and speed |
| Deep analysis | Sonnet | Complex reasoning needed |
| Chat responses | Sonnet | Conversational, context-aware |
| Batch analysis (20 PRs) | Haiku for each, Sonnet for synthesis | Parallel Haiku → aggregate with Sonnet |
| Terraform plan review | Sonnet | Complex technical analysis |

**Cost Optimization:**

- Cache all AI responses (1-hour TTL)
- Use Haiku for simple tasks (one-liners, quick summaries)
- Use Sonnet only for complex reasoning (deep analysis, chat)
- Batch similar requests (20 one-liners → 1 Sonnet call with all 20)

---

## Part 6: Example Workflows

### Workflow 1: Daily PR Triage (100 PRs)

**9:00 AM - Start of day**

```text
Step 1: Filter to "Ready to Merge" (30 PRs)
- AI already generated summaries overnight

Step 2: Scan one-liners
- 25 PRs show "low risk ✓"
- 3 PRs show "medium risk ⚠"
- 2 PRs show "high risk 🔴"

Step 3: Batch select 25 low-risk
- Press 'G' → Run Gate → "Terraform Plan Safety"
- AI confirms: "All safe, no dangerous changes"
- Press 'A' → Approve all 25

Step 4: Review 3 medium-risk individually
- Open each, read AI summary
- 2 are actually fine (false positives)
- 1 needs change (comment with AI suggestion)

Step 5: Escalate 2 high-risk
- AI summary shows: "Destroys NAT gateway, will cause outage"
- Comment: "@owner Please use create_before_destroy lifecycle"
- Move to "Needs Changes" status
```

**Result:** 27 PRs processed in 15 minutes

**9:15 AM - Continue with "Needs Approval"**

```text
Step 1: Filter to "Needs Approval" (40 PRs)

Step 2: Group by repo type
- Terraform: 15 PRs
- Kubernetes: 10 PRs
- Application: 15 PRs

Step 3: Batch process Terraform PRs
- Select all 15
- Open batch chat: "Any dangerous Terraform changes?"
- AI responds: "1 PR recreates load balancer, others safe"
- Approve 14, review 1

Step 4: Batch process K8s PRs
- Select all 10
- Run gate: "K8s Manifest Safety"
- AI: "2 PRs remove resource limits (risky), others safe"
- Approve 8, comment on 2

Step 5: Quick scan Application PRs
- Most are features/bugs, low infra risk
- Bulk approve (trust CI + tests)
```

**Result:** 37 PRs processed in 20 minutes

**Total: 64 PRs in 35 minutes** (well above 100/day pace)

### Workflow 2: Investigating a Blocked Train

Scenario: **Train "k8s-migration" is blocked**

```text
Step 1: Open train detail
- AI one-liner: "Blocked by dependency conflict in PR #124"

Step 2: Open chat
- Ask: "Why is this train blocked?"
- AI: "PR #124 depends on #123, but #123 failed CI. Fix #123 first."

Step 3: Click #123 to investigate
- AI summary: "CI failed due to missing IAM permission"
- Evidence shows: Test tried to access S3, got access denied

Step 4: Ask chat: "How do I fix the IAM permission?"
- AI: "Add s3:GetObject permission to the test role in terraform/iam.tf"
- AI: "See similar fix in PR #210"
- [View PR #210] [Generate fix PR]

Step 5: Click "Generate fix PR"
- AI creates a new PR with the IAM change
- Automatically adds to train (before #123)
- Updates train sequence: [new-PR] → #123 → #124 → ...
```

**Result:** Unblocked train in 5 minutes instead of 30

### Workflow 3: High-Risk Change Review

Scenario: **PR flagged as "high risk" by AI**

```text
Step 1: Open PR #250
- AI one-liner: "🔴 DESTROYS NAT gateway (-/+), will cause outage"

Step 2: Read AI deep analysis
- "Terraform plan shows NAT gateway recreation due to subnet change"
- "Blast radius: All EC2 instances in private subnets lose internet"
- "Estimated downtime: 2-5 minutes during recreation"

Step 3: Ask chat: "Can we do this without downtime?"
- AI: "Yes, use create_before_destroy lifecycle policy"
- AI: "Add this to the NAT gateway resource:
       lifecycle {
         create_before_destroy = true
       }"
- [Apply suggestion] [Show example]

Step 4: Click "Apply suggestion"
- AI generates new commit
- Pushes to PR
- CI re-runs
- AI re-analyzes: "✓ Now safe, NAT gateway won't be destroyed"

Step 5: Approve
```

**Result:** Prevented outage, fixed in 3 minutes

---

## Part 7: Progressive Enhancement Strategy

### Phase 1: Cached Summaries (Week 1-2)

**Goal:** Always-available AI summaries

- One-liner on every PR (generated in <30s)
- Executive summary on hover/expand
- Cache for 1 hour, invalidate on changes

**MVP:**

- Haiku for one-liners
- Sonnet for executive summaries
- No deep analysis yet

### Phase 2: Batch Operations (Week 3-4)

**Goal:** Process 20+ PRs at once

- Batch summarize
- Batch risk assessment
- Batch Terraform plan review

**Features:**

- Parallel processing (20 Haiku calls)
- Aggregate with Sonnet
- Display in table

### Phase 3: AI Chat (Week 5-6)

**Goal:** Ask questions about PRs

- Single PR chat
- Batch PR chat
- Action buttons in responses

**Features:**

- Context-aware (knows PR, train, user)
- Suggested actions
- Memory within session

### Phase 4: Advanced Gates (Week 7-8)

**Goal:** Custom AI-powered gates

- Semantic intent validation
- Conflict detection
- Pattern matching (similar PRs)

**Features:**

- Pluggable gate system
- Custom prompts per gate
- Evidence-based validation

---

## Summary

**AI Features Transform PR Management:**

1. **Cached Summaries** - Decision info available instantly, no waiting
2. **Batch Analysis** - Process 20 PRs in the time it takes to review 1
3. **AI Chat** - Get answers without reading docs/plans/diffs
4. **Smart Gates** - Catch dangerous changes humans miss
5. **Action Suggestions** - AI tells you what to do, you just click

**The Workflow:**

1. Filter → Batch select → AI analyze → Bulk approve safe ones
2. Review flagged PRs with AI chat for quick triage
3. Deep dive only on genuinely risky changes

**Throughput Impact:**

- **Before AI:** 100 PRs = 3-4 hours (2-3 min each)
- **With AI:** 100 PRs = 60 minutes (36s each)

**The key:** AI pre-processes and caches everything, so when you look at a PR, all the information you need to make a
  decision is already there.

Ready to start implementing? I'd recommend:

1. **Week 1:** Cached one-liner summaries (prove the concept)
2. **Week 2:** Batch Terraform plan review (high-value use case)
3. **Week 3:** AI chat (most visible feature, good for demos)
