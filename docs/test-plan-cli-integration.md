<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# CLI Integration Test Plan

> This test plan covers the **Miniforge SDLC** CLI and its integration with external coding-assistant backends.

## Overview

This document defines the test plan for integrating miniforge with external CLI tools
(Claude CLI, Cursor CLI, Codex CLI, Copilot CLI) and validating miniforge's capabilities
through dogfooding - using miniforge to build and improve miniforge itself.

## Test Environment

### Available CLI Backends

| Backend | Command | Version | Status |
|---------|---------|---------|--------|
| Claude | `claude` | 2.1.12 | Available |
| Cursor | `cursor` | 2.3.41 | Available |
| Codex | `codex` | TBD | Not installed |
| Copilot | `gh copilot` | TBD | Not installed |

### Interface Requirements

External CLIs must support:

1. **Prompt input**: Accept prompts via command-line argument
2. **System prompt**: Optional system context
3. **Streaming or batch output**: Return completion text to stdout
4. **Exit codes**: 0 for success, non-zero for errors

Current backend configuration in `components/llm/src/ai/miniforge/llm/core.clj`:

```clojure
{:claude {:cmd "claude"
          :args-fn (fn [{:keys [prompt system max-tokens]}]
                     (cond-> ["-p" prompt]
                       system (into ["--system" system])
                       max-tokens (into ["--max-tokens" (str max-tokens)])))}

 :cursor {:cmd "cursor"
          :args-fn (fn [{:keys [prompt system]}]
                     (cond-> ["--prompt" prompt]
                       system (into ["--system" system])))}}
```

## Test Categories

### 1. Unit Tests - CLI Backend Integration

**Location**: `components/llm/test/ai/miniforge/llm/cli_integration_test.clj`

| Test | Description | Priority |
|------|-------------|----------|
| `claude-cli-availability-test` | Verify claude CLI is installed and responds | P0 |
| `claude-simple-prompt-test` | Send simple prompt, verify response | P0 |
| `claude-system-prompt-test` | Send prompt with system context | P1 |
| `claude-error-handling-test` | Test error handling for invalid inputs | P1 |
| `cursor-cli-availability-test` | Verify cursor CLI is installed | P1 |
| `cursor-simple-prompt-test` | Send simple prompt via cursor | P1 |

### 2. Integration Tests - Agent Execution

**Location**: `development/test/ai/miniforge/agent_integration_test.clj`

| Test | Description | Priority |
|------|-------------|----------|
| `planner-with-real-llm-test` | Planner agent produces valid plan with real LLM | P0 |
| `implementer-with-real-llm-test` | Implementer produces valid code | P0 |
| `tester-with-real-llm-test` | Tester generates appropriate tests | P1 |
| `agent-chain-test` | Plan -> Implement -> Test chain works | P0 |

### 3. End-to-End Tests - Workflow Execution

**Location**: `development/test/ai/miniforge/workflow_e2e_test.clj`

| Test | Description | Priority |
|------|-------------|----------|
| `simple-function-workflow-test` | Workflow that creates a simple utility function | P0 |
| `component-creation-workflow-test` | Workflow that creates a new Polylith component | P1 |
| `bug-fix-workflow-test` | Workflow that fixes a known bug | P1 |
| `refactor-workflow-test` | Workflow that refactors existing code | P2 |

### 4. Dogfooding Tests - Meta-Loop Validation

**Location**: `development/test/ai/miniforge/dogfood_test.clj`

These tests use miniforge to improve miniforge itself:

| Test | Description | Priority |
|------|-------------|----------|
| `add-docstring-test` | Use miniforge to add docstrings to undocumented functions | P1 |
| `add-test-coverage-test` | Use miniforge to add tests for untested code | P1 |
| `improve-error-messages-test` | Use miniforge to improve error messages | P2 |
| `implement-new-feature-test` | Use miniforge to implement a new component | P2 |

## Test Scenarios

### Scenario 1: Simple Function Creation (P0)

**Spec**:

```clojure
{:title "Create string utility"
 :description "Create a function that converts snake_case to kebab-case"}
```

**Expected Workflow**:

1. Planner creates task: implement snake->kebab function
2. Implementer produces: `(defn snake->kebab [s] (str/replace s "_" "-"))`
3. Tester generates: test cases including edge cases
4. Reviewer validates: code quality, naming, edge cases

**Validation**:

- [ ] Function is syntactically valid Clojure
- [ ] Function produces correct output for test inputs
- [ ] Tests cover happy path and edge cases

### Scenario 2: Component Creation (P1)

