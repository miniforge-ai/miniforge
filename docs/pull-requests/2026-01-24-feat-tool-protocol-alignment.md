<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR2: Align Tool Protocol with N1 Spec

**Branch:** `feat/tool-protocol-alignment`  
**Date:** 2026-01-24  
**Layer:** Foundations (Protocol Extension)  
**Part of:** N1 Architecture Completion (PR 2 of 8)  
**Depends On:** None  
**Blocks:** PR5 (Tool invocation tracking)

## Overview

Aligns the Tool protocol with N1 Architecture Spec §2.5.1 by adding `validate-args` and `get-schema`
methods. These methods enable proper argument validation and schema introspection, which are essential
for tool invocation tracking and evidence bundles.

## Motivation

**N1 Spec Requirement (§2.5.1):**

The spec requires tools to implement validate-args and get-schema methods. Currently, there's a free
function `validate-params` but it's not part of the protocol, making it harder to introspect and track
tool usage properly.

This is needed for:

- Tool argument validation before execution
- Schema introspection for dynamic tool registration
- Tool invocation tracking in evidence bundles (PR5)
- MCP compatibility (future)

## Changes in Detail

### 1. Protocol Extension

**File:** `components/tool/src/ai/miniforge/tool/core.clj`

Add methods to Tool protocol:

```clojure
(defprotocol Tool
  (tool-id [this])
  (tool-info [this])
  (execute [this params context])
  (validate-args [this args]    ; NEW
    "Validate arguments against tool schema.
     Returns {:valid? boolean :errors [...]}") 
  (get-schema [this]            ; NEW
    "Return tool parameter schema.
     Returns parameter spec map"))
```

### 2. Update FunctionTool Implementation

Implement new methods in FunctionTool record:

- `validate-args` - Delegates to validate-params logic
- `get-schema` - Returns the parameters field

### 3. Update Existing Tools

Update any existing tool implementations to add the new methods.

### 4. Tests

Add test cases for:

- Protocol methods exist and are callable
- validate-args returns correct validation results
- get-schema returns parameter schema
- Backward compatibility maintained

## Testing Plan

```bash
# Run tool component tests
bb test components/tool

# Run full test suite
bb test

# Verify pre-commit
bb pre-commit
```

### Test Coverage

- ✅ validate-args protocol method works
- ✅ get-schema protocol method works
- ✅ Existing tools implement new methods
- ✅ Validation logic unchanged
- ✅ Backward compatibility maintained

## Deployment Plan

This is an **additive change** with migration path:

- Existing tools continue to work
- Default implementations can be provided
- No breaking changes for current tool users

## Related Issues/PRs

**Depends On:**

- None

**Blocks:**

- PR5: Tool invocation tracking

**Related:**

- N1 Architecture Spec §2.5.1 (Tool Protocol)
- docs/N1-implementation-status.md
- docs/N1-completion-pr-plan.md

## Checklist

- [ ] Protocol methods added to Tool
- [ ] FunctionTool implements new methods
- [ ] Tests added and passing
- [ ] Pre-commit hooks pass
- [ ] N1 spec conformance verified
- [ ] Backward compatibility maintained

## Conformance

This PR achieves **full conformance** with N1 Architecture Spec §2.5.1 for the Tool protocol.

**Before:** ❌ Missing validate-args and get-schema methods  
**After:** ✅ Full protocol conformance
