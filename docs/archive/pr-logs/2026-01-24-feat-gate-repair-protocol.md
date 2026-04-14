<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR3: Add Gate.repair() Protocol Method

**Branch:** `feat/gate-repair-protocol`  
**Date:** 2026-01-24  
**Layer:** Foundations (Protocol Extension)  
**Part of:** N1 Architecture Completion (PR 3 of 8)  
**Depends On:** None  
**Blocks:** PR7 (Conformance tests)

## Overview

Adds the `repair` method to the Gate protocol to conform with N1 Architecture Spec §2.6.1.
This enables gates to attempt automatic remediation of validation violations, which is essential
for the inner loop's validate-repair cycle.

## Motivation

**N1 Spec Requirement (§2.6.1):**

The spec requires gates to implement a repair method that attempts to fix violations. Currently,
gates can only check artifacts, but cannot repair them. This limits the inner loop's ability
to automatically fix issues.

This is needed for:

- Automatic lint error fixes
- Test failure repairs
- Policy violation remediation
- Inner loop integration (validate → repair cycle)

## Changes in Detail

### 1. Protocol Extension

**File:** `components/loop/src/ai/miniforge/loop/interface/protocols/gate.clj`

Add repair method to Gate protocol.

### 2. Implement in All Gates

**Files:**

- `components/gate/src/ai/miniforge/gate/syntax.clj`
- `components/gate/src/ai/miniforge/gate/lint.clj`
- `components/gate/src/ai/miniforge/gate/test.clj`
- `components/gate/src/ai/miniforge/gate/policy.clj`

Add repair implementations:

- Syntax gate: Cannot auto-repair, returns `{:repaired? false}`
- Lint gate: Can attempt auto-fix, returns repaired artifact
- Test gate: Cannot auto-repair, returns `{:repaired? false}`
- Policy gate: May suggest fixes, returns remediation guidance

### 3. Wire into Inner Loop

**File:** `components/loop/src/ai/miniforge/loop/inner.clj`

Update inner loop to call gate repair before agent repair.

### 4. Tests

Add test cases for gate repair behavior and inner loop integration.

## Testing Plan

```bash
bb test components/loop components/gate
bb test
bb pre-commit
```

### Test Coverage

- ✅ Gate protocol has repair method
- ✅ All gates implement repair
- ✅ Inner loop calls gate repair
- ✅ Repair results are properly structured
- ✅ Non-repairable gates return false gracefully

## Deployment Plan

This is an **additive change** with default implementations:

- Gates that can't repair return `{:repaired? false}`
- Existing code continues to work
- Inner loop gains automatic repair capability

## Related Issues/PRs

**Depends On:**

- None

**Blocks:**

- PR7: Conformance tests

**Related:**

- N1 Architecture Spec §2.6.1 (Gate Protocol)
- N1 Architecture Spec §7.2 (Inner Loop)
- docs/N1-implementation-status.md
- docs/N1-completion-pr-plan.md

## Checklist

- [ ] Protocol method added to Gate
- [ ] All gate implementations have repair
- [ ] Inner loop wired to call repair
- [ ] Tests added and passing
- [ ] Pre-commit hooks pass
- [ ] N1 spec conformance verified

## Conformance

This PR achieves **full conformance** with N1 Architecture Spec §2.6.1 for the Gate protocol.

**Before:** ❌ Missing repair method  
**After:** ✅ Full protocol conformance with repair
