# miniforge.ai — Operational Modes Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Purpose

miniforge.ai operates in multiple modes beyond the full SDLC loop:

| Mode | Description | Primary Use |
|------|-------------|-------------|
| **Full Loop** | Intent → Production (existing specs) | Greenfield features |
| **PR Loop** | Submit → Merged (monitor, fix, respond) | All code changes |
| **Single Step** | Run one phase only | Debugging, targeted tasks |
| **Fleet Mode** | Multi-repo PR management | Team-scale operations |
| **Local Self-Improve** | On-machine meta loop | Dev experience |

This spec defines these operational modes and their interactions.

### 1.2 Design Principles

1. **Local-first**: Full capability on a single dev machine (except fine-tuning)
2. **PR as primitive**: PRs are the atomic unit of delivery, not artifacts
3. **Composable steps**: Full loops are compositions of single steps
4. **Self-healing**: Operator can spawn fix workflows autonomously
5. **Human-in-the-loop ready**: Every mode supports intervention points

---

## 2. Local Self-Improvement

### 2.1 Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         LOCAL DEV MACHINE                                │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │                         OPERATOR AGENT                              │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │ │
│  │  │  Monitor    │  │   Decide    │  │   Control   │                 │ │
│  │  │  Workflows  │  │   Actions   │  │   Workflows │                 │ │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘                 │ │
│  │         └─────────────────┴─────────────────┘                       │ │
│  └──────────────────────────────┬─────────────────────────────────────┘ │
│                                 │                                        │
│  ┌──────────────────────────────┼────────────────────────────────────┐  │
│  │                              ▼                                     │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐              │  │
│  │  │Workflow │  │Workflow │  │  Fix    │  │Workflow │              │  │
│  │  │    A    │  │    B    │  │Workflow │  │    D    │              │  │
│  │  │(paused) │  │(running)│  │(running)│  │(pending)│              │  │
│  │  └─────────┘  └─────────┘  └────┬────┘  └─────────┘              │  │
│  │                                 │                                  │  │
│  │                                 ▼                                  │  │
│  │                         ┌─────────────┐                           │  │
│  │                         │  PR Loop    │                           │  │
│  │                         │  (active)   │                           │  │
│  │                         └──────┬──────┘                           │  │
│  │                                │                                   │  │
│  └────────────────────────────────┼───────────────────────────────────┘  │
│                                   │                                      │
│  ┌────────────────────────────────┼───────────────────────────────────┐  │
│  │                   LOCAL GIT    │                                   │  │
│  │  ┌────────────────────────────▼──────────────────────────────────┐│  │
│  │  │  main ──► feature-A ──► fix-workflow-123 ──► (merged)         ││  │
│  │  └───────────────────────────────────────────────────────────────┘│  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                      HEURISTIC STORE (local)                       │  │
│  │  prompts.edn  │  thresholds.edn  │  repair-strategies.edn         │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Self-Improvement Flow

When the Operator detects a problem:

```
1. DETECT
   ├── Signal: Workflow A has failed 3 times on same error
   └── Analysis: Missing handling for edge case X

2. PAUSE
   ├── Action: Pause Workflow A
   └── Checkpoint: Save state at failed task

3. SPAWN FIX WORKFLOW
   ├── Create spec: "Fix edge case X in module Y"
   ├── Context: Include error logs, failing test, relevant code
   └── Start: New workflow targeting the fix

4. MONITOR FIX
   ├── Track: Fix workflow → PR created → Review → Merged
   └── Wait: Until PR is merged to main

5. RESUME OR RESTART
   ├── If fix applies to paused state: Resume from checkpoint
   └── If state is stale: Restart Workflow A from beginning
```

### 2.3 Fix Workflow Spec Schema

