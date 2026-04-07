# N1 Architecture Completion: PR Dependency DAG

**Date:** 2026-01-24  
**Source:** N1-implementation-status.md analysis  
**Strategy:** Bottom-up layered PRs, <400 lines each

---

## PR Dependency Graph

```text
Foundation Layer (Protocols):
  PR1: Agent.abort() ──┐
  PR2: Tool protocol ──┼─► Application Layer:
  PR3: Gate.repair() ──┘     PR5: Tool invocation tracking
                             PR6: Subagent implementation
                             │
                             └─► Integration Layer:
                                   PR7: Conformance tests

Independent (Parallel):
  PR4: Human escalation UX
  PR8: Reproducibility (event replay)
```

**Merge Order:**

1. Foundation (PR1, PR2, PR3) - can merge in parallel
2. Application (PR5, PR6) - depends on foundation
3. Integration (PR7) - depends on everything
4. Independent (PR4, PR8) - merge anytime

---

## PR 1: Add Agent.abort() Protocol Method

**Branch:** `feat/agent-abort-protocol`  
**Layer:** Foundations (Protocol Extension)  
**Size Estimate:** ~150 lines  
**Depends On:** None  
**Blocks:** PR6 (Subagent)

### Changes

**Files Modified:**

- `components/agent/src/ai/miniforge/agent/interface/protocols/agent.clj`
  - Add `abort` method to `AgentLifecycle` protocol
  - Document abort semantics

- `components/agent/src/ai/miniforge/agent/core.clj`
  - Implement abort in BaseAgent record
  - Add abort state tracking

- `components/agent/test/ai/miniforge/agent/core_test.clj`
  - Add abort test cases
  - Test abort during invoke
  - Test abort cleanup

### Acceptance Criteria

- ✅ `abort` method exists in AgentLifecycle protocol
- ✅ All existing agents implement abort
- ✅ Abort sets agent status to `:aborted`
- ✅ Abort is idempotent (safe to call multiple times)
- ✅ Tests verify abort behavior
- ✅ N1 spec §2.3 conformant

### Estimated Impact

- ~3 files modified
- ~150 lines total
- No breaking changes (additive)

---

## PR 2: Align Tool Protocol with N1 Spec

**Branch:** `feat/tool-protocol-alignment`  
**Layer:** Foundations (Protocol Extension)  
**Size Estimate:** ~200 lines  
**Depends On:** None  
**Blocks:** PR5 (Tool tracking)

### Changes

**Files Modified:**

- `components/tool/src/ai/miniforge/tool/core.clj`
  - Add `validate-args` method to Tool protocol
  - Add `get-schema` method to Tool protocol
  - Update FunctionTool record to implement new methods

- `components/tool/src/ai/miniforge/tool/interface.clj`
  - Export new protocol methods

- `components/tool/test/ai/miniforge/tool/interface_test.clj`
  - Add tests for validate-args protocol method
  - Add tests for get-schema protocol method

### Acceptance Criteria

- ✅ Tool protocol has `validate-args` method
- ✅ Tool protocol has `get-schema` method
- ✅ Existing free function `validate-params` refactored to use protocol
- ✅ All existing tools implement new methods
- ✅ Tests verify new protocol methods
- ✅ N1 spec §2.5 conformant

### Estimated Impact

- ~3 files modified
- ~200 lines total
- Breaking change: Tool protocol signature changed (migration path: default implementations)

---

## PR 3: Add Gate.repair() Protocol Method

**Branch:** `feat/gate-repair-protocol`  
**Layer:** Foundations (Protocol Extension)  
**Size Estimate:** ~250 lines  
**Depends On:** None  
**Blocks:** PR7 (Conformance tests)

### Changes

**Files Modified:**

- `components/loop/src/ai/miniforge/loop/interface/protocols/gate.clj`
  - Add `repair` method to Gate protocol
  - Document repair semantics

- `components/gate/src/ai/miniforge/gate/*.clj` (syntax, lint, test, policy)
  - Implement repair in each gate type
  - For gates that can't repair, return `{:repaired? false}`

- `components/loop/src/ai/miniforge/loop/inner.clj`
  - Wire gate repair into inner loop
  - Use gate repair before agent repair

- `components/loop/test/ai/miniforge/loop/gates_test.clj`
  - Add repair test cases
  - Test repair success/failure

### Acceptance Criteria

