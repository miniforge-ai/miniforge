# N1 Architecture Implementation Status

**Date:** 2026-01-24  
**Spec Version:** N1-architecture.md v0.1.0-draft  
**Last Updated:** After PR #75 merged (evidence-bundle + inter-agent messaging)

---

## Executive Summary

**Overall Progress:** ~70% complete

The N1 core architecture is substantially implemented. Key achievements:

- ✅ All major components exist
- ✅ Three-layer architecture established
- ✅ Polylith structure in place
- ✅ Evidence bundles implemented (PR #75)
- ✅ Inter-agent messaging implemented (PR #75)

**Remaining work focuses on:**

- Protocol alignment with spec
- Missing protocol methods
- Component integration and wiring
- End-to-end workflow conformance tests

---

## 1. Core Domain Model (§2)

### 2.1 Workflow ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Workflow schema | ✅ Implemented | `components/workflow/src/ai/miniforge/workflow/state.clj` |
| UUID assignment | ✅ Implemented | Workflow engine |
| Status tracking | ✅ Implemented | Workflow FSM |
| Phase execution records | ✅ Implemented | State management |
| Evidence bundle generation | ✅ Implemented | PR #75 - `components/evidence-bundle/` |
| Event emission | ✅ Implemented | Workflow runner |

**Gaps:** None

---

### 2.2 Phase ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Phase schema | ✅ Implemented | `components/phase/` |
| Standard phases | ✅ Implemented | Plan, Design, Implement, Verify, Review, Release, Observe |
| Phase status tracking | ✅ Implemented | Workflow state |
| Input/output context | ✅ Implemented | Context handoff |
| Artifact tracking | ✅ Implemented | Artifact component |

**Gaps:** None

---

### 2.3 Agent ⚠️ **PARTIALLY IMPLEMENTED**

| Requirement | Status | Location | Gap |
|-------------|--------|----------|-----|
| Agent protocol | ⚠️ Partial | `components/agent/src/ai/miniforge/agent/interface/protocols/agent.clj` | Missing `abort()` method |
| Standard agents | ✅ Implemented | Planner, Implementer, Tester, Reviewer | Observer needs enhancement |
| Agent status | ✅ Implemented | AgentLifecycle protocol | |
| Agent memory | ✅ Implemented | `components/agent/src/ai/miniforge/agent/memory.clj` | |

**Spec requires:**

```clojure
(defprotocol Agent
  (invoke [agent context])
  (get-status [agent])
  (abort [agent reason]))  ; ❌ MISSING
```

**Current implementation:**

```clojure
(defprotocol Agent
  (invoke [this task context])
  (validate [this output context])
  (repair [this output errors context]))

(defprotocol AgentLifecycle
  (init [this config])
  (status [this])    ; ✅ Exists as `status`
  (shutdown [this]))
```

**Action Required:**

- Add `abort` method to Agent protocol or AgentLifecycle
- Implement abort logic in agent executor

---

### 2.4 Subagent ❌ **NOT IMPLEMENTED**

| Requirement | Status | Gap |
|-------------|--------|-----|
| Subagent schema | ❌ Missing | No schema defined |
| Parent-child linking | ❌ Missing | No implementation |
| Subagent lifecycle events | ❌ Missing | No event emission |
| Result aggregation | ❌ Missing | No aggregation logic |
| Failure propagation | ❌ Missing | No failure handling |

**Action Required:**

- Create `components/subagent/` component OR
- Add subagent support to existing `components/agent/` component
- Define subagent protocol
- Implement parent-child relationship tracking
- Add event emission for subagent lifecycle

---

### 2.5 Tool ⚠️ **PARTIALLY IMPLEMENTED**

| Requirement | Status | Location | Gap |
|-------------|--------|----------|-----|
| Tool protocol | ⚠️ Partial | `components/tool/src/ai/miniforge/tool/core.clj` | Missing `validate-args` and `get-schema` methods |
| Tool registry | ✅ Implemented | ToolRegistry protocol | |
| Tool invocation tracking | ❌ Missing | No tracking | Need invocation records |

**Spec requires:**

```clojure
(defprotocol Tool
  (invoke [tool args])
  (validate-args [tool args])  ; ❌ MISSING (exists as free fn)
  (get-schema [tool]))          ; ❌ MISSING
```

**Current implementation:**

```clojure
(defprotocol Tool
  (tool-id [this])
  (tool-info [this])      ; Returns metadata (partial)
  (execute [this params context]))
```

**Action Required:**

- Add `validate-args` and `get-schema` methods to Tool protocol
- Implement tool invocation tracking (for evidence bundles)
- Add tool execution records to evidence bundles

---

### 2.6 Gate ⚠️ **PARTIALLY IMPLEMENTED**

| Requirement | Status | Location | Gap |
|-------------|--------|----------|-----|
| Gate protocol | ⚠️ Partial | `components/loop/src/ai/miniforge/loop/interface/protocols/gate.clj` | Missing `repair` method |
| Gate types | ✅ Implemented | :syntax, :lint, :test, :policy | |
| Gate registry | ✅ Implemented | `components/gate/src/ai/miniforge/gate/registry.clj` | |

**Spec requires:**

```clojure
(defprotocol Gate
  (check [gate artifacts context])
  (repair [gate artifacts violations context]))  ; ❌ MISSING
```

**Current implementation:**

```clojure
(defprotocol Gate
  (check [this artifact context])
  (gate-id [this])
  (gate-type [this]))
```

**Action Required:**

- Add `repair` method to Gate protocol
- Implement repair logic in gate implementations
- Connect repair to inner loop

---

### 2.7 Policy Pack ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Policy pack schema | ✅ Implemented | `components/policy-pack/` |
| Rule definitions | ✅ Implemented | Policy pack loader |
| Pack registry | ✅ Implemented | Policy pack registry |

**Gaps:** None (see N4 spec for detailed policy pack work)

---

### 2.8 Evidence Bundle ✅ **IMPLEMENTED (PR #75)**

| Requirement | Status | Location |
|-------------|--------|----------|
| Evidence bundle schema | ✅ Implemented | `components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj` |
| Intent capture | ✅ Implemented | Collector |
| Phase evidence | ✅ Implemented | Workflow integration |
| Semantic validation | ✅ Implemented | Semantic validator |
| Policy checks | ✅ Implemented | Policy check recording |
| Provenance tracking | ✅ Implemented | Provenance tracer |

**Gaps:** None

---

### 2.9 Artifact ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Artifact schema | ✅ Implemented | `components/artifact/` |
| Content hashing | ✅ Implemented | Artifact store |
| Provenance | ✅ Implemented | Linked to evidence-bundle |

**Gaps:** None

---

### 2.10 Knowledge Base ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Zettelkasten implementation | ✅ Implemented | `components/knowledge/src/ai/miniforge/knowledge/zettel.clj` |
| Knowledge units | ✅ Implemented | Store and loader |
| Full-text indexing | ✅ Implemented | Knowledge interface |

**Gaps:** None

---

## 2. Three-Layer Architecture (§3)

### 3.1 Control Plane ✅ **IMPLEMENTED**

| Component | Status | Location |
|-----------|--------|----------|
| Operator Agent | ✅ Implemented | `components/operator/` |
| Workflow Engine | ✅ Implemented | `components/workflow/` |
| Policy Engine | ✅ Implemented | `components/policy/` |

**Gaps:** None

---

### 3.2 Agent Layer ✅ **IMPLEMENTED**

| Component | Status | Location |
|-----------|--------|----------|
| Specialized agents | ✅ Implemented | Planner, Implementer, Tester, Reviewer |
| Inner loop | ✅ Implemented | `components/loop/src/ai/miniforge/loop/inner.clj` |
| Tool integrations | ✅ Implemented | `components/tool/` |

**Gaps:** None

---

### 3.3 Learning Layer ✅ **IMPLEMENTED**

| Component | Status | Location |
|-----------|--------|----------|
| Observer Agent | ✅ Implemented | `components/observer/` |
| Meta Loop | ⚠️ Partial | Basic implementation in `operator/` (needs enhancement) |
| Heuristic Registry | ✅ Implemented | `components/heuristic/` |
| Knowledge Base | ✅ Implemented | `components/knowledge/` |

**Gaps:** Meta loop needs more sophisticated pattern extraction

---

## 3. Polylith Component Boundaries (§4)

### 4.1 Component Structure ✅ **IMPLEMENTED**

All required components exist:

- ✅ schema, logging, llm, tool, agent, task, loop, workflow
- ✅ knowledge, policy, policy-pack, heuristic, artifact, gate, observer
- ✅ operator, orchestrator, phase, fsm, response

Additional components (not in spec):

- ✅ reporting (for TUI/CLI output)
- ✅ pr-train (for PR train feature)
- ✅ repo-dag (for repo dependency analysis)
- ✅ evidence-bundle (added in PR #75)

---

## 4. Operational Model (§5)

### 5.1 Local-First Execution ✅ **IMPLEMENTED**

| Requirement | Status | Notes |
|-------------|--------|-------|
| Runs locally | ✅ Yes | No external deps except LLM API |
| Local state storage | ✅ Yes | `~/.miniforge/` |
| Offline execution | ⚠️ Partial | Needs LLM response caching |
| Persist across restarts | ✅ Yes | Workflow persistence |

**Gap:** LLM response caching for offline mode

---

### 5.2 Reproducibility ⚠️ **PARTIALLY IMPLEMENTED**

| Requirement | Status | Notes |
|-------------|--------|-------|
| Deterministic planner | ⚠️ Partial | Needs verification |
| Event stream replay | ⚠️ Partial | Events exist, replay not tested |
| Same inputs → same outputs | ❌ Not verified | Need conformance tests |

**Action Required:**

- Create reproducibility test suite
- Implement event stream replay
- Verify determinism in planner

---

### 5.3 Failure Semantics ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Fail-safe | ✅ Implemented | Workflow FSM |
| Fail-visible | ✅ Implemented | Event stream + evidence |
| Fail-recoverable | ⚠️ Partial | Basic resume, needs testing |
| Fail-escalatable | ⚠️ Partial | Escalation exists, needs UX |

**Gap:** Human escalation UX (prompt user for guidance)

---

### 5.4 Concurrency Model ✅ **IMPLEMENTED (OSS)**

| Requirement | Status | Notes |
|-------------|--------|-------|
| Single workflow execution | ✅ Yes | Supported |
| Sequential phases | ✅ Yes | FSM-based |
| Parallel tool invocations | ⚠️ Partial | Possible but not default |

---

## 5. Agent Protocols & Communication (§6)

### 6.1 Agent Invocation Protocol ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Context structure | ✅ Implemented | Workflow state |
| Output + artifacts | ✅ Implemented | Agent protocol |
| Next context | ✅ Implemented | Context handoff |

---

### 6.2 Inter-Agent Communication ✅ **IMPLEMENTED (PR #75)**

| Requirement | Status | Location |
|-------------|--------|----------|
| Message types | ✅ Implemented | `components/agent/src/ai/miniforge/agent/interface/protocols/messaging.clj` |
| Message schema | ✅ Implemented | Messaging protocol |
| Event emission | ✅ Implemented | Message events |

**Message types implemented:**

- `:clarification-request`
- `:clarification-response`
- `:concern`
- `:suggestion`

---

### 6.3 Context Handoff Protocol ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Workflow spec in context | ✅ Implemented | State management |
| Prior phase outputs | ✅ Implemented | Context handoff |
| Knowledge context | ✅ Implemented | Knowledge integration |
| Policy context | ✅ Implemented | Policy integration |

---

## 6. Inner Loop & Outer Loop (§7)

### 7.1 Outer Loop (Phase Graph) ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Phase transitions | ✅ Implemented | Workflow FSM |
| Context passing | ✅ Implemented | State management |
| Gate enforcement | ✅ Implemented | Gate execution |
| Phase skipping | ✅ Implemented | Configurable workflows |
| Event emission | ✅ Implemented | Workflow runner |

---

### 7.2 Inner Loop (Validate → Repair) ✅ **IMPLEMENTED**

| Requirement | Status | Location |
|-------------|--------|----------|
| Validation | ✅ Implemented | Agent protocol + gates |
| Repair | ✅ Implemented | Agent repair + inner loop |
| Retry budget | ✅ Implemented | Loop configuration |
| Escalation | ⚠️ Partial | Exists but needs UX |
| Iteration recording | ✅ Implemented | Evidence bundle |

---

## 7. Conformance & Testing (§8)

### 8.1 Component Conformance ⚠️ **PARTIALLY IMPLEMENTED**

| Requirement | Status | Gap |
|-------------|--------|-----|
| All protocols implemented | ⚠️ Partial | Missing: Agent.abort, Tool.validate-args, Tool.get-schema, Gate.repair |
| All events emitted | ⚠️ Needs verification | Need event coverage tests |
| Evidence bundles for all workflows | ✅ Implemented | |
| All standard phases/agents | ✅ Implemented | |
| Schema validation | ⚠️ Partial | Exists but not enforced everywhere |

---

### 8.2 Integration Tests ❌ **NOT IMPLEMENTED**

| Test | Status | Gap |
|------|--------|-----|
| End-to-end workflow execution | ❌ Missing | No spec → PR test |
| Agent context handoff | ❌ Missing | No context validation test |
| Inner loop validation | ⚠️ Partial | Unit tests exist, no integration test |
| Gate enforcement | ⚠️ Partial | Unit tests exist, no integration test |
| Event stream completeness | ❌ Missing | No event coverage test |

**Action Required:**

- Create `tests/conformance/` suite
- Implement end-to-end workflow test (spec → PR)
- Verify event completeness
- Test context handoff between agents

---

### 8.3 Interoperability Tests ❌ **NOT IMPLEMENTED**

| Test | Status | Gap |
|------|--------|-----|
| Polylith component isolation | ⚠️ Partial | Components can be tested independently, but no explicit test |
| Knowledge base portability | ❌ Missing | No export/import test |
| Evidence bundle portability | ❌ Missing | No cross-instance test |
| Policy pack compatibility | ❌ Missing | No community pack test |

---

## Summary: Priority Work Items

### 🔴 **Critical** (Blocking N1 Conformance)

1. **Protocol Alignment**
   - Add `Agent.abort()` method
   - Add `Tool.validate-args()` and `Tool.get-schema()` methods
   - Add `Gate.repair()` method

2. **Conformance Tests** (§8.2)
   - End-to-end workflow execution test (spec → PR with evidence)
   - Event stream completeness verification
   - Agent context handoff validation

3. **Subagent Support** (§2.4)
   - Define subagent schema and protocol
   - Implement parent-child tracking
   - Add subagent lifecycle events

---

### 🟡 **Important** (Spec Compliance)

1. **Tool Invocation Tracking** (§2.5)
   - Record all tool invocations
   - Include in evidence bundles
   - Track invocation metadata (timestamp, duration, result)

2. **Reproducibility** (§5.2)
   - Implement event stream replay
   - Create determinism tests
   - Add LLM response caching for offline mode

3. **Human Escalation UX** (§5.3)
   - Prompt user for guidance when retry budget exhausted
   - Provide clear error context
   - Allow user to provide hints/corrections

---

### 🟢 **Nice-to-Have** (Enhancement)

1. **Interoperability Tests** (§8.3)
   - Knowledge base export/import
   - Evidence bundle cross-instance reading
   - Community policy pack testing

2. **Meta Loop Enhancement** (§3.3)
   - Sophisticated pattern extraction
   - Automatic heuristic evolution
   - Performance-based heuristic ranking

3. **Parallel Tool Invocation** (§5.4)
   - Enable safe parallel tool calls within agents
   - Resource management for concurrent tools

---

## Component Health Matrix

| Component | Implementation | Tests | Protocol Conformance |
|-----------|----------------|-------|---------------------|
| agent | 85% | ✅ Good | ⚠️ Missing abort |
| tool | 75% | ✅ Good | ⚠️ Missing methods |
| gate | 80% | ✅ Good | ⚠️ Missing repair |
| evidence-bundle | 100% | ✅ Good | ✅ Conformant |
| workflow | 95% | ✅ Good | ✅ Conformant |
| knowledge | 90% | ✅ Good | ✅ Conformant |
| policy | 85% | ✅ Good | ✅ Conformant |
| observer | 75% | ⚠️ Basic | ✅ Conformant |
| **subagent** | **0%** | ❌ None | ❌ Not started |

---

## Next Steps

1. **Create GitHub issues** for each critical work item
2. **Prioritize protocol alignment** (Agent, Tool, Gate)
3. **Implement conformance test suite**
4. **Add subagent support**
5. **Document gaps in other normative specs** (N2-N6)

---

**Prepared by:** AI Assistant  
**Review Status:** Draft  
**Next Review:** After protocol alignment PRs merge
