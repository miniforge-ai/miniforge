# fix: restore two misplaced comments in components/reliability

## Overview

Restores two same-line trailing comments in `components/reliability` that
`stratum-lint --fix` relocated to the wrong place in the earlier Wave 1
autofix PR (#1462, merged): `degradation.clj`'s `; data-driven degradation
policy` and `engine.clj`'s `; {:windows [:7d] ...}`, both documenting the
last field of a `defrecord`, ended up as leading comments on an unrelated
following def instead of staying attached to the `defrecord` they
describe.

## Motivation

This was [stratum-lint#9](https://github.com/miniforge-ai/stratum-lint/issues/9)
— a same-line trailing comment misattached to whatever def comes next —
fixed upstream in [#12](https://github.com/miniforge-ai/stratum-lint/pull/12)
and picked up on `main`'s pin in #1465. Re-running `--fix` against
`components/reliability` at the new sha does **not** self-heal these two
files: the original same-line positional information (no newline between
the closing paren and the comment) was already destroyed by the first,
buggy fix pass — the comment is now on its own line, indistinguishable
from a legitimately-placed leading comment. Confirmed empirically:
re-running `--fix` at the new sha produces a zero-diff no-op. Fixing this
requires restoring the original same-line placement by hand, once, in
each file it already hit.

## Changes in Detail

- `components/reliability/src/ai/miniforge/reliability/degradation.clj` —
  moved `; data-driven degradation policy` back onto the same line as
  `DegradationManager`'s closing `config])`, matching the pre-Wave-1
  source exactly.
- `components/reliability/src/ai/miniforge/reliability/engine.clj` —
  moved `; {:windows [:7d] :tiers [:standard :critical]
  :dependency-health {...}}` back onto the same line as
  `ReliabilityEngine`'s closing `config])`, matching the pre-Wave-1
  source exactly.

No other change — headings, metadata, and every other line are untouched.

## Testing Plan

`bb -m stratum-lint.interface components/reliability` (plain lint, no
`--fix`): same 6 SL003 findings as before this fix (unaffected, Wave 2
scope) — confirms nothing else regressed. `clj-kondo --lint
components/reliability`: 0 errors, 0 warnings. Diffed both restored lines
against the pre-Wave-1 (`main` before #1462) source: byte-identical.

## Deployment Plan

Merges to `main`. No functional change — comment placement only.

## Related Issues/PRs

- Follows up on #1462 (Wave 1 `reliability` autofix, merged with this bug
  present).
- Fixes the two known instances of
  [stratum-lint#9](https://github.com/miniforge-ai/stratum-lint/issues/9)
  that already landed on `main` before the upstream fix (#12) merged.

## Checklist

- [x] Both restored comments verified byte-identical to pre-Wave-1 source
- [x] Plain lint findings unchanged (no new/fewer SL003, no regressions)
- [x] `clj-kondo` clean