- ✅ Gate protocol has `repair` method
- ✅ All gate implementations have repair logic
- ✅ Inner loop calls gate repair before agent repair
- ✅ Repair returns `{:repaired? boolean :artifacts [...] :changes [...]}`
- ✅ Tests verify repair behavior
- ✅ N1 spec §2.6 conformant

### Estimated Impact

- ~6 files modified
- ~250 lines total
- No breaking changes (additive with default no-op repair)

---

## PR 4: Human Escalation UX (Independent)

**Branch:** `feat/human-escalation-ux`  
**Layer:** Adapter (CLI/TUI)  
**Size Estimate:** ~300 lines  
**Depends On:** None  
**Blocks:** None (independent enhancement)

### Changes

**Files Created:**

- `components/loop/src/ai/miniforge/loop/escalation.clj`
  - Escalation prompt logic
  - User input handling
  - Hint integration back to agent

**Files Modified:**

- `components/loop/src/ai/miniforge/loop/inner.clj`
  - Call escalation when retry budget exhausted
  - Pass user hints to agent for retry

- `components/loop/test/ai/miniforge/loop/escalation_test.clj`
  - Test escalation prompts
  - Test hint passing

### Acceptance Criteria

- ✅ When retry budget exhausted, user is prompted
- ✅ Prompt shows error context and last attempt
- ✅ User can provide hints or abort
- ✅ Hints are passed to agent on retry
- ✅ Tests verify escalation flow
- ✅ N1 spec §5.3 conformant

### Estimated Impact

- ~4 files (2 new, 2 modified)
- ~300 lines total
- No breaking changes (new feature)

---

## PR 5: Tool Invocation Tracking for Evidence

**Branch:** `feat/tool-invocation-tracking`  
**Layer:** Application (Domain Logic)  
**Size Estimate:** ~350 lines  
**Depends On:** PR2 (Tool protocol)  
**Blocks:** PR7 (Conformance tests)

### Changes

**Files Modified:**

- `components/tool/src/ai/miniforge/tool/core.clj`
  - Add invocation recording wrapper
  - Track timestamp, duration, exit code, errors

- `components/evidence-bundle/src/ai/miniforge/evidence_bundle/collector.clj`
  - Add tool invocation collection
  - Extract tool metrics from execution

- `components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj`
  - Add tool-invocation schema to evidence bundle

- `components/agent/src/ai/miniforge/agent/core.clj`
  - Pass tool invocations to evidence collector

**Files Created:**

- `components/tool/src/ai/miniforge/tool/tracking.clj`
  - Tool invocation record schema
  - Invocation wrapper functions

### Acceptance Criteria

- ✅ All tool invocations are recorded
- ✅ Records include: tool-id, timestamp, duration, args, result, exit-code, error
- ✅ Tool invocations included in evidence bundles
- ✅ Tests verify tracking
- ✅ N1 spec §2.5 conformant

### Estimated Impact

- ~5 files (1 new, 4 modified)
- ~350 lines total
- No breaking changes (additive)

---

## PR 6: Subagent Implementation

**Branch:** `feat/subagent-support`  
**Layer:** Application (Domain Logic)  
**Size Estimate:** ~400 lines  
**Depends On:** PR1 (Agent.abort)  
**Blocks:** PR7 (Conformance tests)

### Changes

**Files Created:**

- `components/agent/src/ai/miniforge/agent/interface/protocols/subagent.clj`
  - Subagent protocol
  - Parent-child relationship tracking

- `components/agent/src/ai/miniforge/agent/subagent.clj`
  - Subagent implementation
  - Parent-child linking
  - Result aggregation
  - Failure propagation

- `components/agent/test/ai/miniforge/agent/subagent_test.clj`
  - Subagent lifecycle tests
  - Parent-child relationship tests
  - Aggregation tests

**Files Modified:**

- `components/agent/src/ai/miniforge/agent/interface.clj`
  - Export subagent functions

- `components/agent/src/ai/miniforge/agent/core.clj`
  - Add subagent spawning support to BaseAgent

- `components/logging/src/ai/miniforge/logging/core.clj`
  - Add subagent lifecycle events

### Acceptance Criteria

- ✅ Subagent protocol defined
- ✅ Subagents linked to parent via IDs
- ✅ Subagent lifecycle events emitted
- ✅ Results aggregated into parent output
- ✅ Failures propagate to parent
- ✅ Tests verify subagent behavior
- ✅ N1 spec §2.4 conformant

### Estimated Impact

- ~6 files (3 new, 3 modified)
- ~400 lines total
- No breaking changes (new feature)

---

## PR 7: N1 Conformance Test Suite

