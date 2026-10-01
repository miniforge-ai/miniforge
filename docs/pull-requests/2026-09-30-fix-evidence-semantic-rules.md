<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: shared semantic evidence rules

## Overview and motivation

N6 section 2.4.1 requires balanced creates and destroys for migration intent.
The producer currently checks only each count independently. Canonical validation
in #1959 must use the same cross-field rule rather than certifying contradictory
producer output. This independent main-based prerequisite keeps both PRs bounded.

## Changes and standards

Extract a pure vocabulary for count rules, behavior inference and failed rule IDs.
Use it in the existing producer, with one localized violation constructor and
small analysis/composition functions instead of a mutable violation accumulator.
Keep the protocol response compatible. Canonical validation will reuse these rules
after this foundation merges; do not duplicate its policy.

## Testing plan

Test balanced/unbalanced migrations, existing intent rules and resource analysis
on JVM and packaged Babashka. Run consumer suites serially, standards, normal
hooks, review and all CI before merge.
All three evidence consumers passed; rebuilt packaged tests passed 12 tests with
72 assertions. Kondo and explicit strata checks passed; the component standards
scan reported no findings. The refactor retains absent/nil-content behavior.
Review added exact-message compatibility regressions for all three count kinds;
catalog parameters preserve their existing unqualified names.

## Deployment and related work

No external provider operations or data migration. Prerequisite of #1959 and its
dependent evidence publication/finalization PRs. Historical seals are not changed.

## Checklist

- [x] Shared rule and producer regressions pass
- [ ] Standards, review and CI settle
