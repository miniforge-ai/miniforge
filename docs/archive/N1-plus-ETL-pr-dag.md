<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N1 + ETL Architecture - Complete PR DAG

**Date:** 2026-01-25
**Status:** N1 Complete (PR #86 pending merge), ETL Enhancements Identified
**Spec Updates:** 6 normative specs enhanced with ETL & trust requirements

---

## Executive Summary

### Completed (N1 Core)

✅ **PR #77-83, #86**: All 8 original N1 PRs complete (~2,600 lines)

- Foundation protocols (Agent.abort, Tool, Gate.repair)
- Application layer (Tool tracking, Subagent, Human escalation)
- Infrastructure (Event replay)
- **Conformance tests (PR #86)** ← Pending merge

### New Requirements (ETL & Trust)

📋 **PR #84 Merged**: Spec enhancements (~500 lines of requirements)

- Transitive trust rules
- Pack dependency validation
- ETL lifecycle events
- Incremental ETL processing
- Enhanced prompt injection detection

---

## Updated PR Dependency DAG

```text
COMPLETED (N1 Core):
  ✅ PR #77 (PR1): Agent.abort()
  ✅ PR #78 (PR2): Tool protocol alignment
  ✅ PR #79 (PR3): Gate.repair()
  ✅ PR #81 (PR4): Human escalation UX
  ✅ PR #82 (PR5): Tool invocation tracking
  ✅ PR #83 (PR6): Subagent implementation
  ✅ PR #80 (PR8): Event stream replay
  ⏳ PR #86 (PR9): N1 conformance tests (pending merge)

NEW WORK (ETL & Trust):

🔴 CRITICAL (OSS v1 - Must Complete):
  PR14: Transitive trust rules ──────┐
  PR15: Pack dependency validation ──┼──► Blocks OSS v1.0 release
  PR16: ETL lifecycle events ────────┤
  PR17: Promotion justification ─────┘

🟡 IMPORTANT (OSS v1.1 - Should Complete):
  PR18: Incremental ETL ─────────┐
  PR19: ETL dry-run CLI ─────────┼──► OSS v1.1 features
  PR20: Enhanced PI scanner ─────┤
  PR21: Trust-verified subchannel┘

🟢 ENHANCEMENTS (OSS v1.x - Nice-to-Have):
  PR10: LLM cache (offline mode)
  PR11: Interoperability tests
  PR12: Meta loop enhancement
  PR13: Parallel tool execution
```

**Dependencies:**

- PR14-17 can run in **parallel** (all independent)
- PR18-21 can run in **parallel** (all independent)
- PR10-13 can run in **parallel** (all independent)

---

## New PRs: ETL & Trust System (PR14-21)

### 🔴 PR14: Implement Transitive Trust Rules

**Branch:** `feat/transitive-trust-rules`
**Priority:** P0 - CRITICAL
**Size Estimate:** ~350 lines
**Depends On:** None
**Blocks:** OSS v1.0 release

#### Scope (N1 §2.10.2)

Implements the four transitive trust rules to prevent trust escalation attacks:

1. **Instruction authority is not transitive**
   - Pack A (trusted, instruction) refs Pack B (untrusted) → B stays data-only
2. **Trust level inheritance**
   - Combined content gets lowest trust level (tainted < untrusted < trusted)
3. **Cross-trust references**
   - Track and validate transitive trust graph before execution
4. **Tainted isolation**
   - Tainted content MUST NOT be used for instruction, even transitively

#### Files to Modify

1. `components/knowledge/src/ai/miniforge/knowledge/trust.clj` (~100 lines)
   - Implement trust rule validation
   - Add transitive trust graph builder
   - Add trust level inheritance logic

2. `components/knowledge/src/ai/miniforge/knowledge/pack_loader.clj` (~100 lines)
   - Validate trust rules before pack loading
   - Reject packs violating transitive trust
   - Track cross-trust references

3. `components/knowledge/test/ai/miniforge/knowledge/trust_test.clj` (~150 lines)
   - Test all 4 transitive trust rules
   - Test trust escalation prevention
   - Test tainted isolation

#### Acceptance Criteria

- ✅ All 4 transitive trust rules enforced
- ✅ Trusted pack cannot transitively grant instruction authority to untrusted pack
- ✅ Trust level inheritance uses lowest trust in chain
- ✅ Tainted content isolated from instruction authority
- ✅ Tests verify all rules

---

### 🔴 PR15: Implement Pack Dependency Validation

**Branch:** `feat/pack-dependency-validation`
**Priority:** P0 - CRITICAL
**Size Estimate:** ~400 lines
**Depends On:** None
**Blocks:** OSS v1.0 release

#### Scope (N4 §2.4.2)

Implements comprehensive pack dependency validation in the `knowledge-safety` policy pack:

1. **Circular dependency detection** (A → B → A)
2. **Missing dependency detection**
3. **Version conflict resolution**
4. **Trust level constraint enforcement** (untrusted cannot require trusted)
5. **Dependency depth limit** (default: 5 levels)
6. **Complete dependency graph validation** before loading

#### Files to Create

1. `components/policy-pack/src/ai/miniforge/policy_pack/rules/pack_dependency_validation.clj` (~200 lines)
   - Build dependency graph
   - Detect circular dependencies
   - Validate version constraints
   - Enforce trust constraints
   - Check depth limits

#### Files to Modify

1. `components/policy-pack/src/ai/miniforge/policy_pack/knowledge_safety.clj` (~50 lines)
   - Add `pack-dependency-validation` rule to knowledge-safety pack

2. `components/knowledge/src/ai/miniforge/knowledge/pack_loader.clj` (~50 lines)
   - Run dependency validation before loading
   - Reject packs with dependency violations

3. `components/policy-pack/test/ai/miniforge/policy_pack/rules/pack_dependency_validation_test.clj` (~100 lines)
   - Test circular dependency detection
   - Test missing dependencies
   - Test version conflicts
   - Test trust constraints
   - Test depth limits

#### Acceptance Criteria

- ✅ Circular dependencies detected and rejected
- ✅ Missing dependencies detected
- ✅ Version conflicts reported
- ✅ Trust constraints enforced (untrusted → trusted = FAIL)
- ✅ Depth limit enforced (default 5)
- ✅ Complete graph built before loading
- ✅ Tests cover all validation rules

---

### 🔴 PR16: Add ETL Lifecycle Events

**Branch:** `feat/etl-lifecycle-events`
**Priority:** P0 - CRITICAL
**Size Estimate:** ~200 lines
**Depends On:** None
**Blocks:** OSS v1.0 release

#### Scope (N3 §3.4)

Adds two new ETL lifecycle events to the event stream:

1. **`etl/completed`** - Emitted after successful ETL workflow
   - Duration, summary stats (packs generated/promoted, risk findings, sources processed)
2. **`etl/failed`** - Emitted on ETL failure
   - Failure stage (classification, scanning, extraction, validation)
   - Structured error details

#### Files to Create

1. `components/logging/src/ai/miniforge/logging/events/etl.clj` (~80 lines)
   - Define `etl/completed` event schema
   - Define `etl/failed` event schema
   - Event emission helpers

#### Files to Modify

1. `components/workflow/src/ai/miniforge/workflow/etl.clj` (~60 lines)
   - Emit `etl/completed` on success
   - Emit `etl/failed` on failure
   - Collect summary statistics

2. `components/logging/src/ai/miniforge/logging/interface.clj` (~10 lines)
   - Export ETL event functions

3. `tests/conformance/conformance/event_stream_test.clj` (~50 lines)
   - Add ETL event conformance tests
   - Verify event emission in ETL workflows

#### Acceptance Criteria

- ✅ `etl/completed` event defined and emitted
- ✅ `etl/failed` event defined and emitted
- ✅ Events include required fields (duration, stats, errors)
- ✅ Events emitted in ETL workflows
- ✅ Conformance tests verify emission
- ✅ N3 §3.4 conformant

---

### 🔴 PR17: Add Promotion Justification to Evidence Bundles

**Branch:** `feat/promotion-justification`
**Priority:** P0 - CRITICAL
**Size Estimate:** ~150 lines
**Depends On:** None
**Blocks:** OSS v1.0 release

#### Scope (N6 §2.1)

Enhances evidence bundles to include `:promotion-justification` field for pack promotions, enabling audit trails for trust decisions.

#### Files to Modify

1. `components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj` (~30 lines)
   - Add `:promotion-justification` to `:evidence/pack-promotions` schema
   - Make field REQUIRED

2. `components/evidence-bundle/src/ai/miniforge/evidence_bundle/collector.clj` (~40 lines)
   - Collect justification during promotion
   - Include in evidence bundle

3. `components/knowledge/src/ai/miniforge/knowledge/promotion.clj` (~30 lines)
   - Pass justification when promoting packs
   - Example: "passed knowledge-safety scans", "manual review approved"

4. `components/evidence-bundle/test/ai/miniforge/evidence_bundle/schema_test.clj` (~50 lines)
   - Test justification field presence
   - Test validation of justification content

#### Acceptance Criteria

- ✅ `:promotion-justification` field added to schema
- ✅ Field is REQUIRED for pack promotions
- ✅ Justification collected during promotion
- ✅ Included in evidence bundles
- ✅ Tests verify field presence
- ✅ N6 §2.1 conformant

---

## 🟡 Near-Term PRs (OSS v1.1)

### PR18: Implement Incremental ETL

**Branch:** `feat/incremental-etl`
**Priority:** P1 - Important
**Size Estimate:** ~500 lines
**Depends On:** None

#### Scope (N1 §2.10.5)

Implements incremental ETL to avoid re-processing unchanged sources:

1. **Track source content hashes**
2. **Skip unchanged sources**
3. **Detect deletions**
4. **Handle dependencies** (if B changes, re-process A that depends on B)
5. **Maintain pack index incrementally**

#### Files to Create

1. `components/workflow/src/ai/miniforge/workflow/etl/incremental.clj` (~250 lines)
   - Content hash tracking
   - Change detection
   - Deletion detection
   - Dependency-aware re-processing

#### Files to Modify

1. `components/workflow/src/ai/miniforge/workflow/etl.clj` (~150 lines)
   - Integrate incremental processing
   - Load/save hash index
   - Skip unchanged sources

2. `components/workflow/test/ai/miniforge/workflow/etl/incremental_test.clj` (~100 lines)
   - Test hash tracking
   - Test skip unchanged
   - Test dependency re-processing
   - Test deletion detection

#### Acceptance Criteria

- ✅ Source hashes tracked
- ✅ Unchanged sources skipped
- ✅ Deletions detected
- ✅ Dependencies trigger re-processing
- ✅ Pack index maintained incrementally
- ✅ N1 §2.10.5 conformant

---

### PR19: Add ETL Dry-Run CLI Flag

**Branch:** `feat/etl-dry-run`
**Priority:** P1 - Important
**Size Estimate:** ~100 lines
**Depends On:** None

#### Scope (N5 §2.3.7)

Adds `--dry-run` flag to `miniforge etl repo` command for preview without generation.

#### Files to Modify

1. `bases/cli/src/ai/miniforge/cli/commands/etl.clj` (~40 lines)
   - Add `--dry-run` flag
   - Show what would be processed
   - Don't generate packs

2. `components/workflow/src/ai/miniforge/workflow/etl.clj` (~30 lines)
   - Support dry-run mode
   - Return preview information

3. `bases/cli/test/ai/miniforge/cli/commands/etl_test.clj` (~30 lines)
   - Test dry-run flag
   - Verify no packs generated

#### Acceptance Criteria

- ✅ `--dry-run` flag added
- ✅ Shows what would be processed
- ✅ No packs generated in dry-run
- ✅ Preview information returned
- ✅ N5 §2.3.7 conformant

---

### PR20: Enhanced Prompt Injection Scanner

**Branch:** `feat/enhanced-pi-scanner`
**Priority:** P1 - Important
**Size Estimate:** ~300 lines
**Depends On:** None

#### Scope (N4 §2.4.3)

Enhances the prompt injection tripwire scanner with additional detection patterns:

1. **Data exfiltration:** "send output to", "POST to", "curl http", webhook patterns
2. **Embedded execution:** Unusual code blocks, base64 blobs with `eval`, obfuscated scripts
3. **Time-based triggers:** "wait until", "after N days", "when timestamp", cron expressions
4. **Context confusion:** Blurring documentation vs instruction boundaries

#### Files to Modify

1. `components/policy-pack/src/ai/miniforge/policy_pack/rules/prompt_injection_tripwire.clj` (~200 lines)
   - Add data exfiltration patterns
   - Add embedded execution detection
   - Add time-based trigger patterns
   - Add context confusion detection
   - Sensitivity tuning by content type

2. `components/policy-pack/test/ai/miniforge/policy_pack/rules/prompt_injection_tripwire_test.clj` (~100 lines)
   - Test new pattern detection
   - Test sensitivity tuning
   - Test false positive handling

#### Acceptance Criteria

- ✅ Data exfiltration patterns detected
- ✅ Embedded execution detected
- ✅ Time-based triggers detected
- ✅ Context confusion detected
- ✅ Sensitivity configurable by content type
- ✅ N4 §2.4.3 conformant

---

### PR21: Implement Trust-Verified Subchannel

**Branch:** `feat/trust-verified-subchannel`
**Priority:** P1 - Important
**Size Estimate:** ~250 lines
**Depends On:** None

#### Scope (N2 §4.4.1)

Implements `:data/trust-verified` subchannel in phase context for scanner-verified content that enriches agent context without elevating trust.

#### Files to Modify

1. `components/workflow/src/ai/miniforge/workflow/state.clj` (~50 lines)
   - Add `:data/trust-verified` to phase context schema

2. `components/agent/src/ai/miniforge/agent/core.clj` (~80 lines)
   - Pass trust-verified content to agents
   - Keep content at `:untrusted` trust level
   - Include scanner findings for transparency

3. `components/policy-pack/src/ai/miniforge/policy_pack/knowledge_safety.clj` (~50 lines)
   - Populate trust-verified subchannel with scanned content

4. `tests/conformance/conformance/agent_context_handoff_test.clj` (~70 lines)
   - Test trust-verified subchannel in context
   - Verify trust level remains `:untrusted`
   - Verify scanner findings included

#### Acceptance Criteria

- ✅ `:data/trust-verified` subchannel implemented
- ✅ Scanner-verified content included
- ✅ Trust level remains `:untrusted`
- ✅ Scanner findings included for transparency
- ✅ Agents can access verified content
- ✅ N2 §4.4.1 conformant

---

## 🟢 Enhancements (OSS v1.x - From Original Plan)

### PR10: LLM Response Caching

**Status:** Deferred to post-ETL
**Priority:** P2
**Size:** ~300 lines

### PR11: Interoperability Tests

**Status:** Deferred to post-ETL
**Priority:** P2
**Size:** ~400 lines

### PR12: Meta Loop Enhancement

**Status:** Deferred to post-ETL
**Priority:** P2
**Size:** ~500 lines

### PR13: Parallel Tool Execution

**Status:** Deferred to post-ETL
**Priority:** P3
**Size:** ~350 lines

---

## Complete PR Summary Table

| PR | Title | Lines | Priority | Status | Depends On |
|----|-------|-------|----------|--------|------------|
| #77 | Agent.abort() | 150 | P0 | ✅ MERGED | - |
| #78 | Tool protocol | 200 | P0 | ✅ MERGED | - |
| #79 | Gate.repair() | 250 | P0 | ✅ MERGED | - |
| #81 | Human escalation | 300 | P0 | ✅ MERGED | - |
| #82 | Tool tracking | 350 | P0 | ✅ MERGED | #78 |
| #83 | Subagent | 400 | P0 | ✅ MERGED | #77 |
| #80 | Event replay | 400 | P0 | ✅ MERGED | - |
| #86 | N1 conformance | 600 | P0 | ⏳ PENDING | All above |
| **14** | **Transitive trust** | **350** | **P0** | **TODO** | **-** |
| **15** | **Pack dependencies** | **400** | **P0** | **TODO** | **-** |
| **16** | **ETL events** | **200** | **P0** | **TODO** | **-** |
| **17** | **Promotion justification** | **150** | **P0** | **TODO** | **-** |
| **18** | **Incremental ETL** | **500** | **P1** | **TODO** | **-** |
| **19** | **ETL dry-run** | **100** | **P1** | **TODO** | **-** |
| **20** | **Enhanced PI scanner** | **300** | **P1** | **TODO** | **-** |
| **21** | **Trust-verified subchannel** | **250** | **P1** | **TODO** | **-** |
| 10 | LLM cache | 300 | P2 | TODO | - |
| 11 | Interoperability | 400 | P2 | TODO | - |
| 12 | Meta loop | 500 | P2 | TODO | - |
| 13 | Parallel tools | 350 | P3 | TODO | - |

**Totals:**

- ✅ Merged: 2,650 lines (8 PRs)
- ⏳ Pending: 600 lines (1 PR)
- 🔴 Critical TODO: 1,100 lines (4 PRs)
- 🟡 Important TODO: 1,150 lines (4 PRs)
- 🟢 Enhancement TODO: 1,550 lines (4 PRs)
- **Grand Total: 7,050 lines across 21 PRs**

---

## Implementation Timeline

### Phase 1: N1 Core Completion (✅ DONE)

**Week 1 (Jan 24):** PRs #77-83 merged
**Week 2 (Jan 25):** PR #86 created

### Phase 2: ETL Critical (Week 3-4)

**Goal:** Complete OSS v1.0 release with ETL

**Week 3:**

- PR14: Transitive trust rules
- PR15: Pack dependency validation
- PR16: ETL lifecycle events
- PR17: Promotion justification

**Strategy:** Run all 4 PRs in **parallel** (independent agents)

### Phase 3: ETL Important (Week 5-6)

**Goal:** Complete OSS v1.1 features

**Week 5-6:**

- PR18: Incremental ETL
- PR19: ETL dry-run
- PR20: Enhanced PI scanner
- PR21: Trust-verified subchannel

**Strategy:** Run all 4 PRs in **parallel** (independent agents)

### Phase 4: Enhancements (Week 7+)

**Goal:** Nice-to-have improvements

**Week 7+:**

- PR10: LLM cache
- PR11: Interoperability tests
- PR12: Meta loop enhancement
- PR13: Parallel tool execution

**Strategy:** Run all 4 PRs in **parallel** (independent agents)

---

## Worktree Strategy

### Current Worktrees

```bash
/Users/chris/Local/miniforge.ai/miniforge  # Main (on main)
/private/tmp/miniforge-pr6                 # chris/spec-updates
/private/tmp/miniforge-pr9                 # feat/n1-conformance-tests-v2 (PR #86)
```

### Recommended Worktrees for Phase 2 (ETL Critical)

Create 4 parallel worktrees:

```bash
cd /Users/chris/Local/miniforge.ai/miniforge
git worktree add /private/tmp/miniforge-pr14 -b feat/transitive-trust-rules
git worktree add /private/tmp/miniforge-pr15 -b feat/pack-dependency-validation
git worktree add /private/tmp/miniforge-pr16 -b feat/etl-lifecycle-events
git worktree add /private/tmp/miniforge-pr17 -b feat/promotion-justification
```

Then spin up 4 parallel agents:

```bash
# In a single message, use 4 Task tool calls to launch agents for PR14-17
```

---

## Success Criteria

### For OSS v1.0 Release

- ✅ All N1 core PRs merged (#77-83, #86)
- ✅ All ETL critical PRs merged (PR14-17)
- ✅ All conformance tests pass
- ✅ No P0 blockers remaining

### For OSS v1.1 Release

- ✅ All ETL important PRs merged (PR18-21)
- ✅ Incremental ETL functional
- ✅ Enhanced security posture (PI scanner, trust rules, dependency validation)

### For OSS v1.x Releases

- ✅ All enhancement PRs merged (PR10-13)
- ✅ Offline mode functional
- ✅ Meta loop improvements measurable
- ✅ Performance improvements documented

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| ETL critical PRs reveal new dependencies | Medium | High | Independent design allows parallel work |
| Transitive trust complexity | Medium | Medium | Comprehensive tests, clear rules |
| Pack dependency validation edge cases | High | Medium | Extensive test coverage, graph algorithms well-known |
| Incremental ETL performance issues | Low | Medium | Profile and optimize, cache intermediate results |
| Prompt injection false positives | Medium | Low | Tunable sensitivity, allowlist mechanism |

---

## Next Actions

### Immediate (This Session)

1. ✅ Review ETL spec enhancements
2. ✅ Create updated PR DAG
3. ⏳ Merge PR #86 (user action)
4. ⏳ Create 4 worktrees for PR14-17
5. ⏳ Launch 4 parallel agents for ETL critical work

### Next Session

1. Review PR14-17 results
2. Create PRs for ETL critical work
3. Plan Phase 3 (ETL important work)

---

**Prepared by:** Claude Sonnet 4.5
**Date:** 2026-01-25
**Next Review:** After PR #86 merged, before starting PR14-17