**Branch:** `feat/n1-conformance-tests`  
**Layer:** Integration (Testing)  
**Size Estimate:** ~500 lines (tests don't count toward PR size limit per 720-pr-layering)  
**Depends On:** PR1, PR2, PR3, PR5, PR6 (all foundation + application)  
**Blocks:** None (validation layer)

### Changes

**Files Created:**

- `tests/conformance/n1_architecture_test.clj`
  - End-to-end workflow execution test
  - Event stream completeness test
  - Protocol conformance verification

- `tests/conformance/agent_context_handoff_test.clj`
  - Context passing between phases
  - Context schema validation

- `tests/conformance/gate_enforcement_test.clj`
  - Gate blocks phase completion on failure
  - Gate repair integration

- `tests/conformance/evidence_bundle_test.clj`
  - Evidence bundle generated for all workflows
  - All required fields present

**Files Modified:**

- `deps.edn`
  - Add conformance test alias

- `bb.edn`
  - Add `bb test:conformance` task

### Acceptance Criteria

- ✅ End-to-end test: spec → PR with evidence bundle
- ✅ Event completeness verified
- ✅ Agent context handoff validated
- ✅ Gate enforcement verified
- ✅ All N1 §8.2 requirements met
- ✅ Tests run in CI

### Estimated Impact

- ~5 files (4 new, 1 modified)
- ~500 lines total (tests)
- No breaking changes (new tests)

---

## PR 8: Event Stream Replay for Reproducibility (Independent)

**Branch:** `feat/event-stream-replay`  
**Layer:** Infrastructure (Persistence + Replay)  
**Size Estimate:** ~400 lines  
**Depends On:** None  
**Blocks:** None (independent enhancement)

### Changes

**Files Created:**

- `components/workflow/src/ai/miniforge/workflow/replay.clj`
  - Event stream replay logic
  - State reconstruction from events
  - Determinism verification

- `components/workflow/test/ai/miniforge/workflow/replay_test.clj`
  - Replay tests
  - State reconstruction tests

**Files Modified:**

- `components/workflow/src/ai/miniforge/workflow/persistence.clj`
  - Add event log loading

- `components/workflow/src/ai/miniforge/workflow/interface.clj`
  - Export replay functions

### Acceptance Criteria

- ✅ Can replay event stream to reconstruct state
- ✅ Same events → same final state
- ✅ Tests verify determinism
- ✅ N1 spec §5.2 conformant

### Estimated Impact

- ~4 files (2 new, 2 modified)
- ~400 lines total
- No breaking changes (new feature)

---

## PR Sizing Summary

| PR | Lines | Files | Type | Can Merge After |
|----|-------|-------|------|-----------------|
| PR1 | ~150 | 3 | Protocol | Immediately |
| PR2 | ~200 | 3 | Protocol | Immediately |
| PR3 | ~250 | 6 | Protocol | Immediately |
| PR4 | ~300 | 4 | Feature | Immediately |
| PR5 | ~350 | 5 | Integration | PR2 |
| PR6 | ~400 | 6 | Feature | PR1 |
| PR7 | ~500 | 5 | Tests | PR1, PR2, PR3, PR5, PR6 |
| PR8 | ~400 | 4 | Feature | Immediately |

**Total:** ~2,550 lines across 8 focused PRs

---

## Implementation Timeline

### Phase 1: Foundation (Parallel) - Week 1

- PR1: Agent.abort() ✓
- PR2: Tool protocol ✓
- PR3: Gate.repair() ✓
- PR4: Human escalation ✓ (independent)

### Phase 2: Application (Sequential) - Week 2

- PR5: Tool tracking (after PR2) ✓
- PR6: Subagent (after PR1) ✓
- PR8: Replay (independent) ✓

### Phase 3: Integration (Final) - Week 3

- PR7: Conformance tests (after all) ✓

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Protocol breaking changes | Provide default implementations, migration guide |
| Test coupling | Each PR has unit tests, integration tests in PR7 only |
| Merge conflicts | Small PRs reduce conflict surface, foundation merges first |
| Scope creep | Strict <400 line limit per PR (tests excluded) |

---

## Success Criteria

After all PRs merged:

- ✅ All N1 §2.3-2.6 protocols conformant
- ✅ All N1 §8.2 conformance tests passing
- ✅ Evidence bundles include tool invocations
- ✅ Subagents fully functional
- ✅ Human escalation working
- ✅ Event replay functional

---

**Next Action:** Start with PR1 (Agent.abort) as it's the smallest foundation piece.
