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
— a same-line trailing comment misattached to whatever def comes next.
This branch went through several redo cycles before this final version:

1. First pass fixed the two comments by hand, since the sha on `main` at
   the time (pre-#12) couldn't self-heal them — the original same-line
   positional information was already destroyed by the first, buggy
   `--fix` pass from #1462.
2. [#12](https://github.com/miniforge-ai/stratum-lint/pull/12) fixed the
   root cause upstream, but re-verifying it found the fix wasn't
   idempotent — a second `--fix` pass silently re-migrated a same-line
   trailing comment forward again, reproducing #9 exactly — fixed in
   [#13](https://github.com/miniforge-ai/stratum-lint/pull/13).
3. This branch's own `tasks/stratum.clj` had never been rebased onto
   `main` through the pin bumps that followed (#1465, #1470, #1471), so
   it was still pointing at the pre-#13 sha. Reset the branch onto
   current `main` (pin `80699e37`) and re-ran `--fix` fresh — but this
   only reshuffled the comment relative to whatever it happened to be
   adjacent to already, because the *same-line* positional information
   (no newline between `config])` and the comment) had been destroyed
   back in the original Wave 1 autofix, before #12/#13 existed to
   preserve it. A comment already sitting on its own line, with no other
   marker, is structurally indistinguishable from a legitimate leading
   comment — `--fix` has nothing left to reconstruct the original
   attachment from.
4. Automated review (Copilot) correctly caught that this reshuffle still
   left the comment reading as attached to `mode-rank`/the `;; Pipeline
   stages` section rather than to the `defrecord` — same symptom as the
   original bug, different cause. Fixed by hand-restoring the true
   same-line form (`config])  ; comment`) for both files, then
   re-running `--fix`: confirmed zero diff, i.e. the fixed tool
   recognizes and preserves a genuine same-line trailing comment as
   stable — the same shape independently verified on `gate`'s
   `policy.clj`.

## Changes in Detail

- `components/reliability/src/ai/miniforge/reliability/degradation.clj` —
  `; data-driven degradation policy` restored to the same line as
  `DegradationManager`'s closing `config])` (`config])  ; data-driven
  degradation policy`), separated from the next def (`mode-rank`) by a
  blank line.
- `components/reliability/src/ai/miniforge/reliability/engine.clj` —
  same restoration for `; {:windows [:7d] :tiers [:standard :critical]
  :dependency-health {...}}` against `ReliabilityEngine`'s closing
  `config])`.

No other change — headings, metadata, and every other line are untouched.
`stratum-lint --fix` (pinned sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e`)
run against both files after the hand-restoration confirms the same-line
form is stable: zero diff, i.e. the fix doesn't move it again.

## Testing Plan

- Hand-restored the same-line form, then ran `--fix` twice in a row on
  both files; zero diff on both passes (confirms the restoration is
  stable under the tool, not just visually plausible).
- `clj-kondo --lint` on both files: 0 errors, 0 warnings.
- `bb -m stratum-lint.interface components/reliability` (plain lint, no
  `--fix`): same 6 SL003 findings as before this fix (`budget.clj`,
  `degradation.clj`, `dependency_health.clj`, `engine.clj`, `sli.clj`,
  `slo.clj`) — unaffected, Wave 2 scope, confirms nothing else regressed.
- Confirmed via the committed diff that each comment sits on the same
  line as its `defrecord`'s closing `config])`, with a blank line
  separating it from the *next* def — unambiguously trailing
  `DegradationManager`/`ReliabilityEngine`, not leading the following
  def/section.

## Deployment Plan

Merges to `main`. No functional change — comment placement only.
`degradation.clj` and `engine.clj` both carry pre-existing SL003 findings
(7 and 4 layers respectively) that this PR doesn't create or worsen —
committed with `MINIFORGE_STRATUM_BUDGET_MODE=warn` for the same reason
as `gate`'s Wave 1 PR (#1464): a namespace split is Wave 2 scope, not
attempted here.

## Related Issues/PRs

- Follows up on #1462 (Wave 1 `reliability` autofix, merged with this bug
  present).
- Fixes the two known instances of
  [stratum-lint#9](https://github.com/miniforge-ai/stratum-lint/issues/9).
  [#12](https://github.com/miniforge-ai/stratum-lint/pull/12) and
  [#13](https://github.com/miniforge-ai/stratum-lint/pull/13) (idempotency)
  fixed the tool so a same-line trailing comment stays stable once one
  exists, but couldn't reconstruct same-line positioning already lost
  before those fixes landed — this PR's hand-restoration supplies that.
- Same branch-staleness root cause as gate's redo on #1464.

## Checklist

- [x] Idempotency verified directly (two `--fix` passes after hand-
      restoration, zero diff both times)
- [x] Both comments verified to sit on the same line as their
      originating `defrecord`'s closing `config])`, not merely
      adjacent to it
- [x] Plain lint findings unchanged (no new/fewer SL003, no regressions)
- [x] `clj-kondo` clean
