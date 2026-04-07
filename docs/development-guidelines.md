<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Development Guidelines

> These guidelines apply to the Miniforge monorepo, which houses three products: **MiniForge Core** (the governed
workflow engine), **Miniforge** (the autonomous SDLC product), and **Data Foundry** (the generic ETL product).

**Purpose:** This document defines development practices for miniforge contributors to avoid
common anti-patterns that degrade code quality and team velocity.

## Table of Contents

- [Core Principles](#core-principles)
- [Function Design](#function-design)
- [Testing Discipline](#testing-discipline)
- [Pre-commit Validation](#pre-commit-validation)
- [Handling Existing Test Failures](#handling-existing-test-failures)
- [Code Review Standards](#code-review-standards)

---

## Core Principles

### Self-Validation First

Miniforge is a self-validating system. We practice what we preach:

- If the code can't validate itself, it shouldn't validate others
- Pre-commit hooks are not optional
- Failing tests are blocking issues, not warnings

### Incremental Improvement

Every change should leave the codebase better than you found it:

- Fix broken tests you encounter
- Refactor complex functions you touch
- Add missing documentation for code you modify

---

## Function Design

### ❌ DO NOT: Create Giant Functions

**Bad Example:**

```clojure
(defn process-workflow [workflow input context]
  ;; 200 lines of mixed concerns:
  ;; - validation
  ;; - state management
  ;; - phase execution
  ;; - error handling
  ;; - metrics collection
  ;; - artifact generation
  (let [validated (if (valid? workflow)
                    workflow
                    (throw (ex-info "Invalid" {})))
        state (create-state validated input)
        ;; ... 190 more lines
        ]))
```

**Why This Is Bad:**

- Impossible to test individual behaviors in isolation
- Cannot reuse validation logic elsewhere
- Difficult to understand what the function does
- Changes in one concern break unrelated tests
- Hard to optimize specific steps

### ✅ DO: Create Small Atomic Composable Functions

**Good Example:**

```clojure
;; Layer 0: Validation
(defn validate-workflow [workflow]
  (if (valid-schema? workflow)
    {:valid? true :workflow workflow}
    {:valid? false :errors (explain-errors workflow)}))

;; Layer 1: State Management
(defn create-execution-state [workflow input]
  {:execution/id (random-uuid)
   :execution/workflow-id (:workflow/id workflow)
   :execution/input input
   :execution/status :pending})

;; Layer 2: Phase Execution
(defn execute-phase [phase state context]
  (let [agent (create-agent-for-phase phase context)
        result (invoke-agent agent state)]
    (record-phase-result state (:phase/id phase) result)))

;; Layer 3: Orchestration
(defn process-workflow [workflow input context]
  (let [{:keys [valid? errors]} (validate-workflow workflow)]
    (if-not valid?
      {:status :error :errors errors}
      (-> (create-execution-state workflow input)
          (execute-phases (:workflow/phases workflow) context)
          (finalize-execution)))))
```

**Why This Is Good:**

- Each function has single responsibility
- Easy to test validation separately from execution
- Can reuse `validate-workflow` in other contexts
- Clear progression from simple to complex (layers)
- Changes to validation don't affect execution tests

**Guidelines:**

- **Target:** Functions should be 5-15 lines (excluding docstrings)
- **Maximum:** 30 lines before mandatory refactoring
- **Layering:** Build complexity through composition, not monoliths
  - Layer 0: Pure data transformations
  - Layer 1: Basic operations (create, validate, transform)
  - Layer 2: Workflow steps (execute, process, handle)
  - Layer 3: Orchestration (compose Layer 2 into full flow)
- **Testing:** If you can't easily test a function, it's too complex

---

## Testing Discipline

### ❌ DO NOT: Comment Out Tests

**Bad Example:**

```clojure
;; (deftest test-workflow-validation
;;   (testing "Validate workflow schema"
;;     (let [workflow {...}]
;;       (is (valid? workflow)))))

;; Commented out because it's failing - TODO fix later
```

**Why This Is Bad:**

- "TODO fix later" never happens
- Code coverage metrics lie
- Regressions go undetected
- We lose track of original intent
- Creates technical debt with no tracking

### ✅ DO: Understand Intent, Then Fix or Remove

**Decision Tree:**

```text
Test is failing
    │
    ├─→ Is the tested behavior still required?
    │       │
    │       ├─→ YES: Fix the test
    │       │       │
    │       │       ├─→ In current PR (if related to your changes)
    │       │       └─→ In separate PR (if unrelated to your changes)
    │       │
    │       └─→ NO: Was the code/feature removed?
    │               │
    │               ├─→ YES: Remove the test (with commit message explaining why)
    │               └─→ NO: Update test to match new behavior/API
    │
    └─→ Is the test testing the right thing?
            │
            ├─→ YES: Keep and fix
            └─→ NO: Refactor test to match actual intent
```

**Good Examples:**

#### Scenario 1: Test for removed feature

```clojure
;; REMOVE THIS:
(deftest test-old-validation-api
  (testing "Old validation function"
    (is (= :valid (validate-old workflow)))))

;; Commit message:
;; "test: remove test for deprecated validate-old function
;;
;;  The validate-old function was removed in PR #123 and replaced
;;  with validate-workflow. This test is no longer relevant."
```

#### Scenario 2: Test intent still valid, needs updating

```clojure
;; OLD (failing):
(deftest test-workflow-validation
  (testing "Validate workflow schema"
    (let [workflow {:workflow/phases [...]}]
      (is (= :valid (validate-old workflow))))))

;; NEW (fixed):
(deftest test-workflow-validation
  (testing "Validate workflow schema"
    (let [workflow {:workflow/phases [...]}]
      (is (:valid? (validate-workflow workflow)))
      (is (empty? (:errors (validate-workflow workflow)))))))

;; Commit message:
;; "test: update validation test for new API
;;
;;  Updated to use validate-workflow which returns {:valid? bool :errors [...]}
;;  instead of validate-old which returned :valid/:invalid keywords."
```

#### Scenario 3: Unrelated test failure discovered

```bash
# Create separate branch from main
git checkout main
git pull
git checkout -b fix/broken-validator-tests

# Fix the tests
# ... make changes ...

# Commit and PR
git commit -m "fix: repair broken validator tests

Found these tests failing in PR #65. They test workflow schema
validation which is still required functionality.

Root cause: Tests used old :phase/agent-type format instead of
new :phase/agent format introduced in PR #59."

gh pr create --title "fix: repair broken validator tests" \
             --body "..."

# After PR #66 merges, rebase your original branch
git checkout feature/your-feature
git rebase main
# Continue with your work
```

**Guidelines:**

- Never comment out tests without understanding why they exist
- Read the test - what behavior does it verify?
- Check git history - when was it written, what was the context?
- Ask: "Is this behavior still part of our system?"
- If removing: Document WHY in commit message
- If fixing: Create separate PR if unrelated to your changes

---

## Pre-commit Validation

### ❌ DO NOT: Skip Pre-commit Hooks

**Bad Examples:**

```bash
# NEVER DO THIS:
git commit --no-verify -m "quick fix"

# OR THIS:
git config core.hooksPath /dev/null

# OR THIS:
mv .git/hooks/pre-commit .git/hooks/pre-commit.disabled
```

**Why This Is Bad:**

- Broken code reaches `main` branch
- CI catches it (wastes time, blocks others)
- Creates "broken window" effect (others follow suit)
- Pre-commit exists to prevent exactly this scenario
- We built Policy component - use it!

### ✅ DO: Fix the Warnings/Errors

**When Linting Fails:**

```bash
$ git commit -m "feat: add new feature"
🔍 Linting...
❌ 3 errors, 2 warnings

# Option 1: Auto-fix
$ bb lint:fix

# Option 2: Manual fix
# Fix the reported issues in your editor

# Then commit again
$ git commit -m "feat: add new feature"
✅ All checks passed
```

**When Tests Fail:**

```bash
$ git commit -m "feat: add new feature"
🧪 Running tests...
❌ 3 failures

# Read the failures - are they related to your changes?

# Scenario A: Your changes broke existing tests
# Fix your code to not break existing behavior
# OR update tests if behavior change is intentional

# Scenario B: Tests were already broken
# See "Handling Existing Test Failures" section below

# Scenario C: Flaky test
# Investigate and fix the flakiness
# Document what made it flaky in commit message
```

**Guidelines:**

- Pre-commit hooks are not suggestions
- If check is broken, fix the check, don't disable it
- "Just this once" becomes "just every time"
- CI time is expensive - catch issues locally

---

## Handling Existing Test Failures

### ❌ DO NOT: Ignore Because "Not My Fault"

**Bad Response:**

```text
You: "Tests are failing but they're not related to my PR"
AI: "Should I skip the pre-commit hook?"
You: "Yes, go ahead" ← WRONG
```

**Why This Is Bad:**

- You inherit the broken test
- Next developer inherits it from you
- Soon 50% of tests are "someone else's problem"
- Codebase becomes unmaintainable

### ✅ DO: Fix in Separate PR, Then Merge

**Correct Process:**

#### Step 1: Discover the Issue

```bash
# Working on feature branch
$ git checkout feature/add-workflow-runner
$ git commit -m "feat: add workflow runner"

🧪 Running tests...
❌ 13 failures in validator_test.clj
# These failures are unrelated to workflow runner
```

#### Step 2: Create Fix Branch from Main

```bash
# Stash your work
$ git stash

# Create fix branch from clean main
$ git checkout main
$ git pull origin main
$ git checkout -b fix/validator-test-failures

# Verify tests fail on main too
$ clojure -M:poly test
# Yep, they fail here too - not your fault
```

#### Step 3: Fix the Tests

```bash
# Fix the failures
# ... edit validator_test.clj ...

# Verify all tests pass
$ clojure -M:poly test
✅ 1834 passes, 0 failures

# Commit
$ git commit -m "fix: update validator tests to v1 workflow format

These tests were failing due to using old :phase/agent-type format.
Updated to new :phase/agent format introduced in #59.

Fixes #123"
```

#### Step 4: Create PR and Get It Merged

```bash
$ git push -u origin fix/validator-test-failures
$ gh pr create --title "fix: update validator tests to v1 workflow format" \
               --body "Found these failing while working on #64.

Root cause: Tests used old phase format.

Tests fixed: 13 in validator_test.clj
All tests now passing: 1834 passes, 0 failures"

# Wait for approval and merge
# (or ask reviewer to prioritize since it's blocking your other PR)
```

#### Step 5: Rebase Your Original Work

```bash
# After fix PR merges
$ git checkout feature/add-workflow-runner
$ git stash pop  # Restore your work

# Rebase on latest main (includes the fix)
$ git rebase origin/main

# Now commit your work
$ git commit -m "feat: add workflow runner"
✅ All checks passed  # Because main is clean now

$ git push
```

**Guidelines:**

- Broken tests on `main` are P0 bugs - fix immediately
- Don't mix test fixes with feature work
- Small focused PRs for test fixes get merged faster
- Document what was broken and why in commit message
- Link to issue if one exists, create one if not

---

## Code Review Standards

### For Authors

**Before Requesting Review:**

- [ ] All tests pass locally
- [ ] Pre-commit validation passes
- [ ] No commented-out code (remove or uncomment)
- [ ] No TODO comments without linked issues
- [ ] Functions are appropriately sized
- [ ] New behavior has tests

**PR Description Should Include:**

- What: What does this PR do?
- Why: Why is this change needed?
- How: How does it work? (if not obvious)
- Test Results: Link to CI run or paste test output
- Breaking Changes: Any API changes?

### For Reviewers

**Check For:**

- [ ] Are functions small and composable?
- [ ] Are tests present for new behavior?
- [ ] Are any tests commented out or skipped?
- [ ] Does pre-commit validation pass?
- [ ] Is the PR focused or does it mix concerns?

**Anti-patterns to Flag:**

- Giant functions (>30 lines)
- Commented-out tests
- `git commit --no-verify` in commit messages
- "Fix later" comments
- Unrelated changes in same PR

---

## Examples: Good vs Bad PRs

### ❌ Bad PR: Mixed Concerns + Skipped Tests

```text
Title: "Add workflow runner and fix some tests"

Changes:
- bases/cli/src/workflow_runner.clj (new, 500 lines)
- components/workflow/test/validator_test.clj (500 changes)
- components/agent/test/reviewer_test.clj (100 changes)
- .git/hooks/pre-commit (disabled)

Description:
"Added workflow runner. Also fixed some broken tests I found.
Had to skip pre-commit because some tests were failing."
```

**Problems:**

- Mixes feature work with test fixes
- Giant 500-line file (not composable)
- Disabled pre-commit hooks
- Vague description
- Tests still failing

### ✅ Good PR: Focused Feature

```text
Title: "feat(cli): add Babashka-compatible workflow runner"

Changes:
- bases/cli/src/ai/miniforge/cli/workflow_runner.clj (324 lines, well-layered)
- bases/cli/src/ai/miniforge/cli/main.clj (added workflow commands)
- bases/cli/test/ai/miniforge/cli/workflow_runner_test.clj (tests)

Description:
"Add CLI workflow execution with real-time progress display.

## What
- workflow list - List available workflows
- workflow run <id> - Execute with progress

## Why
Needed for instant-startup CLI workflow execution.

## How
- Lazy-loads workflow component via requiring-resolve
- Displays phase progress with callbacks
- Supports pretty and JSON output modes

## Test Results
All tests passing: 324 lines added, 100% coverage
Pre-commit validation: ✅ passed

## Performance
- Startup: 40ms (Babashka) vs 1478ms (JVM)
- 37x faster"
```

**Why This Is Good:**

- Single focused feature
- Functions properly sized (see file structure)
- Tests included
- Clear documentation
- All validations pass

### ✅ Good PR: Test Fixes

```text
Title: "fix: update validator tests to v1 workflow format"

Changes:
- components/workflow/test/ai/miniforge/workflow/validator_test.clj

Description:
"Fix 13 failing validator tests discovered in #64.

## Root Cause
Tests used old :phase/agent-type format instead of new
:phase/agent format introduced in #59.

## Changes
- Updated all test fixtures to v1 format
- Added missing required fields (:workflow/name, etc.)
- Updated transition format: :phase/on-success → :phase/next

## Test Results
Before: 13 failures
After: 1834 passes, 0 failures

## Why Separate PR
These failures existed on main, unrelated to #64.
Fixing separately to unblock that PR."
```

**Why This Is Good:**

- Separate from feature work
- Clear root cause analysis
- Focused on one problem
- Unblocks other work

---

## Enforcement

These guidelines are enforced through:

1. **Pre-commit Hooks**
   - Linting (clj-kondo)
   - Formatting (cljfmt for Clojure, markdownlint for docs)
   - Tests (full suite via `poly test`)

2. **Code Review**
   - Reviewers will flag violations
   - PRs may be rejected if guidelines ignored

3. **CI Validation**
   - GitHub Actions run same checks
   - Cannot merge with failing checks

4. **Documentation**
   - This file is canonical source
   - Reference in PR template
   - Link in CONTRIBUTING.md

---

## Questions?

- **Q: What if pre-commit takes too long?**
  - A: Run `bb test` before committing to catch issues early
  - A: Consider if your PR is too large (split it up)

- **Q: What if I really need to skip validation?**
  - A: You don't. Fix the issues instead.
  - A: If emergency, document in commit message with `[EMERGENCY: reason]`

- **Q: What if I find 100 broken tests?**
  - A: Create an issue documenting all failures
  - A: Fix in batches (separate PRs)
  - A: Prioritize based on component importance

- **Q: How do I know if a function is too large?**
  - A: If you can't explain what it does in one sentence
  - A: If it has more than 3 levels of nesting
  - A: If it mixes multiple concerns (validation + execution + formatting)
  - A: If testing it requires mocking more than 2 dependencies

---

## See Also

- [Architecture Decision Records](./adr/)
- [Testing Strategy](./testing-strategy.md)
- [Contributing Guide](../CONTRIBUTING.md)
- [Code Review Checklist](.github/pull_request_template.md)
