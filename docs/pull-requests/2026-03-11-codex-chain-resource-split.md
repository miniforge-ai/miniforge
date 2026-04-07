# PR: Move software-factory chain resources behind app composition

## Summary

This PR removes the last shipped software-factory chain resource from the
shared `workflow` component.

`spec-to-pr` now lives in a dedicated resource-only component,
`workflow-chain-software-factory`, and the generic chain loader now aggregates
all `chains/` resource roots on the active classpath.

## Changes

### App-owned chain resources

- Added `components/workflow-chain-software-factory`
- Moved `spec-to-pr-v1.0.0.edn` out of `components/workflow/resources/chains/`

### Generic chain loading

- Updated `ai.miniforge.workflow.chain-loader/list-resource-names`
- Chain discovery now scans every `chains/` directory on the classpath instead
  of only the first matching resource root

## Why

After splitting workflow families, phase implementations, and selector
configuration into app-owned components, the remaining software-factory leak was
the shipped `spec-to-pr` chain in the kernel workflow component.

This keeps chain loading generic while moving chain ownership to the composed
application layer.

## Test coverage

Existing behavior coverage remains in:

- `ai.miniforge.workflow.chain-loader-test`

New seam-specific coverage was added for:

- composed discovery across multiple `chains/` roots
- enumeration of both app-owned and test-only chain resources

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow.chain-loader-test) ..."`
- `bb test`
- `bb test:integration`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`

## Follow-up

The next narrow seam is the remaining software-factory helper logic around
chain recommendation and related workflow-facing helpers, not chain resources.
