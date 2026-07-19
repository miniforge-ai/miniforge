<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Separate upstream tool pins from product DateVer

## Overview

Remove four false-positive DateVer findings from the GitHub workflows without changing Miniforge release-version policy.

## Motivation

The DateVer scanner currently treats third-party tool releases as Miniforge product versions and also matches the final
three segments inside a valid four-segment DateVer example. Applying its suggested `.0` suffixes would reference
nonexistent upstream artifacts and corrupt valid documentation.

## Changes in Detail

- Centralize the duplicated `deps.clj` upstream release pin outside the product-version workflow surface.
- Remove the unused Windows Polylith CLI installation.
- Describe the release input with the DateVer shape instead of a numeric example that the detector partially matches.

## Testing Plan

- Run the focused Dewey 730 scan and confirm zero findings.
- Run `bb pre-commit`.
- Validate the workflow files with repository CI, including the Windows job.

## Deployment Plan

Merge normally. The pinned `deps.clj` release remains unchanged; the unused Polylith setup is removed from a job that
does not invoke `poly`.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Follows #1419.

## Checklist

- [x] Preserve the `deps.clj` upstream release pin.
- [x] Remove unused Windows Polylith setup.
- [x] Clear all four Dewey 730 workflow findings.
- [x] Pass local pre-commit verification; CI verification runs on the PR.
