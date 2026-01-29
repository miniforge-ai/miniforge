# PR #93: CLI Spec File Support with Markdown and Layered Decoration

**Branch:** `feat/cli-run-spec-file`
**Date:** 2026-01-28
**Layer:** CLI / Workflow Execution
**Part of:** Dogfooding Enablement (N5 CLI Implementation)
**Depends On:** PR #92 (Merged)
**Blocks:** Meta-meta loop (using miniforge to build miniforge)

## Overview

Completes the CLI `mf run <spec-file>` command by wiring spec file parsing to the workflow
engine, adds markdown format support, and implements interceptor-style layered decoration
for inner/outer loop context enrichment.

This unblocks dogfooding by enabling developers to run arbitrary workflows from spec files
in EDN, JSON, or Markdown formats.

## Motivation

**N5 CLI Spec Requirements:**

- Users must be able to run workflows from spec files (not just catalog workflows)
- Spec files should support multiple formats (EDN for code, JSON for tooling, Markdown for humans)
- Specs should be enriched with runtime context for evidence bundles and debugging

**Current State:**

- CLI exists but `mf run <spec-file>` was a stub (printed "TODO")
- Workflow engine works in tests but couldn't be invoked via CLI with arbitrary specs
- No context decoration for evidence provenance or loop feedback

**This PR enables:**

- Using miniforge to build miniforge (meta-meta loop)
- Evidence bundles with full provenance (source file → execution → outcome)
- Inner loop repair with retry context (iteration count, previous errors)
- Future outer loop learning injection (reserved Layer 2)

## Changes in Detail

### 1. Spec File Parsing (Layer 0 Decoration)

**File:** `bases/cli/src/ai/miniforge/cli/spec_parser.clj`

**Changes:**

- Add markdown format support (`.md` files with YAML frontmatter)
- Reuse `knowledge.interface` YAML parser (respects Polylith boundaries)
- Add `:spec/provenance` decoration at parse time:
  - `source-file`: Path to spec file
  - `source-format`: `:edn`, `:json`, or `:markdown`
  - `loaded-at`: Timestamp when spec was parsed
  - `file-size`: Size of source file in bytes

**Example Markdown Spec:**

```markdown
---
title: Fix authentication timeout bug
description: Resolve JWT validation timeouts
intent:
  type: bugfix
  scope: [components/auth/src]
constraints:
  - maintain-backward-compatibility
  - add-regression-test
tags: [bugfix, authentication]
---

## Additional Context

Extended description, references, and debugging context.
Body content is appended to the description field.
```

### 2. Runtime Context Decoration (Layer 1)

**File:** `bases/cli/src/ai/miniforge/cli/workflow_runner.clj`

**Changes:**

- Add `decorate-spec-with-runtime-context` function
- Add `:spec/context` decoration:
  - `cwd`: Current working directory
  - `git-branch`: Current git branch (if in repo)
  - `git-commit`: Current git commit SHA (if in repo)
  - `files-in-scope`: Files extracted from `:intent :scope`
  - `environment`: `:development` (extensible for prod/staging)
- Add `:spec/metadata` decoration:
  - `submitted-at`: Execution start timestamp
  - `session-id`: Unique execution session UUID
  - `iteration`: Retry count (default: 1, incremented on repair)
  - `parent-task-id`: UUID of parent workflow (for chaining)

### 3. Knowledge Component Interface Extension

**File:** `components/knowledge/src/ai/miniforge/knowledge/interface.clj`

**Changes:**

- Export `split-frontmatter` function (YAML frontmatter extraction)
- Export `parse-yaml-frontmatter` function (YAML → EDN parsing)
- Uses `requiring-resolve` to avoid compile-time dependency

This allows CLI base to use knowledge component's YAML parser without
violating Polylith component boundaries.

### 4. Examples and Documentation

**Files:**

- `examples/workflows/fix-bug.md`: New markdown example
- `examples/workflows/README.md`: Updated with markdown format docs and decoration layers

**Documentation Additions:**

- Markdown format specification and examples
- Layered decoration architecture explanation
- Use cases for inner/outer loops
- Future Layer 2 (meta-loop learning injection) reserved

## Layered Decoration Architecture

Following the interceptor/middleware pattern from web frameworks, specs are
decorated at multiple layers with each layer adding what it knows:

```text
User Spec File (EDN/JSON/Markdown)
         ↓
[Layer 0: Parse Time]
  + :spec/provenance
    - source-file, source-format, loaded-at, file-size
         ↓
[Layer 1: Execution Time]
  + :spec/context
    - cwd, git-branch, git-commit, files-in-scope, environment
  + :spec/metadata
    - submitted-at, session-id, iteration, parent-task-id
         ↓
[Layer 2: Meta-Loop Time] (Future)
  + :spec/learning-refs
    - Injected learnings from knowledge store
    - Pattern-based recommendations
         ↓
Workflow Engine Execution
         ↓
Evidence Bundle (includes full enriched spec)
```

## Use Cases Enabled

### Inner Loop (Repair Cycles)

When a workflow fails and needs retry:

