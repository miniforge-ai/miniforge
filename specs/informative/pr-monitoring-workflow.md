<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR Monitoring Workflow

**Date:** 2026-02-01
**Status:** Informative (Future Feature)
**Informs:** N2 (Workflows), N3 (Events), N6 (Evidence)

---

## Overview

The **PR Monitoring Workflow** is a reactive workflow that watches open PRs and responds to
external events to get them to a "ready to merge" state. Unlike the Release phase (which
_creates_ PRs), PR Monitoring _maintains_ them through their lifecycle.

This is separate from the release phase by design:

- **Release** = proposing changes (branch → commit → push → PR)
- **PR Monitoring** = shepherding PRs to completion (conflicts, comments, CI)

The human remains in the loop for final review and merge.

---

## Problem Statement

After miniforge creates a PR, several things can happen:

1. **Main branch advances** → PR has merge conflicts
2. **CI fails** → Tests break, linting fails, security scan flags issues
3. **Bot comments** → Dependabot, CodeQL, coverage tools leave feedback
4. **Human comments** → Reviewers ask questions or request changes
5. **Approval conditions** → Required reviewers, status checks pending

Currently, these require human intervention. A PR Monitoring workflow can handle most of these autonomously.

---

## Workflow Model

```text
┌─────────────────────────────────────────────────────────────────┐
│                      PR Monitoring Workflow                      │
│                                                                  │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐  │
│  │  Watch   │───▶│  Triage  │───▶│  Handle  │───▶│  Signal  │  │
│  │  Events  │    │  Event   │    │  Event   │    │  Ready   │  │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘  │
│       ▲                                               │         │
│       └───────────────────────────────────────────────┘         │
│                         (loop until ready or abandoned)         │
└─────────────────────────────────────────────────────────────────┘
```

### Event Sources

The workflow monitors:

1. **GitHub webhooks** - Push events (main updated), PR comments, reviews, status checks
2. **Polling** - Fallback for webhook-less environments
3. **Internal events** - Retry timers, budget exhaustion signals

### Event Types and Handlers

| Event Type               | Detection               | Handler                   |
| ------------------------ | ----------------------- | ------------------------- |
| Merge conflict           | `mergeable: false`      | Conflict Resolution Agent |
| CI failure               | Status check failed     | Re-implement or fix       |
| Bot comment              | Comment from known bots | Parse and act             |
| Human comment            | Comment from humans     | Comment Response Agent    |
| Review requested changes | Review state            | Address feedback          |
| All checks pass          | All green               | Signal ready              |

---

## Component: Conflict Resolution Agent

A specialized agent for resolving git merge conflicts.

### Input

```clojure
{:conflict/pr-url "https://github.com/org/repo/pull/123"
 :conflict/files ["src/auth/login.clj" "src/config.clj"]
 :conflict/base-branch "main"
 :conflict/head-branch "feature/add-auth"
 :conflict/markers [...] ;; Parsed conflict markers per file
 :conflict/original-intent "Implement user authentication"
 :conflict/our-changes {...} ;; What our PR changed
 :conflict/their-changes {...}} ;; What main changed
```

### Output

```clojure
{:resolution/id #uuid "..."
 :resolution/strategy :auto|:manual-required
 :resolution/files [{:path "src/auth/login.clj"
                     :resolved-content "..."
                     :confidence :high|:medium|:low
                     :explanation "Kept our auth logic, integrated their config refactor"}]
 :resolution/verification-needed? true
 :resolution/commit-message "fix: resolve merge conflicts with main"}
```

### Resolution Strategies

1. **Trivial conflicts** - Whitespace, import ordering → auto-resolve with high confidence
2. **Additive conflicts** - Both sides added code → merge both, verify tests pass
3. **Semantic conflicts** - Both sides modified same logic → requires understanding
4. **Structural conflicts** - Refactoring vs. feature changes → may need re-implementation

### Confidence Levels and Actions

| Confidence | Action                                            |
| ---------- | ------------------------------------------------- |
| High       | Auto-apply, push, continue                        |
| Medium     | Apply, run tests, if pass continue, else escalate |
| Low        | Create draft resolution, request human review     |

### Semantic Conflict Detection

Git doesn't detect semantic conflicts:

```clojure
;; Main renamed user-id → account-id everywhere
;; Our branch added code using user-id
;; Git: "no conflict!"
;; Reality: broken code
```

The agent should:

1. After resolution, run full test suite
2. Run static analysis (undefined symbols, type errors)
3. If issues found, treat as conflict requiring re-implementation

---

## Component: Comment Response Agent

Handles comments on PRs from bots and humans.

### Bot Comment Handling

| Bot        | Comment Pattern          | Action                           |
| ---------- | ------------------------ | -------------------------------- |
| Dependabot | Version update available | Evaluate and update if safe      |
| CodeQL     | Security finding         | Assess severity, fix if critical |
| Codecov    | Coverage decreased       | Add tests or justify             |
| Renovate   | Dependency update        | Same as Dependabot               |