**Spec**:

```clojure
{:title "Create rate-limiter component"
 :description "A Polylith component that provides rate limiting functionality"}
```

**Expected Outputs**:

- `components/rate-limiter/src/ai/miniforge/rate_limiter/interface.clj`
- `components/rate-limiter/src/ai/miniforge/rate_limiter/core.clj`
- `components/rate-limiter/test/ai/miniforge/rate_limiter/interface_test.clj`

### Scenario 3: Bug Fix (P1)

**Spec**:

```clojure
{:title "Fix infinite loop in task graph"
 :description "The task graph cycle detection fails for indirect cycles"}
```

**Expected Workflow**:

1. Planner analyzes bug description
2. Implementer locates and fixes the code
3. Tester adds regression test
4. Reviewer validates fix doesn't break other functionality

### Scenario 4: Dogfooding - Add Documentation (P1)

**Spec**:

```clojure
{:title "Add missing docstrings"
 :description "Add docstrings to all public functions in components/task/"}
```

**Validation**:

- [ ] All public functions have docstrings
- [ ] Docstrings follow project conventions
- [ ] No functional changes to code

## Test Infrastructure

### Test Harness

```clojure
(ns ai.miniforge.test-harness
  "Harness for running integration tests with real LLM backends."
  (:require
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.orchestrator.interface :as orch]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.artifact.interface :as artifact]))

(defn create-test-orchestrator
  "Create orchestrator with real LLM backend for integration testing."
  [{:keys [backend budget]
    :or {backend :claude
         budget {:max-tokens 50000 :max-cost-usd 1.0}}}]
  (let [llm-client (llm/create-client {:backend backend})
        k-store (knowledge/create-store)
        a-store (artifact/create-store)]
    (orch/create-orchestrator llm-client k-store a-store)))

(defn execute-and-validate
  "Execute workflow and validate results."
  [orchestrator spec validators]
  (let [result (orch/execute-workflow orchestrator spec {:budget default-budget})]
    {:workflow-id (:workflow-id result)
     :status (:status result)
     :validations (map (fn [v] (v result)) validators)}))
```

### Budget Controls

For safety, all tests should have strict budget limits:

```clojure
(def test-budgets
  {:unit-test     {:max-tokens 1000  :max-cost-usd 0.01}
   :integration   {:max-tokens 10000 :max-cost-usd 0.10}
   :e2e           {:max-tokens 50000 :max-cost-usd 0.50}
   :dogfood       {:max-tokens 100000 :max-cost-usd 1.00}})
```

### Markers for Expensive Tests

Tests requiring real LLM calls should be marked:

```clojure
(deftest ^:integration ^:expensive planner-with-real-llm-test
  ...)
```

Run with: `clojure -M:poly test :dev :include :integration`

## Execution Plan

### Phase 1: CLI Validation (Day 1)

1. Verify both CLIs work with miniforge's interface
2. Add any missing CLI flags/options
3. Run basic completion tests

### Phase 2: Agent Integration (Day 2-3)

1. Test each agent type with real LLM
2. Validate output parsing
3. Test repair loops

### Phase 3: Workflow Integration (Day 4-5)

1. Run simple workflow end-to-end
2. Test phase transitions
3. Validate artifact creation

### Phase 4: Dogfooding (Day 6+)

1. Use miniforge to add its own tests
2. Use miniforge to fix its own bugs
3. Use miniforge to implement new features

## Success Criteria

### Must Have (P0)

- [ ] Claude CLI integration works reliably
- [ ] Planner agent produces valid plans
- [ ] Implementer agent produces syntactically valid code
- [ ] Simple workflow completes successfully

### Should Have (P1)

- [ ] Cursor CLI integration works
- [ ] All agent types function correctly
- [ ] Repair loops work when validation fails
- [ ] Dogfooding tests demonstrate self-improvement

### Nice to Have (P2)

- [ ] Codex CLI integration
- [ ] Copilot CLI integration
- [ ] Multi-agent collaboration
- [ ] Meta-loop improvements captured

## Notes

### CLI Differences

| Feature | Claude | Cursor | Notes |
|---------|--------|--------|-------|
| Prompt flag | `-p` | `--prompt` | Different flags |
| System prompt | `--system` | `--system` | Same |
| Max tokens | `--max-tokens` | N/A | Cursor doesn't support |
| Output format | Plain text | Plain text | Same |
| Exit codes | Standard | Standard | Same |

### Known Issues

1. Claude CLI may require authentication setup
2. Cursor CLI may need workspace context
3. Token counting not available from CLI output
