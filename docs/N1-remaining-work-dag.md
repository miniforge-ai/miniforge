# N1 Architecture - Remaining Work DAG

**Date:** 2026-01-25
**Status:** 7/8 Original PRs Merged
**Remaining:** Conformance tests + enhancements

---

## Summary of Completed Work

✅ **PR #77** (PR1): Agent.abort() protocol - **MERGED**
✅ **PR #78** (PR2): Tool protocol alignment - **MERGED**
✅ **PR #79** (PR3): Gate.repair() protocol - **MERGED**
✅ **PR #81** (PR4): Human escalation UX - **MERGED**
✅ **PR #82** (PR5): Tool invocation tracking - **MERGED**
✅ **PR #83** (PR6): Subagent implementation - **MERGED**
✅ **PR #80** (PR8): Event stream replay - **MERGED**

**Total:** 7/8 PRs completed = **~2,050 lines merged**

---

## Remaining Work for N1 Spec Conformance

### 🔴 **CRITICAL** (Blocks N1 Conformance)

#### PR9: N1 Conformance Test Suite

**Branch:** `feat/n1-conformance-tests-v2`
**Size Estimate:** ~600 lines (tests)
**Depends On:** All foundation PRs (already merged)
**Priority:** P0 - CRITICAL

**Status:** Not started

**Files to Create:**

1. `tests/conformance/n1_architecture_test.clj`
   - End-to-end workflow execution test (spec → PR with evidence)
   - Test complete workflow lifecycle
   - Verify evidence bundle generation
   - ~200 lines

2. `tests/conformance/event_stream_test.clj`
   - Event stream completeness verification
   - Test all required events are emitted
   - Test event ordering and causality
   - ~150 lines

3. `tests/conformance/agent_context_handoff_test.clj`
   - Context passing between phases
   - Context schema validation
   - Test knowledge/policy context inclusion
   - ~100 lines

4. `tests/conformance/protocol_conformance_test.clj`
   - Verify all protocols implemented correctly
   - Test Agent.abort(), Tool.validate-args/get-schema, Gate.repair()
   - ~100 lines

5. `tests/conformance/gate_enforcement_test.clj`
   - Gate blocks phase completion on failure
   - Gate repair integration
   - Inner loop validation
   - ~50 lines

**Files to Modify:**

- `deps.edn` - Add conformance test alias
- `bb.edn` - Add `bb test:conformance` task

**Acceptance Criteria:**

- ✅ All N1 §8.2 requirements verified
- ✅ End-to-end test: YAML spec → PR with evidence bundle
- ✅ All required events verified present
- ✅ Agent context handoff validated
- ✅ Protocol methods tested
- ✅ Gate enforcement verified
- ✅ Tests run in CI
- ✅ All tests pass

---

### 🟡 **IMPORTANT** (Spec Compliance Enhancement)

#### PR10: LLM Response Caching for Offline Mode

**Branch:** `feat/llm-response-cache`
**Size Estimate:** ~300 lines
**Depends On:** None (independent)
**Priority:** P1 - Important

**Status:** Not started

**Changes:**

1. Create `components/llm/src/ai/miniforge/llm/cache.clj`
   - Response cache implementation
   - Cache key generation (hash of prompt + model + params)
   - Cache storage (EDN files in `~/.miniforge/llm-cache/`)
   - ~150 lines

2. Modify `components/llm/src/ai/miniforge/llm/client.clj`
   - Integrate cache layer
   - Check cache before API call
   - Store successful responses
   - ~50 lines

3. Create `components/llm/test/ai/miniforge/llm/cache_test.clj`
   - Cache hit/miss tests
   - Cache invalidation tests
   - ~100 lines

**Acceptance Criteria:**

- ✅ LLM responses cached locally
- ✅ Cached responses used when available
- ✅ Offline execution works with cache
- ✅ N1 spec §5.1.3 (offline capability) conformant

---

### 🟢 **NICE-TO-HAVE** (Enhancement - Not Blocking N1)

#### PR11: Interoperability Test Suite

**Branch:** `feat/interoperability-tests`
**Size Estimate:** ~400 lines (tests)
**Depends On:** None
**Priority:** P2 - Enhancement

**Status:** Not started

**Files to Create:**

1. `tests/interoperability/knowledge_base_portability_test.clj`
   - Test knowledge base export
   - Test knowledge base import
   - Test cross-instance compatibility
   - ~150 lines

2. `tests/interoperability/evidence_bundle_portability_test.clj`
   - Test evidence bundle reading from different instances
   - Test bundle format compatibility
   - ~100 lines

3. `tests/interoperability/policy_pack_compatibility_test.clj`
   - Test loading community policy packs
   - Test policy pack versioning
   - ~100 lines

4. `tests/interoperability/component_isolation_test.clj`
   - Verify Polylith component independence
   - Test component boundaries
   - ~50 lines

**Acceptance Criteria:**

- ✅ All N1 §8.3 requirements verified
- ✅ Knowledge base export/import works
- ✅ Evidence bundles portable
- ✅ Policy packs compatible

---

#### PR12: Meta Loop Enhancement

**Branch:** `feat/meta-loop-enhancement`
**Size Estimate:** ~500 lines
**Depends On:** None
**Priority:** P2 - Enhancement

**Status:** Not started (design needed)

**Changes:**