```clojure
{:fix-spec/id              uuid
 :fix-spec/type            :self-improvement
 :fix-spec/trigger
 {:source-workflow   uuid           ; workflow that failed
  :source-task       uuid           ; task that failed
  :failure-type      keyword        ; :test-failure, :validation-error, etc.
  :failure-count     integer        ; how many times it failed
  :error-summary     string}

 :fix-spec/objective       string   ; what needs to be fixed
 :fix-spec/context
 {:error-logs        [string]
  :failing-tests     [string]
  :relevant-files    [string]
  :recent-changes    [CommitRef]}

 :fix-spec/constraints
 {:target-branch     string         ; where to merge
  :must-pass-tests   boolean
  :requires-review   boolean}

 :fix-spec/on-complete
 {:resume-workflow   uuid           ; workflow to resume
  :resume-from       keyword        ; :checkpoint, :beginning, :specific-phase
  :notify            [string]}}
```

### 2.4 Operator Decision Rules for Self-Improvement

```clojure
{:operator/self-improve-rules
 [{:trigger {:failure-count (>= 3)
             :same-error? true}
   :action :spawn-fix-workflow
   :params {:objective-template "Fix recurring error: {{error-type}} in {{module}}"
            :include-context [:error-logs :failing-tests :recent-changes]}}

  {:trigger {:inner-loop-iterations (> 5)
             :no-progress? true}
   :action :pause-and-diagnose
   :params {:spawn-diagnostic true}}

  {:trigger {:pr-blocked-hours (> 24)
             :has-unresolved-comments true}
   :action :escalate-or-fix
   :params {:try-fix-first true
            :escalate-after-n-attempts 2}}

  {:trigger {:heuristic-underperforming true
             :sample-size (> 50)}
   :action :propose-heuristic-update
   :params {:requires-approval false    ; local mode: auto-apply
            :rollback-on-regression true}}]}
```

### 2.5 Local Heuristic Updates

In local mode, meta loop improvements apply immediately (no approval gate):

```clojure
{:local-meta-loop
 {:approval-required     false        ; auto-apply locally
  :shadow-mode-duration  0            ; skip shadow
  :canary-percentage     1.0          ; 100% immediately
  :rollback-trigger      {:quality-drop 0.1}
  :persist-to            "~/.miniforge/heuristics/"
  :sync-to-repo          true}}       ; commit heuristic changes
```

---

## 3. PR Loop Mode

### 3.1 PR Loop State Machine

```
┌───────────────────────────────────────────────────────────────────────────┐
│                              PR LOOP                                       │
│                                                                            │
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐         │
│  │  DRAFT   │────►│  OPEN    │────►│  REVIEW  │────►│  CHANGES │         │
│  └──────────┘     └──────────┘     └──────────┘     │ REQUESTED│         │
│                        │                 │          └─────┬────┘         │
│                        │                 │                │               │
│                        ▼                 ▼                ▼               │
│                   ┌──────────┐     ┌──────────┐     ┌──────────┐         │
│                   │ CI/CHECK │     │ COMMENTS │     │  FIXING  │         │
│                   │ RUNNING  │     │ PENDING  │     │          │         │
│                   └────┬─────┘     └─────┬────┘     └─────┬────┘         │
│                        │                 │                │               │
│                        │      ┌──────────┴───────────────┘               │
│                        │      │                                           │
│                        ▼      ▼                                           │
│                   ┌──────────────┐                                        │
│                   │   APPROVED   │                                        │
│                   └───────┬──────┘                                        │
│                           │                                               │
│                           ▼                                               │
│                   ┌──────────────┐                                        │
│                   │    MERGED    │ ◄── Terminal success                  │
│                   └──────────────┘                                        │
│                                                                            │
│  Terminal failures: CLOSED, ABANDONED                                     │
└───────────────────────────────────────────────────────────────────────────┘
```

### 3.2 PR Loop Schema