### Human Comment Handling

1. **Parse intent** - Question? Request for change? Approval?
2. **Categorize** - Code style, logic, design, clarification
3. **Generate response** - Answer question or implement change
4. **Push update** - If change made, push and respond with summary

### Response Format

```clojure
{:response/comment-id 12345
 :response/in-reply-to "Can you add error handling for network timeouts?"
 :response/action :code-change|:clarification|:question
 :response/body "Added timeout handling in `fetch-user`. The default is 30s, configurable via `FETCH_TIMEOUT_MS`."
 :response/commits-pushed ["abc1234"]
 :response/files-changed ["src/api/client.clj"]}
```

---

## Component: CI Failure Handler

When CI fails after PR creation or after conflict resolution:

1. **Parse failure** - Which check failed? What's the error?
2. **Categorize** - Test failure, lint error, build error, security scan
3. **Route to appropriate agent**:
   - Test failure → Tester agent (fix test or fix code)
   - Lint error → Implementer agent (auto-fix)
   - Build error → Implementer agent (fix compilation)
   - Security scan → Security review, may need human

### Retry Budget

The workflow has a budget for CI fix attempts:

```clojure
{:budget/max-ci-fix-attempts 3
 :budget/max-conflict-resolution-attempts 2
 :budget/max-comment-responses 5
 :budget/abandon-after-hours 72}
```

If budget exhausted → signal for human intervention.

---

## Integration with Existing Specs

### N2 - Workflows

PR Monitoring is a new workflow type:

```clojure
{:workflow/type :pr-monitoring
 :workflow/trigger :webhook|:poll|:manual
 :workflow/phases [:watch :triage :handle :signal]
 :workflow/budget {...}}
```

### N3 - Events

New event types for PR monitoring:

```clojure
;; Conflict detected
{:event/type :pr-monitor/conflict-detected
 :pr/url "..."
 :conflict/files [...]}

;; Conflict resolved
{:event/type :pr-monitor/conflict-resolved
 :resolution/strategy :auto
 :resolution/confidence :high}

;; Comment received
{:event/type :pr-monitor/comment-received
 :comment/author "reviewer"
 :comment/type :human|:bot}

;; PR ready
{:event/type :pr-monitor/ready-for-merge
 :pr/url "..."
 :checks/all-passed true}
```

### N6 - Evidence

PR monitoring adds to the evidence bundle:

```clojure
{:evidence/pr-lifecycle
 {:created-at #inst "..."
  :conflicts-resolved [{:at #inst "..." :strategy :auto}]
  :comments-addressed [{:comment-id 123 :response "..."}]
  :ci-failures-fixed [{:check "lint" :fixed-at #inst "..."}]
  :ready-at #inst "..."
  :total-monitoring-duration-hours 4.5}}
```

---

## Operational Modes

### Fully Autonomous

```clojure
{:mode :autonomous
 :auto-resolve-conflicts true
 :auto-respond-comments true
 :auto-fix-ci true
 :human-merge-required true} ;; Human still clicks merge
```

### Supervised

```clojure
{:mode :supervised
 :auto-resolve-conflicts false ;; Creates draft, waits for approval
 :auto-respond-comments :bots-only
 :auto-fix-ci true
 :human-approval-required-for [:conflict-resolution :human-comments]}
```

### Monitor Only

```clojure
{:mode :monitor-only
 :notify-on [:conflict :ci-failure :comment]
 :auto-actions false}
```

---

## Future Considerations

### Learning from Resolutions

Over time, the system can learn:

- Common conflict patterns in this codebase
- Which bot comments can be auto-addressed
- Reviewer preferences and common feedback

### Multi-PR Coordination

When multiple PRs conflict with each other:

- Detect PR-to-PR conflicts
- Suggest merge order
- Rebase dependent PRs after merge

### Merge Automation

With sufficient confidence and policy approval:

- Auto-merge when all checks pass
- Respect branch protection rules
- Honor merge queue if configured

---

## Implementation Priority

1. **Phase 1: Conflict Resolution Agent** - Core capability, most common need
2. **Phase 2: Bot Comment Handler** - Low-risk automation
3. **Phase 3: CI Failure Handler** - Ties into existing agents
4. **Phase 4: Human Comment Response** - Requires careful prompt engineering
5. **Phase 5: Full Workflow Integration** - Webhook handling, polling, event loop

---

## Relationship to Release Phase

```text
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Implement      │────▶│  Release        │────▶│  PR Monitoring  │
│  Phase          │     │  Phase          │     │  Workflow       │
│                 │     │                 │     │                 │
│  Writes code    │     │  Creates PR     │     │  Shepherds PR   │
│                 │     │  with artifacts │     │  to merge-ready │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                        │
                                                        ▼
                                                 Human merges
```

The release phase we just built creates the PR. PR Monitoring takes over from there.

---

**Version:** 0.1.0-draft
**Last Updated:** 2026-02-01
