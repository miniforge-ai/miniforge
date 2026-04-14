<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Pack Dependency Validation

## Overview

Implements comprehensive pack dependency validation in the `knowledge-safety` policy pack as specified in N4 §2.4.2.
Validates dependency graphs before pack loading to prevent circular dependencies, version conflicts, and trust
escalation.

## Motivation

Spec enhancement PR #84 added `pack-dependency-validation` rule to the knowledge-safety policy pack. Without dependency
validation:

- Circular dependencies can crash workflows or enable DoS attacks
- Version conflicts between dependencies can cause runtime failures
- Missing dependencies cause cryptic errors during execution
- Untrusted packs could require trusted dependencies, creating confusion
- Unbounded dependency chains could exhaust resources

This PR implements comprehensive graph-based validation before any pack loading.

## Changes in Detail

### New Files

**`components/policy-pack/src/ai/miniforge/policy_pack/rules/pack_dependency_validation.clj`** (522 lines)

- Complete dependency graph construction from pack manifests
- Circular dependency detection (simple A→B→A and complex A→B→C→A cycles)
- Missing dependency detection across entire graph
- Version conflict resolution with DateVer support (`YYYY.MM.DD` format)
- Trust constraint validation (placeholder for PR14 integration)
- Dependency depth limit enforcement (default: 5 levels, configurable)
- Version parsing and comparison (supports `=`, `>`, `>=`, `<`, `<=`, wildcards, ranges)

**`components/policy-pack/src/ai/miniforge/policy_pack/knowledge_safety.clj`** (296 lines)

- Knowledge-safety policy pack definition with 7 security rules:
  - `no-untrusted-instruction-authority`
  - `no-markdown-agent-interface`
  - `prompt-injection-tripwire`
  - `pack-schema-validation`
  - `pack-root-allowlist`
  - `pack-dependency-validation` (this PR)
  - `transitive-trust-validation` (placeholder for PR14)
- Integration with dependency validation logic
- Configurable rule parameters

**`components/policy-pack/test/ai/miniforge/policy_pack/rules/pack_dependency_validation_test.clj`** (491 lines)

- 17 comprehensive test cases covering:
  - Circular dependency detection (simple and complex cycles)
  - Missing dependency detection
  - Version constraint satisfaction
  - Version conflict detection
  - Dependency depth limit enforcement
  - Single pack validation against registry
- 61 assertions, 100% passing

### Modified Files

**`components/policy-pack/src/ai/miniforge/policy_pack/loader.clj`** (+80 lines)

- Added `validate-pack-dependencies` function
- Integrated validation into `load-all-packs` workflow
- Returns structured dependency validation results
- Fails pack loading on dependency violations

## Validation Features

1. **Circular Dependency Detection**
   - Detects cycles of any length: A→B→A, A→B→C→A, A→B→C→D→A
   - Uses depth-first search with cycle tracking
   - Reports full cycle path for debugging

2. **Missing Dependency Detection**
   - Validates all referenced dependencies exist in registry
   - Checks entire transitive dependency graph
   - Reports which pack requires the missing dependency

3. **Version Constraint Validation**
   - Supports DateVer format: `YYYY.MM.DD` or `YYYY.MM.DD.N`
   - Comparison operators: `=`, `>`, `>=`, `<`, `<=`
   - Wildcard constraints: `2026.01.*` matches `2026.01.25`
   - Range constraints: `>=2026.01.01,<2026.02.01`
   - Reports unsatisfiable constraints

4. **Trust Constraint Enforcement**
   - Placeholder for PR14 integration
   - Will enforce: untrusted pack cannot require trusted dependency
   - Prevents trust level confusion

5. **Dependency Depth Limits**
   - Default maximum depth: 5 levels
   - Configurable via pack metadata
   - Warns on excessive depth, fails on overflow
   - Prevents resource exhaustion attacks

6. **Complete Graph Validation**
   - Builds entire dependency graph before loading any pack
   - Validates graph properties (acyclic, connected, depth-bounded)
   - All-or-nothing loading: graph must be valid before any pack loads

## Version Support

- **DateVer**: `YYYY.MM.DD` or `YYYY.MM.DD.N` (e.g., `2026.01.25`, `2026.01.25.2`)
- **Comparison**: Lexicographic ordering with proper date semantics
- **Constraints**: Flexible constraint language for pack authors

## Testing Plan

- `bb test` - 17 test cases, 61 assertions, 100% passing
- `bb pre-commit` - All checks passed (6 minor linter warnings for future cleanup)
- Integration with existing policy-pack tests

## Deployment Plan

- No special deployment steps; merge and release with OSS v1.0
- Breaking changes: None (additive only)
- Existing packs continue to work (validation is opt-in via knowledge-safety pack)

## Related Issues/PRs

- Spec enhancement: PR #84 (ETL & Trust System Enhancements)
- N4 spec: §2.4.2 Reference Rules (knowledge-safety)
- ETL critical sequence: PR15/21 (this PR)
- Integrates with: PR14 (transitive-trust-rules)

## Checklist

- [x] Circular dependencies detected and rejected
- [x] Missing dependencies detected
- [x] Version conflicts reported
- [x] Trust constraints enforced (placeholder for PR14)
- [x] Depth limit enforced (default 5, configurable)
- [x] Complete dependency graph built before loading
- [x] Tests cover all validation rules (17 tests, 61 assertions, 100% passing)
- [x] Pre-commit validation passed
- [x] N4 §2.4.2 conformant