```clojure
{:pr-loop/id              uuid
 :pr-loop/status          keyword       ; see state machine
 :pr-loop/pr-url          string
 :pr-loop/pr-number       integer
 :pr-loop/repo            string
 :pr-loop/branch          string
 :pr-loop/base-branch     string

 ;; Source
 :pr-loop/source
 {:workflow-id      uuid              ; parent workflow (if any)
  :created-by       string}           ; human or agent

 ;; Tracking
 :pr-loop/checks
 {:ci-status        keyword           ; :pending, :passing, :failing
  :ci-url           string
  :required-checks  [string]
  :passed-checks    [string]
  :failed-checks    [string]}

 :pr-loop/comments
 {:total            integer
  :resolved         integer
  :unresolved       integer
  :threads          [CommentThread]}

 :pr-loop/reviews
 {:approvals        integer
  :required         integer
  :reviewers        [ReviewerStatus]}

 ;; History
 :pr-loop/iterations      integer      ; how many fix cycles
 :pr-loop/commits         [CommitRef]
 :pr-loop/events          [PREvent]

 ;; Timing
 :pr-loop/created-at      inst
 :pr-loop/last-activity   inst
 :pr-loop/merged-at       inst}
```

### 3.3 Comment Thread Handling

```clojure
{:comment-thread/id       string        ; GitHub thread ID
 :comment-thread/file     string
 :comment-thread/line     integer
 :comment-thread/status   keyword       ; :open, :resolved, :outdated

 :comment-thread/comments
 [{:comment/id       string
   :comment/author   string
   :comment/body     string
   :comment/is-bot   boolean           ; is this from a review bot?
   :comment/created  inst}]

 :comment-thread/resolution
 {:resolved-by      keyword            ; :code-fix, :reasoning, :dismissed
  :resolution-commit string            ; if code-fix
  :resolution-reply  string            ; if reasoning
  :accepted?         boolean}}         ; did reviewer accept?
```

### 3.4 Comment Resolution Strategies

```clojure
{:pr-loop/resolution-strategies
 [{:condition {:comment-type :actionable-fix
               :confidence (> 0.8)}
   :strategy :code-fix
   :action "Make the requested change and push commit"}

  {:condition {:comment-type :style-suggestion
               :conflicts-with-codebase? true}
   :strategy :reasoning
   :action "Reply explaining existing codebase conventions"}

  {:condition {:comment-type :bot-review
               :known-false-positive? true}
   :strategy :dismiss-with-reason
   :action "Reply with reasoning, mark resolved"}

  {:condition {:comment-type :question}
   :strategy :answer
   :action "Reply with explanation"}

  {:condition {:comment-type :unclear
               :attempts (> 2)}
   :strategy :escalate
   :action "Tag human for clarification"}]}
```

### 3.5 Handling Re-Opened Comments

When a resolved comment is re-opened or marked unresolved:

```clojure
{:reopened-comment/handling
 {:detect-trigger  :comment-status-change
  :actions
  [{:if :resolution-was-reasoning
    :then :try-code-fix
    :reasoning "Reviewer didn't accept explanation, try fixing instead"}

   {:if :resolution-was-code-fix
    :then :analyze-feedback
    :reasoning "Fix wasn't sufficient, understand why"}

   {:if :attempts (>= 3)
    :then :escalate-to-human
    :reasoning "Multiple attempts failed, need human judgment"}]}}
```

### 3.6 PR Loop Controller

```clojure
(defprotocol PRLoopController
  ;; Lifecycle
  (start-pr-loop [this pr-url config]
    "Start monitoring/managing a PR")

  (pause-pr-loop [this pr-loop-id reason]
    "Stop active management, continue monitoring")

  (resume-pr-loop [this pr-loop-id]
    "Resume active management")

  (abandon-pr-loop [this pr-loop-id reason]
    "Stop all management, optionally close PR")

  ;; Actions
  (push-fix [this pr-loop-id fix-commits]
    "Push fix commits to the PR branch")

  (reply-to-comment [this pr-loop-id thread-id response]
    "Reply to a comment thread")

  (resolve-thread [this pr-loop-id thread-id resolution]
    "Mark a thread as resolved")

  (request-review [this pr-loop-id reviewers]
    "Request review from specified reviewers")

  ;; Query
  (get-pr-status [this pr-loop-id]
    "Get current PR loop status")

  (get-blocking-items [this pr-loop-id]
    "Get list of items blocking merge"))
```