1. Enhance `components/observer/src/ai/miniforge/observer/pattern_extractor.clj`
   - Sophisticated pattern extraction from workflows
   - Pattern clustering and categorization
   - ~200 lines

2. Create `components/heuristic/src/ai/miniforge/heuristic/evolution.clj`
   - Automatic heuristic evolution based on performance
   - Heuristic A/B testing framework
   - Performance metrics collection
   - ~200 lines

3. Modify `components/operator/src/ai/miniforge/operator/meta_loop.clj`
   - Integrate pattern extraction
   - Integrate heuristic evolution
   - ~100 lines

**Acceptance Criteria:**

- ✅ Patterns automatically extracted from successful workflows
- ✅ Heuristics evolve based on performance data
- ✅ Performance metrics tracked
- ✅ A/B testing framework functional

---

#### PR13: Parallel Tool Invocation

**Branch:** `feat/parallel-tool-execution`
**Size Estimate:** ~350 lines
**Depends On:** None
**Priority:** P3 - Enhancement

**Status:** Not started (design needed)

**Changes:**

1. Modify `components/agent/src/ai/miniforge/agent/core.clj`
   - Add parallel tool execution capability
   - Safe parallelization (only for read-only tools)
   - Resource management
   - ~200 lines

2. Create `components/tool/src/ai/miniforge/tool/parallel.clj`
   - Parallel execution coordinator
   - Tool safety analysis (read-only vs. side-effects)
   - ~150 lines

**Acceptance Criteria:**

- ✅ Read-only tools can execute in parallel
- ✅ Side-effect tools remain sequential
- ✅ Resource limits enforced
- ✅ Performance improvement measured

---

## PR Dependency DAG

```text
Foundation (All MERGED):
  ✅ PR1: Agent.abort()
  ✅ PR2: Tool protocol
  ✅ PR3: Gate.repair()
  ✅ PR4: Human escalation
  ✅ PR5: Tool tracking
  ✅ PR6: Subagent
  ✅ PR8: Event replay

Remaining Work:

🔴 CRITICAL (Sequential):
  PR9: Conformance tests ───► [Blocks N1 1.0 release]

🟡 IMPORTANT (Can work in parallel):
  PR10: LLM cache ──┐
                    ├──► [All independent, can merge anytime]
🟢 ENHANCEMENTS:    │
  PR11: Interop ────┤
  PR12: Meta loop ──┤
  PR13: Parallel ───┘
```

**Merge Strategy:**

1. **Week 1:** PR9 (Conformance tests) - MUST complete for N1 conformance
2. **Week 2:** PR10 (LLM cache) - Important for offline mode
3. **Week 3+:** PR11-13 (Enhancements) - Nice-to-have, can be done later

---

## Implementation Plan

### Phase 1: N1 Conformance (CRITICAL) - 1 week

**Goal:** Complete N1 spec conformance

**Work Items:**

- PR9: Conformance test suite (~600 lines)

**Agents Needed:** 1 agent (sequential work)

**Success Criteria:**

- All N1 §8.2 conformance tests pass
- N1 spec fully conformant
- Ready for N1 1.0 release

---

### Phase 2: Offline Mode (IMPORTANT) - 1 week

**Goal:** Enable offline execution

**Work Items:**

- PR10: LLM response caching (~300 lines)

**Agents Needed:** 1 agent (independent)

**Success Criteria:**

- Offline execution works with cache
- N1 §5.1.3 conformant

---

### Phase 3: Enhancements (NICE-TO-HAVE) - 2-3 weeks

**Goal:** Improve system capabilities

**Work Items:**

- PR11: Interoperability tests (~400 lines)
- PR12: Meta loop enhancement (~500 lines)
- PR13: Parallel tool execution (~350 lines)

**Agents Needed:** 3 agents (all independent, can work in parallel)

**Success Criteria:**

- All N1 §8.3 requirements met
- Meta loop produces better heuristics
- Performance improvements measured

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Conformance tests reveal implementation gaps | Medium | High | Fix gaps before merging tests |
| Test complexity exceeds estimate | Medium | Medium | Break into smaller test files |
| Offline cache design complexity | Low | Medium | Start with simple file-based cache |
| Meta loop enhancement scope creep | High | Low | Defer to post-N1 if needed |

---

## Success Metrics

### For N1 1.0 Release

- ✅ All 8 original PRs merged (DONE)
- ✅ PR9 conformance tests pass (TODO)
- ✅ All N1 §8.2 requirements verified (TODO)
- ✅ Zero conformance test failures (TODO)

### For N1 1.1 Release

- ✅ PR10 LLM cache merged
- ✅ Offline mode functional
- ✅ All N1 §5.1.3 requirements met

### For N1 2.0 Release

- ✅ PR11-13 enhancements merged
- ✅ All N1 §8.3 requirements met
- ✅ Meta loop producing measurable improvements

---

## Next Actions

### Immediate (This Session)

1. ✅ Create this remaining work DAG
2. ⏳ Create PR9 branch and worktree
3. ⏳ Spin up agent to implement conformance tests
4. ⏳ Review and merge PR9

### Next Session

1. Start PR10 (LLM cache)
2. Plan PR11-13 enhancements

---

**Prepared by:** Claude Sonnet 4.5
**Date:** 2026-01-25
**Next Review:** After PR9 merged
