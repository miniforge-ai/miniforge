# PR1: Add Agent.abort() Protocol Method

**Branch:** `feat/agent-abort-protocol`  
**Date:** 2026-01-24  
**Layer:** Foundations (Protocol Extension)  
**Part of:** N1 Architecture Completion (PR 1 of 8)  
**Depends On:** None  
**Blocks:** PR6 (Subagent implementation)

## Overview

Adds the `abort` method to the `AgentLifecycle` protocol to conform with N1 Architecture Spec §2.3.1.
This enables agents to be gracefully terminated with a reason, which is essential for subagent
management and workflow cancellation.

## Motivation

**N1 Spec Requirement (§2.3.1):**
> All agents MUST implement:
>
> ```clojure
> (defprotocol Agent
>   (abort [agent reason]
>     "Abort agent execution"))
> ```

Currently, agents can be shut down via `shutdown`, but there's no way to abort an in-progress
execution with a specific reason. This is needed for:

- Workflow cancellation
- Timeout handling
- Budget exhaustion
- Subagent termination (PR6 depends on this)

## Changes in Detail

### 1. Protocol Extension

**File:** `components/agent/src/ai/miniforge/agent/interface/protocols/agent.clj`

Add `abort` method to `AgentLifecycle` protocol:

```clojure
(defprotocol AgentLifecycle
  (init [this config])
  (status [this])
  (shutdown [this])
  (abort [this reason]  ; NEW
    "Abort agent execution with reason. 
     Sets status to :aborted and records reason.
     Idempotent - safe to call multiple times.
     Returns {:aborted true :reason reason}"))
```

### 2. Implementation in BaseAgent

**File:** `components/agent/src/ai/miniforge/agent/core.clj`

Implement abort in BaseAgent record:

- Set agent status to `:aborted`
- Record abort reason
- Ensure idempotency (safe to call multiple times)
- Clean up in-progress work

### 3. Backward Compatibility

**File:** `components/agent/src/ai/miniforge/agent/protocol.clj`

Re-export `abort` for backward compatibility:

```clojure
(def abort p/abort)
```

### 4. Tests

**File:** `components/agent/test/ai/miniforge/agent/core_test.clj`

Add test cases:

- `test-agent-abort` - Verify abort sets status correctly
- `test-agent-abort-idempotent` - Multiple abort calls are safe
- `test-agent-abort-during-invoke` - Abort while agent is executing
- `test-agent-abort-cleanup` - Resources are cleaned up

## Testing Plan

```bash
# Run agent component tests
bb test components/agent

# Run full test suite
bb test

# Verify pre-commit
bb pre-commit
```

### Test Coverage

- ✅ Abort sets agent status to `:aborted`
- ✅ Abort records reason
- ✅ Abort is idempotent
- ✅ Abort during execution stops agent
- ✅ Abort cleans up resources
- ✅ Status query after abort returns `:aborted`

## Deployment Plan

This is a non-breaking change (additive API extension):

- Existing code continues to work
- New code can use `abort` when needed
- No migration required

## Related Issues/PRs

**Depends On:**

- None

**Blocks:**

- PR6: Subagent implementation (needs abort for parent → child termination)

**Related:**

- N1 Architecture Spec §2.3.1 (Agent Protocol)
- docs/N1-implementation-status.md (Gap analysis)
- docs/N1-completion-pr-plan.md (PR DAG)

## Checklist

- [ ] Protocol method added to `AgentLifecycle`
- [ ] Implemented in `BaseAgent` record
- [ ] Tests added and passing
- [ ] Pre-commit hooks pass
- [ ] Documentation updated
- [ ] N1 spec conformance verified
- [ ] No breaking changes

## Conformance

This PR achieves **full conformance** with N1 Architecture Spec §2.3.1 for the Agent protocol `abort` method.

**Before:** ❌ Missing `abort` method  
**After:** ✅ `abort` method implemented and tested