```clojure
{:spec/title "Fix authentication bug"
 :spec/context {:files-in-scope ["auth.clj" "middleware.clj"]
                :git-commit "abc123"}
 :spec/metadata {:iteration 2  ;; Second attempt
                 :parent-task-id #uuid "..."
                 :previous-error "NullPointerException at line 42"}}
```

The repair agent can see:

- Which files were modified in previous attempt
- Current git state for rollback
- Iteration count to avoid infinite loops
- Previous error context

### Outer Loop (Meta Learning - Future)

When meta-loop observes patterns:

```clojure
{:spec/title "Refactor authentication"
 :spec/learning-refs
   [{:zettel-uid "auth-security-checklist"
     :content "Always validate JWT signatures..."
     :confidence 0.95
     :injected-by :meta-loop
     :reason "Pattern match: authentication + refactor"}]}
```

The workflow can be enriched with learnings before execution.

### Evidence & Provenance

Evidence bundles capture full enriched specs:

```clojure
{:evidence/spec
   {:spec/title "..."
    :spec/provenance {:source-file "examples/workflows/fix-bug.md"
                      :source-format :markdown
                      :loaded-at #inst "2026-01-28T..."}
    :spec/context {:git-commit "abc123"
                   :cwd "/Users/dev/project"}
    :spec/metadata {:session-id #uuid "..."
                    :iteration 1}}
 :evidence/execution {...}
 :evidence/outcome {...}}
```

This enables:

- Full audit trail from source → execution → outcome
- Debugging failed workflows (see exact context)
- Compliance requirements (provenance tracking)
- Meta-loop pattern mining (analyze successful vs failed workflows)

## Testing Plan

```bash
# Run all tests
bb test:all

# Rebuild and install CLI
bb install:local

# Test EDN format
mf run examples/workflows/simple-refactor.edn

# Test JSON format
mf run examples/workflows/add-tests.json

# Test Markdown format
mf run examples/workflows/fix-bug.md
```

### Test Coverage

- ✅ Spec parser handles EDN format
- ✅ Spec parser handles JSON format
- ✅ Spec parser handles Markdown format (with YAML frontmatter)
- ✅ Layer 0 decoration adds provenance
- ✅ Layer 1 decoration adds context (cwd, git info, files-in-scope)
- ✅ Layer 1 decoration adds metadata (session-id, iteration, timestamps)
- ✅ Git info extraction works (branch, commit)
- ✅ Files-in-scope extraction from intent
- ✅ Polylith boundaries respected (no direct yaml dependency)
- ✅ All 2,174+ test assertions pass
- ✅ Pre-commit hooks pass (lint, format, test)

## Deployment Plan

This is a non-breaking change (completes previously stubbed functionality):

- `mf workflow run <workflow-id>` continues to work (unchanged)
- `mf run <spec-file>` now works (was stubbed, now implemented)
- Existing workflows get automatic decoration (backward compatible)
- New workflows can use markdown format

**Migration:** None required (additive feature)

## Related Issues/PRs

**Depends On:**

- PR #92: Local dev workflow improvements (merged)

**Enables:**

- Meta-meta loop: Using miniforge to build miniforge
- Dogfooding: Developers can now run workflows from spec files
- Inner loop repair: Context for retry workflows
- Evidence bundles: Full provenance tracking

**Related Specs:**

- N5 CLI Implementation
- N6 Evidence & Provenance §6.2 (Evidence Bundle Schema)
- N2 Workflow Execution Model

**Related Docs:**

- `examples/workflows/README.md`: Spec format documentation
- `docs/implementation-status.md`: N5 CLI completion

## Checklist

- [x] Spec parser supports EDN format
- [x] Spec parser supports JSON format
- [x] Spec parser supports Markdown format
- [x] Layer 0 decoration (provenance) implemented
- [x] Layer 1 decoration (context + metadata) implemented
- [x] Layer 2 reserved for future meta-loop
- [x] Knowledge interface extended (YAML parsing)
- [x] Example markdown spec created
- [x] Documentation updated
- [x] All tests passing (2,174+ assertions)
- [x] Pre-commit hooks pass
- [x] Polylith boundaries respected
- [x] No breaking changes
- [x] CLI rebuilt and installed locally

## Conformance

This PR achieves **full implementation** of N5 CLI spec file execution requirements
and establishes the foundation for N6 evidence provenance.

**Before:**
❌ `mf run <spec-file>` printed "TODO"
❌ No spec context decoration
❌ Only EDN/JSON formats supported

**After:**
✅ `mf run <spec-file>` executes workflows
✅ Layered decoration (provenance, context, metadata)
✅ Markdown format support (EDN, JSON, Markdown)
✅ Ready for dogfooding and meta-meta loop

## Future Work (Layer 2)

Reserved for meta-loop implementation:

- `:spec/learning-refs`: Inject learnings from knowledge store
- Pattern matching: Analyze task type/tags → relevant learnings
- Confidence scoring: Weight learnings by success rate
- Knowledge evolution: Update learnings based on outcomes

This will enable the outer loop to automatically improve workflow execution
by injecting relevant learnings before execution starts.