---

## 4. Single Step Mode

### 4.1 Available Steps

```clojure
{:single-steps
 [{:step/id          :plan
   :step/description "Generate work plan from specification"
   :step/inputs      [:spec]
   :step/outputs     [:plan :task-graph]
   :step/agent       :planner}

  {:step/id          :design
   :step/description "Create architecture and interface definitions"
   :step/inputs      [:plan :context]
   :step/outputs     [:adr :interfaces]
   :step/agent       :architect}

  {:step/id          :implement
   :step/description "Generate code for a task"
   :step/inputs      [:task :context :existing-code]
   :step/outputs     [:code]
   :step/agent       :implementer}

  {:step/id          :test
   :step/description "Generate tests for code"
   :step/inputs      [:code :spec]
   :step/outputs     [:tests :coverage-report]
   :step/agent       :tester}

  {:step/id          :review
   :step/description "Review artifacts against standards"
   :step/inputs      [:artifacts :policies]
   :step/outputs     [:review-feedback :approval-status]
   :step/agent       :reviewer}

  {:step/id          :diagnose
   :step/description "Analyze failure or issue"
   :step/inputs      [:error-logs :context :code]
   :step/outputs     [:diagnosis :fix-suggestions]
   :step/agent       :diagnostician}

  {:step/id          :deploy
   :step/description "Generate deployment artifacts"
   :step/inputs      [:code :environment-config]
   :step/outputs     [:manifests :runbook]
   :step/agent       :sre}

  {:step/id          :pr-create
   :step/description "Create PR from changes"
   :step/inputs      [:branch :changes :context]
   :step/outputs     [:pr-url]
   :step/agent       :release}

  {:step/id          :pr-respond
   :step/description "Respond to PR comments"
   :step/inputs      [:pr-url :comments]
   :step/outputs     [:responses :fixes]
   :step/agent       :reviewer}]}
```

### 4.2 Single Step Execution

```clojure
(defprotocol StepExecutor
  (run-step [this step-id inputs config]
    "Execute a single step, returns outputs")

  (list-steps [this]
    "List available steps")

  (get-step-schema [this step-id]
    "Get input/output schema for a step")

  (validate-inputs [this step-id inputs]
    "Validate inputs for a step")

  (estimate-cost [this step-id inputs]
    "Estimate token/cost for step"))
```

### 4.3 CLI for Single Steps

```bash
# Run planning step
miniforge step plan --spec ./feature.md --output ./plan.edn

# Run implementation for a specific task
miniforge step implement --task "Add user validation" --context ./src/

# Diagnose a failing test
miniforge step diagnose --error-log ./test-output.log --code ./src/auth/

# Create PR from current branch
miniforge step pr-create --base main --title "Add user validation"

# Respond to PR comments
miniforge step pr-respond --pr https://github.com/org/repo/pull/123
```

### 4.4 Step Chaining

Steps can be composed without running full loop:

```bash
# Plan then implement (without test/review/deploy)
miniforge chain plan,implement --spec ./feature.md

# Implement and test only
miniforge chain implement,test --task "Add caching" --context ./src/

# Diagnose then fix
miniforge chain diagnose,implement --error-log ./failure.log
```

---

## 5. Fleet Mode (Multi-Repo PR Management)

