# refactor: Extract Canonical Algorithms Component

## Overview

Extracts shared graph algorithms (DFS, graph validation) from knowledge and policy-pack components into a
reusable algorithms component. Reduces code duplication and provides canonical implementations for common
algorithms used across the codebase.

## Motivation

During review of PRs #14 (transitive-trust-rules) and #15 (pack-dependency-validation), identical DFS
implementations were found in both knowledge and policy-pack components:

- `knowledge/trust.clj`: DFS for trust graph traversal and tainted content detection
- `policy-pack/rules/pack_dependency_validation.clj`: DFS for circular dependency detection

The duplicate implementations (200+ lines total) were functionally equivalent but maintained separately,
creating technical debt and potential for divergence. This PR creates a canonical algorithms component to
eliminate duplication and provide consistent graph traversal across components.

## Changes in Detail

### New Files

**`components/algorithms/src/ai/miniforge/algorithms/graph.clj`** (272 lines)

- `dfs` - Core depth-first search with pluggable hook functions
  - `on-visit-fn` - Called when visiting each node
  - `on-cycle-fn` - Called when cycle detected
  - `on-missing-fn` - Called when missing dependency found
- `dfs-find` - Find first node matching predicate using DFS
- `dfs-validate-graph` - Validate graph structure (cycles, missing nodes)
- `dfs-collect` - Collect values during traversal with configurable collection points
- Pure functional implementation using loop/recur (no atoms in algorithm core)

**`components/algorithms/src/ai/miniforge/algorithms/interface.clj`** (48 lines)

- Public API exports for graph algorithms
- Clean interface for component consumers

**`components/algorithms/deps.edn`**

- Minimal component definition (no external dependencies)
- Standard Polylith structure with test paths

### Modified Files

**`components/knowledge/src/ai/miniforge/knowledge/trust.clj`** (-126 lines)

- Removed local DFS implementations (Layer 2: Generic DFS utilities)
- Added `ai.miniforge.algorithms.interface` require as `alg`
- Updated `validate-cross-trust-references` to use `alg/dfs-validate-graph`
- Updated `validate-tainted-isolation` to use `alg/dfs-find`
- Reduced from 311 lines to 185 lines

**`components/knowledge/deps.edn`**

- Added `ai.miniforge/algorithms {:local/root "../algorithms"}` dependency

**`components/policy-pack/src/ai/miniforge/policy_pack/rules/pack_dependency_validation.clj`** (-97 lines)

- Removed local DFS implementations (dfs and dfs-collect-all functions)
- Added `ai.miniforge.algorithms.interface` require as `alg`
- Updated `detect-circular-dependencies` to use `alg/dfs-collect` with `:cycle` parameter
- Reduced from 276 lines to 179 lines

**`components/policy-pack/deps.edn`**

- Added `ai.miniforge/algorithms {:local/root "../algorithms"}` dependency

**`deps.edn`** (workspace root)

- Added algorithms to `:dev` alias (paths and deps)
- Added algorithms to `:test` alias (paths and deps)
- Added algorithms to `:conformance` alias (paths and deps)

**`projects/miniforge/deps.edn`**

- Added `ai.miniforge/algorithms {:local/root "../../components/algorithms"}` dependency

## Code Quality Improvements

1. **Elimination of duplication**: Removed 200+ lines of duplicate DFS code
2. **Canonical implementation**: Single source of truth for graph algorithms
3. **Hook-based architecture**: Flexible behavior customization without code duplication
4. **Pure functional**: Core algorithms use loop/recur, no mutable state
5. **Better separation of concerns**: Algorithm logic separate from domain logic

## Testing Plan

All existing tests pass with no modifications required:

```bash
clojure -M:poly test :all
```

Results:

- 1,924 assertions across all components
- 0 failures, 0 errors
- Execution time: 5 seconds

Key tests exercising the algorithms component:

- `ai.miniforge.knowledge.trust-test`: 59 assertions (trust graph validation)
- `ai.miniforge.policy-pack.rules.pack-dependency-validation-test`: Tests circular dependency detection

No new tests required - the algorithms component is tested through its consumers.

## Deployment Plan

- No special deployment steps; merge and release with next version
- Breaking changes: None (pure refactoring)
- Component structure follows Polylith conventions
- All transitive dependencies remain unchanged

## Related Issues/PRs

- PR #14: feat-transitive-trust-rules (knowledge component DFS usage)
- PR #15: feat-pack-dependency-validation (policy-pack component DFS usage)
- Code review discussion: Extract shared DFS to reusable component

## Architecture Benefits

1. **Reusability**: Any component needing graph algorithms can use algorithms component
2. **Consistency**: All graph traversal uses same canonical implementation
3. **Testability**: Algorithm correctness validated through multiple consumer test suites
4. **Maintainability**: Single location for algorithm improvements and bug fixes
5. **Performance**: Optimizations benefit all consumers automatically

## Checklist

- [x] algorithms component created with proper Polylith structure
- [x] DFS implementations extracted and generalized
- [x] knowledge component refactored to use algorithms
- [x] policy-pack component refactored to use algorithms
- [x] Workspace deps.edn updated with algorithms paths
- [x] Project deps.edn updated with algorithms dependency
- [x] All tests passing (1,924 assertions, 0 failures)
- [x] No behavior changes - pure refactoring
- [x] Code duplication eliminated (200+ lines removed)
