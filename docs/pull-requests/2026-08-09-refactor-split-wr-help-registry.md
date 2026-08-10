# refactor(cli): split workflow_runner/help/registry.clj — flag specs vs. subcommand registry (rule 210)

## Overview

`bases/cli/src/ai/miniforge/cli/workflow_runner/help/registry.clj` measured 6
real strata against the rule-210 budget of 3 (stratum-lint SL003). Move-only
split into two namespaces along the seam the file already had: the flag-spec
data that describes *what flags a subcommand takes*, and the registry that binds
those specs to subcommand keys and derives the parent-level `--help` listings.

| Namespace | Vocabulary | Strata |
|---|---|---|
| `…help.flags` (existing) | the `--help`/`-h` flag: its definition, adding it to a `:spec`, stripping it, rendering the `Options:` table | 3 |
| `…help.flag-specs` (new) | one babashka.cli `:spec` per workflow-runner subcommand | 1 |
| `…help.registry` (rewritten) | the `subcommands` map, lookups over it, group-listing inputs | 3 (was 6) |

Dependency direction (one-way, no cycles):

```text
help.flags ──> help.flag-specs ──> help.registry ──> help.clj / main.clj
      └──────> help.usage
```

## Motivation

Rule 210 (`standards/miniforge/languages/clojure`, per-file stratified design):
a file wanting a fourth band is the signal to split the namespace. `registry.clj`
wanted six. It was the last SL003 offender left in the `workflow_runner/help/`
tree after #1719 extracted `help/flags.clj` and `help/usage.clj`.

This also closes a finding deferred from #1719: `help-flag-keys` (in
`help/flags.clj`) and `help-flag-spec` (in `registry.clj`) were two halves of one
idea — what the `--help` flag *is*. They now sit together at Layer 0 of
`help/flags.clj`, alongside the two operations over it (`with-help-flag` adds it,
`without-help-flag` strips it). `flags.clj` stays inside the 3-stratum budget,
and `help-flag-spec` stays private — moving `with-help-flag` with it meant the
API surface did not have to widen.

## Changes in Detail

**`help/flag_specs.clj` (new, 1 stratum).** The nine `*-flag-spec` defs, moved
verbatim apart from `with-help-flag` becoming `flags/with-help-flag`. Because
every def now references only a var in another namespace, they are all leaves of
this file's reference graph and sit at a single Layer 0.

**`help/flags.clj` (3 strata, unchanged count).** Gains `help-flag-spec`
(Layer 0, still `^:private`) and `with-help-flag` (Layer 1), both moved verbatim
from `registry.clj`. Namespace docstring updated to cover both concerns.

**`help/registry.clj` (3 strata, down from 6).** Keeps its whole public surface:
`subcommands`, `spec-for`, `entry-for`, `workflow-subcommand-keys`,
`chain-subcommand-keys`, `workflow-group-help`, `chain-group-help`. Relative
order of the surviving forms is unchanged; only the `Layer N` headings and
`^{:stratum n}` metadata are recomputed:

- Layer 0 — `workflow-subcommand-keys`, `chain-subcommand-keys`,
  `subcommand-leaf`, `subcommands`
- Layer 1 — `spec-for`, `entry-for`, `group-subcommand-rows`
- Layer 2 — `workflow-group-help`, `chain-group-help`

No re-exports were needed. `with-help-flag` and `help-flag-spec` are the only
vars that left the namespace, and neither had a caller outside it (verified by
grep over `bases/`, `components/`, `projects/`).

## Testing Plan

- `stratum-lint` (pin `bef8657a`) plain: clean on all three files.
- `stratum-lint --fix` dry run on scratch copies: zero changes to any of the
  three, so the hand-written headings and metadata match the strata the linter
  computes from each file's reference graph. (`--fix` behaviour on these files
  was itself sanity-checked against a deliberately mis-tagged probe copy, which
  it correctly collapsed.)
- `clj-kondo`: 0 errors, 0 warnings on the touched files.
- `help_test.clj` + `help/registry_test.clj`: 13 tests, 76 assertions,
  0 failures, 0 errors. No `with-redefs` targets moved, so no test edits.
- Pre-commit gate (342 tests / 1291 assertions smoke + GraalVM compat) passed on
  both commits.

## Deployment Plan

No behaviour change — every moved form is verbatim. Ships with the ordinary
merge to `main`.

## Related Issues/PRs

- #1719 split `workflow_runner/help.clj` into `help/flags.clj` + `help/usage.clj`
  and deferred both this file and the `help-flag-spec` reunification.
- Same wave: #1662–#1667, #1720, #1721, #1724.
- Rules: `standards/miniforge/languages/clojure` (210),
  `standards/miniforge/foundations/stratified-design` (001).

## Checklist

- [x] `registry.clj` down to 3 strata, public surface intact
- [x] `flag_specs.clj` created with the Apache header
- [x] `help-flag-spec` reunited with `help-flag-keys`; `flags.clj` still ≤3 strata
- [x] `^{:stratum n}` metadata agrees with `Layer N` headings
- [x] stratum-lint plain + `--fix` dry run clean
- [x] clj-kondo clean
- [x] `help_test` / `help/registry_test` green