### 5.1 Fleet Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           FLEET CONTROLLER                               │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐│
│  │                         PR WATCHER                                   ││
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐        ││
│  │  │  Repo A   │  │  Repo B   │  │  Repo C   │  │  Repo D   │        ││
│  │  │  3 PRs    │  │  7 PRs    │  │  1 PR     │  │  12 PRs   │        ││
│  │  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘        ││
│  │        └──────────────┴───────────────┴──────────────┘              ││
│  └─────────────────────────────────┬───────────────────────────────────┘│
│                                    │                                     │
│  ┌─────────────────────────────────▼───────────────────────────────────┐│
│  │                         PR QUEUE                                     ││
│  │  ┌─────────────────────────────────────────────────────────────────┐││
│  │  │ Priority │ Repo   │ PR#  │ Status    │ Age   │ Action Needed   │││
│  │  │ ──────── │ ────── │ ──── │ ───────── │ ───── │ ─────────────── │││
│  │  │ 1 (hot)  │ repo-a │ #123 │ failing   │ 2h    │ Fix CI          │││
│  │  │ 2        │ repo-b │ #456 │ comments  │ 1d    │ Respond         │││
│  │  │ 3        │ repo-c │ #789 │ approved  │ 3h    │ Merge           │││
│  │  │ ...      │ ...    │ ...  │ ...       │ ...   │ ...             │││
│  │  └─────────────────────────────────────────────────────────────────┘││
│  └─────────────────────────────────────────────────────────────────────┘│
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐│
│  │                    ACTIVE PR LOOPS                                   ││
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               ││
│  │  │ PR Loop #1   │  │ PR Loop #2   │  │ PR Loop #3   │               ││
│  │  │ repo-a/#123  │  │ repo-b/#456  │  │ (available)  │               ││
│  │  │ fixing...    │  │ responding..│  │              │               ││
│  │  └──────────────┘  └──────────────┘  └──────────────┘               ││
│  └─────────────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Fleet Configuration

```clojure
{:fleet/id               uuid
 :fleet/name             string
 :fleet/repos
 [{:repo/url             string
   :repo/org             string
   :repo/name            string
   :repo/default-branch  string
   :repo/watch-labels    [string]     ; only watch PRs with these labels
   :repo/ignore-labels   [string]     ; ignore PRs with these labels
   :repo/auto-review     boolean      ; automatically review new PRs
   :repo/auto-fix        boolean}]    ; automatically fix failing PRs

 :fleet/concurrency
 {:max-active-pr-loops   integer      ; how many PRs to work on at once
  :max-per-repo          integer}     ; limit per repo

 :fleet/prioritization
 {:factors
  [{:factor :age              :weight 0.3}
   {:factor :review-requested :weight 0.2}
   {:factor :ci-failing       :weight 0.25}
   {:factor :comments-pending :weight 0.15}
   {:factor :label-priority   :weight 0.1}]}

 :fleet/notifications
 {:on-merge              [Channel]
  :on-block              [Channel]
  :on-conflict           [Channel]
  :daily-summary         [Channel]}}
```

### 5.3 Fleet Dashboard (CLI)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ MINIFORGE FLEET - 4 repos │ 23 PRs │ 3 active                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│ ▶ ACTIVE (3)                                                            │
│   ├─ acme/backend#123  [FIXING]     "Add user auth"      eta: 5m       │
│   ├─ acme/frontend#456 [RESPONDING] "Update dashboard"   eta: 2m       │
│   └─ acme/api#789      [REVIEWING]  "API versioning"     eta: 8m       │
│                                                                         │
│ ⏸ BLOCKED (2)                                                           │
│   ├─ acme/backend#120  [CONFLICT]   "Refactor DB layer"  needs: rebase │
│   └─ acme/ml#45        [STUCK]      "Model update"       needs: human  │
│                                                                         │
│ ✓ READY TO MERGE (5)                                                    │
│   ├─ acme/frontend#450 [APPROVED]   "Fix nav bug"        ⌘M to merge   │
│   ├─ acme/api#785      [APPROVED]   "Add rate limit"     ⌘M to merge   │
│   └─ ... 3 more                                                         │
│                                                                         │
│ ⏳ WAITING (13)                                                          │
│   ├─ 4 awaiting review                                                  │
│   ├─ 6 CI running                                                       │
│   └─ 3 in queue                                                         │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ [↑↓] Navigate  [Enter] Details  [M] Merge  [F] Fix  [R] Review  [Q] Quit│
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.4 Fleet Controller Protocol

