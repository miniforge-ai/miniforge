<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Sealed evidence manager publication

## Scope and dependencies

Application boundary extracted from #1960. Base: canonical contract #1959;
retarget main after that prerequisite merges. No deployment step is required.
The CLI adapter remains in #1960.

## Changes and standards review

Expose published-bundle validation through the component interface. Require a
valid canonical seal, not only a bundle-shaped map. Export the exact validated
snapshot as precision-preserving EDN. Invalid or unsealed evidence leaves the
destination unchanged. This proves content integrity, not signer authority.
Keep pure validation separate from filesystem and exception handling. Reuse
canonical schemas, codec and test fixtures. Preserve interruption and fatal errors.
The existing project tests are regrouped by actual dependencies; their only
behavior change is rejection of legacy unsealed export.

## Verification and merge gates

Run all three evidence consumers, the project interface tests, CLI build and
packaged publication regressions serially. Check standards, kondo, strata and
Polylith. Require clean current-head review and all CI checks before merge.
