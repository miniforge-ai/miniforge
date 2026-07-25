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
requires moving each comment back by hand — and the pre-commit hook
re-normalizes whatever's typed to its own stable same-line-trailing
representation on the next commit regardless (see "Changes in Detail").

## Changes in Detail

- `components/reliability/src/ai/miniforge/reliability/degradation.clj` —
  moved `; data-driven degradation policy` to immediately follow
  `DegradationManager`'s closing `config])`, with the blank line moved to
  *after* the comment instead of before it.
- `components/reliability/src/ai/miniforge/reliability/engine.clj` —
  same move for `; {:windows [:7d] :tiers [:standard :critical]
  :dependency-health {...}}` against `ReliabilityEngine`'s closing
  `config])`.

No other change — headings, metadata, and every other line are untouched.

Note on exact placement: `stratum-lint --fix`'s own normalized form for a
same-line trailing comment is "immediately on the next line, no blank,
block separated from what follows by a blank line" — not literally fused
onto the closing paren's line. Editing either file at all re-triggers the
pre-commit `--fix` pass, which re-normalizes to this shape regardless of
what's hand-typed — confirmed by attempting the literal same-line
fusion first and watching the hook reformat it to this shape on commit.
What matters, and what this PR verifies, is that the comment
unambiguously reads as trailing `DegradationManager`/`ReliabilityEngine`
again (separated from the *next* def by the blank line), not that it's
byte-identical to the pre-Wave-1 source.

## Testing Plan

`bb -m stratum-lint.interface components/reliability` (plain lint, no
`--fix`): same 6 SL003 findings as before this fix (unaffected, Wave 2
scope) — confirms nothing else regressed. `clj-kondo --lint
components/reliability`: 0 errors, 0 warnings. Confirmed via `git show`
on the actual committed blobs (post pre-commit-hook normalization) that
each comment sits directly after its `defrecord`'s closing line with the
blank-line separator on the correct side, matching the pre-Wave-1
source's *attachment* even though the tool's stable output form isn't
byte-identical to it.

## Deployment Plan

Merges to `main`. No functional change — comment placement only.

## Related Issues/PRs

- Follows up on #1462 (Wave 1 `reliability` autofix, merged with this bug
  present).
- Fixes the two known instances of
  [stratum-lint#9](https://github.com/miniforge-ai/stratum-lint/issues/9)
  that already landed on `main` before the upstream fix (#12) merged.

## Checklist

- [x] Both comments verified to unambiguously attach to their originating
      `defrecord` again (not byte-identical to pre-Wave-1 source — see
      note above on the tool's stable normalized form)
- [x] Plain lint findings unchanged (no new/fewer SL003, no regressions)
- [x] `clj-kondo` clean