```clojure
(defprotocol FleetController
  ;; Setup
  (add-repo [this repo-config]
    "Add a repo to the fleet")

  (remove-repo [this repo-url]
    "Remove a repo from the fleet")

  (list-repos [this]
    "List all repos in fleet")

  ;; Monitoring
  (sync-prs [this]
    "Fetch latest PR state from all repos")

  (get-pr-queue [this]
    "Get prioritized list of PRs needing attention")

  (get-fleet-status [this]
    "Get overall fleet status summary")

  ;; Control
  (start-pr-loop [this pr-ref]
    "Start actively managing a PR")

  (pause-all [this reason]
    "Pause all active PR loops")

  (set-concurrency [this n]
    "Set max concurrent PR loops")

  ;; Batch operations
  (merge-ready [this]
    "Merge all approved PRs")

  (review-new [this]
    "Start review on all new PRs")

  ;; Dashboard
  (render-dashboard [this]
    "Render CLI dashboard"))
```

### 5.5 Fleet Events

```clojure
{:fleet-events
 [{:event :pr-opened
   :action :auto-review-if-configured}

  {:event :pr-ci-failed
   :action :queue-for-fix}

  {:event :pr-comments-added
   :action :queue-for-response}

  {:event :pr-approved
   :action :add-to-merge-queue}

  {:event :pr-merged
   :action :notify-and-cleanup}

  {:event :pr-conflict
   :action :attempt-rebase-or-notify}

  {:event :pr-stale
   :condition {:age-days (> 7)}
   :action :notify-owner}]}
```

### 5.6 Fleet CLI Commands

```bash
# Start fleet mode for an org
miniforge fleet start --org acme-corp

# Add a specific repo
miniforge fleet add-repo https://github.com/acme/backend

# Show dashboard (interactive)
miniforge fleet dashboard

# Show status (non-interactive)
miniforge fleet status

# Merge all approved PRs
miniforge fleet merge-ready

# Pause all activity
miniforge fleet pause

# Focus on a specific PR
miniforge fleet focus acme/backend#123
```

---

## 6. Mode Interactions

### 6.1 Full Loop → PR Loop

When a workflow creates a PR, it spawns a PR Loop:

```clojure
{:interaction :workflow-creates-pr
 :flow
 [[:outer-loop :release-phase]
  [:action :create-pr]
  [:spawn :pr-loop {:source-workflow workflow-id}]
  [:outer-loop :wait-for-pr-merge]
  [:pr-loop :runs-until-merged]
  [:outer-loop :continue-to-observe-phase]]}
```

### 6.2 PR Loop → Self-Improvement

When a PR Loop encounters repeated failures:

```clojure
{:interaction :pr-loop-triggers-fix
 :flow
 [[:pr-loop :comment-resolution-failed {:attempts 3}]
  [:operator :pause-pr-loop]
  [:operator :analyze-failure]
  [:operator :spawn-fix-workflow]
  [:fix-workflow :runs-to-completion]
  [:fix-workflow :creates-its-own-pr]
  [:fix-pr-loop :runs-until-merged]
  [:operator :resume-original-pr-loop]]}
```

### 6.3 Fleet Mode → PR Loop

Fleet mode manages multiple PR Loops:

```clojure
{:interaction :fleet-manages-prs
 :flow
 [[:fleet :sync-prs]
  [:fleet :prioritize-queue]
  [:fleet :start-pr-loop {:pr top-priority}]
  [:pr-loop :runs-independently]
  [:fleet :monitors-completion]
  [:fleet :starts-next-pr-loop]]}
```

