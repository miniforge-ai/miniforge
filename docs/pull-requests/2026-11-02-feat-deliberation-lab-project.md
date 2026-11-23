<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-lab): a project so CI tests the deliberation workspace

## Overview

Adds `projects/deliberation-lab`, holding the deliberation workspace and the
two components it needs (`anomaly`, `messages`). Until now
`deliberation-workspace` belonged to no project, so CI never ran its tests.

## Motivation

Polylith tests a brick only through a project that contains it. CI uses two
test paths, and neither reaches the development project:

- **Pull requests** run `bb test`, which asks Polylith for changed-or-affected
  projects with `skip:dev`.
- **Pushes to main** run `poly test :all`, which also skips the development
  project.

`deliberation-workspace` sat only on the development project's paths, through
the root `deps.edn`. So its suites never ran in CI. That covers the merged
PRs #2330, #2331, #2337 and #2342. Their PR docs said "`poly test` and the
Polylith check ran only in CI". That was accurate for the Polylith check
only. The suites ran locally, on a malli stand-in, because Clojars is
unreachable from the authoring sandbox.

`projects/deliberation-lab` is the project the lab will ship as (N15). The
lab base and the harness join it in the PRs that add them. Creating it now,
with the workspace alone, makes every later deliberation PR test that
component in CI.

## Changes in Detail

- `projects/deliberation-lab/deps.edn` (new): `deliberation-workspace`,
  `anomaly` and `messages` as local roots, plus Clojure.
- `workspace.edn`: registers the project under alias `dlab`. It lists all
  three components as `:necessary`, the same way `bb-utils` declares its
  components, because no base uses them yet.

## Testing Plan

This PR's own CI is the test. A new project counts as changed, so `bb test`
selects it and runs all 12 deliberation-workspace suites against the real
dependencies for the first time. Reviewers should check that the Test job's
log shows the `deliberation-lab` project and its namespaces.

Locally, on plain Clojure 1.12 with a malli stand-in, the same suites pass:
171 tests, 552 assertions, 0 failures.

## Deployment Plan

None. The project builds no artifact yet.

## Related Issues/PRs

- The deliberation PRs merged so far: #2330, #2331, #2337, #2342.
- Open: #2335 and #2332, which now gain CI coverage through this project,
  and #2334, which will add `deliberation-harness` to it.

## Checklist

- [x] No source change
- [x] Project registered in `workspace.edn`
- [x] Copyright header on the PR doc
