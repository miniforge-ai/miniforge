# PR: Loop Component

**Branch:** `feat/loop-component`
**Date:** 2026-01-19

## Summary

Implements the `loop` component for miniforge.ai. Provides the inner control loop
(Generate → Validate → Repair) with validation gates and repair strategies,
plus outer loop stubs for SDLC phase management.

## Changes

### New Files

- `components/loop/deps.edn` - Component dependencies (schema, logging, malli)
- `components/loop/src/ai/miniforge/loop/schema.clj` - Malli schemas for:
  - `InnerLoopState`, `GateResult`, `RepairResult`, `LoopMetrics`
  - State enums and termination conditions
- `components/loop/src/ai/miniforge/loop/gates.clj` - Validation gates with:
  - `Gate` protocol: `check`, `gate-id`, `gate-type` methods
  - Built-in gates: `syntax-gate`, `lint-gate`, `test-gate`, `policy-gate`
  - `custom-gate` for arbitrary validation
  - Gate runner with optional fail-fast behavior
  - Gate sets: `default-gates`, `minimal-gates`, `strict-gates`
- `components/loop/src/ai/miniforge/loop/repair.clj` - Repair strategies with:
  - `RepairStrategy` protocol: `can-repair?`, `repair` methods
  - Built-in: `llm-fix-strategy`, `retry-strategy`, `escalate-strategy`
  - Repair orchestration with multi-attempt support
- `components/loop/src/ai/miniforge/loop/inner.clj` - Inner loop with:
  - State machine: `PENDING → GENERATING → VALIDATING → (COMPLETE | REPAIRING)`
  - Step functions: `generate-step`, `validate-step`, `repair-step`
  - Full loop runners: `run-inner-loop`, `run-simple`
  - Termination conditions: gates passed, max iterations, budget exhausted
- `components/loop/src/ai/miniforge/loop/outer.clj` - Outer loop stubs (P1) with:
  - Phase definitions for SDLC cycle
  - State management and phase transitions
- `components/loop/src/ai/miniforge/loop/interface.clj` - Public API exports
- `components/loop/test/ai/miniforge/loop/*_test.clj` - Comprehensive test suite

### Modified Files

- `deps.edn` - Added loop component paths to dev/test aliases

## Design Decisions

1. **Gate Protocol**: Validation is abstracted behind a protocol, enabling custom
   gates (security scanners, style checkers, etc.).

2. **Repair Strategy Pattern**: Different repair approaches (LLM fix, retry, escalate)
   are encapsulated in strategies, enabling flexible error recovery.

3. **Step-by-Step Control**: Inner loop can be run one step at a time for debugging
   or integrated into larger control flows.

4. **Termination Guards**: Multiple termination conditions (iterations, budget, success)
   prevent runaway loops.

## Inner Loop Flow

```
PENDING → GENERATING → VALIDATING
                          ↓
                    gates pass? ──yes──→ COMPLETE
                          ↓ no
                    REPAIRING
                          ↓
                    max attempts? ──yes──→ FAILED
                          ↓ no
                    ←── back to GENERATING
```

## Testing

```bash
clojure -M:poly test :dev
# All assertions pass for loop component
```

## Dependencies

- `ai.miniforge/schema` - Type definitions (local)
- `ai.miniforge/logging` - Structured logging (local)
- `metosin/malli` - Schema validation
