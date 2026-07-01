# Refactor: classify registry invariant throws as fatal-only

## Overview

This PR refines the exceptions-as-data compliance scanner's programmer-error
classification for registry contract failures.

## Motivation

The workflow guard/action registries intentionally throw for namespace-load
contract failures and post-validation resolution invariants. Those sites are
documented programmer-error guards, but the scanner currently reports them as
`:cleanup-needed`, which pollutes the actionable cleanup queue.

## Changes in Detail

- Add fatal-only markers for localized registry message keys:
  `non-keyword`, `non-fn`, and `unregistered-at-resolve`.
- Add regression coverage for the localized `messages/t` call shape used by
  registry invariant throws.
- Preserve plain runtime failure classification as `:cleanup-needed`.

## Testing Plan

- Run focused compliance-scanner tests.
- Run the exceptions-as-data scanner and confirm the workflow guard/action
  registry sites move out of `:cleanup-needed`.
- Run `bb pre-commit`.

## Deployment Plan

No deployment special handling. This only changes local/CI scanner
classification output.

## Related Issues/PRs

- Follows the exceptions-as-data cleanup waves through #1337.

## Checklist

- [x] Focused compliance-scanner tests pass.
- [x] Scanner count drops by the documented registry sites.
- [x] `bb pre-commit` passes.