### 6.4 Single Step → Any Mode

Single steps can be used within any mode:

```clojure
{:interaction :single-step-usage
 :examples
 [{:context :debugging
   :usage "Run diagnose step on failing workflow"}

  {:context :manual-intervention
   :usage "Run implement step for a specific fix"}

  {:context :fleet-mode
   :usage "Run pr-respond step for a specific comment"}]}
```

---

## 7. CLI Command Structure

```bash
miniforge <mode> <command> [options]

# Modes
miniforge run      <spec>              # Full loop (default)
miniforge step     <step-name>         # Single step
miniforge chain    <step,step,...>     # Step chain
miniforge pr       <command>           # PR loop management
miniforge fleet    <command>           # Multi-repo management
miniforge operator <command>           # Operator controls

# Examples

# Full loop
miniforge run ./feature-spec.md
miniforge run --resume workflow-123

# Single steps
miniforge step plan --spec ./feature.md
miniforge step diagnose --error ./failure.log
miniforge step pr-respond --pr org/repo#123

# PR management
miniforge pr start https://github.com/org/repo/pull/123
miniforge pr status
miniforge pr fix        # fix current PR issues
miniforge pr respond    # respond to comments

# Fleet management
miniforge fleet start --org my-org
miniforge fleet dashboard
miniforge fleet status --json
miniforge fleet merge-ready

# Operator controls
miniforge operator status           # show all workflows
miniforge operator pause workflow-123
miniforge operator spawn-fix --from workflow-123
miniforge operator heuristics list
miniforge operator heuristics update prompts.implementer v2.1.0
```

---

## 8. Deliverables

### Phase 0 (Foundations)

- [ ] Mode schemas (PR loop, fleet, single step)
- [ ] CLI command structure
- [ ] Event definitions for mode transitions

### Phase 1 (PR Loop)

- [ ] PR Loop controller
- [ ] Comment resolution engine
- [ ] GitHub API adapter for PR operations
- [ ] Re-opened comment handling

### Phase 2 (Single Step)

- [ ] Step executor
- [ ] Step registry
- [ ] CLI for single steps
- [ ] Step chaining

### Phase 3 (Fleet Mode)

- [ ] Fleet controller
- [ ] Multi-repo PR watcher
- [ ] Priority queue
- [ ] CLI dashboard (TUI)

### Phase 4 (Local Self-Improvement)

- [ ] Operator self-improvement rules
- [ ] Fix workflow spawning
- [ ] Workflow pause/resume with context
- [ ] Local heuristic store

### Phase 5 (Integration)

- [ ] Mode transition handling
- [ ] Cross-mode event bus
- [ ] Unified CLI experience

---

## 9. First Product: Fleet Mode

Fleet Mode is the recommended first product because:

1. **Immediate value**: Devs are drowning in PRs today
2. **Low risk**: Read-mostly, respond-when-asked
3. **High visibility**: Dashboard shows value immediately
4. **Gateway drug**: Once using fleet, natural to try full loop
5. **Generates training data**: PR interactions are learning examples

### MVP Scope

```
Fleet Mode MVP
├── Watch PRs across N repos
├── Show dashboard in CLI
├── Auto-review new PRs (optional)
├── Respond to comments (human-triggered)
├── Merge approved PRs (human-triggered)
└── Daily summary email/Slack
```

### Upgrade Path

```
Fleet Mode → PR Loop (auto-fix) → Single Steps → Full Loop
```

---

## 10. Open Questions

1. **PR permissions**: How to handle repos where we can't push directly?
2. **Review bot conflicts**: How to handle disagreements with other review bots?
3. **Merge strategies**: Squash vs merge commit vs rebase?
4. **Branch protection**: How to handle required reviewers, status checks?
5. **Rate limiting**: How to handle GitHub API rate limits in fleet mode?
