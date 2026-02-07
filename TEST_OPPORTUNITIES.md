# Fast Iteration Test Opportunities

Analysis of components that would benefit from focused integration tests similar to `handoff_test.clj`.

## Criteria for Fast Iteration Tests

1. **Complex data flow** between components
2. **Slow end-to-end testing** (minutes vs seconds)
3. **High bug risk** due to integration complexity
4. **Critical path** - failures block all workflows

## High-Priority Opportunities

### 1. PR Lifecycle Integration Test ⚠️ **NO TESTS EXIST**

**Component:** `components/pr-lifecycle/`
**Current Coverage:** None! (`test/` directory doesn't exist)
**Why Critical:** PR lifecycle orchestrates git operations, CI monitoring, and PR merging - all slow and complex

**Proposed Test:** `pr_lifecycle_integration_test.clj`
- Mock gh CLI calls and git operations
- Test state transitions: pending → ci-running → approved → merged
- Test error handling: CI failures, merge conflicts, rebase needed
- Test event emission to event bus
- **Speed:** ~2-3 seconds vs ~5-10 minutes for real PR lifecycle

**Value:** Would have caught integration issues early during PR lifecycle development

---

### 2. Release Executor File Writing Test

**Component:** `components/release-executor/`
**Current Coverage:** Sandbox tests exist but limited integration coverage
**Why Critical:** File writing bugs (like the one we just fixed) silently fail with zero files written

**Proposed Test:** `release_file_writing_test.clj`
- Mock git operations (branch creation, commit, push)
- Test file writing from code artifacts
- Verify files are staged correctly
- Test metadata generation (commit messages, PR descriptions)
- Test rollback on failure
- **Speed:** ~1-2 seconds vs ~30+ seconds for full release with git operations

**Value:** Would have caught the artifact persistence bug immediately

---

### 3. Agent Response Parsing Test

**Component:** `components/agent/`
**Current Coverage:** Good unit tests, but no integration test for full request/response cycle
**Why Critical:** Agents return structured data that flows through entire system

**Proposed Test:** `agent_response_integration_test.clj`
- Mock LLM responses (success, error, timeout cases)
- Test response parsing and validation
- Test that response structure matches what phases expect
- Test error handling and escalation
- **Speed:** ~1 second vs ~30+ seconds with real LLM calls

**Value:** Catches schema mismatches between agent outputs and phase inputs

---

### 4. Gate Validation Pipeline Test

**Component:** `components/gate/` + `components/loop/`
**Current Coverage:** Good unit tests, limited integration coverage
**Why Critical:** Gates validate artifacts through the inner loop - failures cause repair cycles

**Proposed Test:** `gate_pipeline_test.clj`
- Mock gate implementations (syntax, lint, tests, security)
- Test artifact flow through gate chain
- Test that failures trigger repair correctly
- Test escalation when repairs fail
- Test metrics accumulation through repair cycles
- **Speed:** ~2 seconds vs ~20+ seconds with real linting/testing

**Value:** Validates the repair loop works without running real tools

---

### 5. Metrics Accumulation Test

**Component:** Multiple (cross-cutting concern)
**Current Coverage:** Scattered unit tests, no integration test
**Why Critical:** Metrics must aggregate correctly across phases for cost tracking and budgets

**Proposed Test:** `metrics_accumulation_test.clj`
- Mock phases returning metrics
- Test metrics flow through workflow execution
- Test budget enforcement (token limits, time limits, iteration limits)
- Test metrics merging from parallel phases
- Test metrics in evidence bundles
- **Speed:** ~1 second vs full workflow execution

**Value:** Ensures cost tracking and budget limits work correctly

---

### 6. Evidence Bundle Assembly Test

**Component:** `components/evidence-bundle/`
**Current Coverage:** Schema validation tests exist
**Why Critical:** Evidence bundles must capture complete workflow state for auditability

**Proposed Test:** `evidence_bundle_integration_test.clj`
- Mock workflow execution with phase results
- Test bundle assembly from context
- Test that all required fields are present
- Test bundle export and import
- Test validation catches incomplete bundles
- **Speed:** ~2 seconds vs full workflow + export

**Value:** Ensures audit trail is complete and valid

---

## Medium-Priority Opportunities

### 7. FSM State Transition Test

**Component:** `components/workflow/`
**Current Coverage:** `fsm_test.clj` exists but could be enhanced
**Enhancement:** Add integration test for full state transition chains with rollback

### 8. Event Stream Integration Test

**Component:** `components/event-stream/`
**Current Coverage:** Interface tests exist
**Enhancement:** Test event flow from phases through bus to subscribers

### 9. DAG Parallel Execution Test

**Component:** `components/dag-executor/`
**Current Coverage:** Good tests exist
**Enhancement:** Add test for artifact dependencies in parallel execution

---

## Implementation Priority

1. **PR Lifecycle Integration Test** (no tests exist - highest risk)
2. **Release Executor File Writing Test** (just found bug here)
3. **Agent Response Parsing Test** (critical integration point)
4. **Gate Validation Pipeline Test** (inner loop is core functionality)
5. **Metrics Accumulation Test** (cross-cutting, budget enforcement)

## Test Pattern Template

Based on `handoff_test.clj`, successful fast iteration tests:

1. **Mock external dependencies** (LLM calls, git operations, CLI tools)
2. **Focus on data flow** between components
3. **Test happy path AND error cases**
4. **Use realistic mock data** that matches production structure
5. **Run in seconds not minutes**
6. **Make assertions about integration points** not implementation details

## Estimated Value

Adding these 5-6 tests would:
- **Reduce debug time** from hours to minutes (like the handoff bug)
- **Enable rapid iteration** on integration logic
- **Catch bugs earlier** in development cycle
- **Document integration contracts** between components
- **Improve CI reliability** (faster, more focused tests)

## Next Steps

1. Review this analysis
2. Prioritize 2-3 tests to implement first
3. Use `handoff_test.clj` as template
4. Add tests incrementally as integration issues are discovered
