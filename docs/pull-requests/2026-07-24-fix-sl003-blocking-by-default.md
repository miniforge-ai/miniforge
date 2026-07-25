# fix: SL003 blocks the commit by default, configurable to warn

## Overview

`bb lint:stratum`'s post-fix check (any stratum-lint finding remaining
after `--fix` — in practice always SL003, over the 3-layer budget)
printed a non-blocking advisory and let the commit through regardless.
It now fails the commit by default, same as every other rule 210
violation. `MINIFORGE_STRATUM_BUDGET_MODE=warn` opts back into the old
print-and-continue behavior.

## Motivation

Flagged directly: PR #1464 (`gate`'s Wave 1 autofix) has 5 files over the
layer budget, and CI was green regardless — because CI never runs
stratum-lint at all (only `bb lint:clj:all`/clj-kondo), and the one place
that does, the pre-commit gate, treated this specific violation as
advisory-only. That's the same "rule exists but doesn't actually enforce
anything" pattern this entire cleanup effort (`work/stratum-lint-baseline-2026-07-24.md`)
was started to eliminate — an over-budget file is a genuine, acknowledged
rule 210 violation, not a lesser one `--fix` happens not to resolve
mechanically.

Chosen scope: blocking everywhere, immediately, not just for `gate`'s PR
or gated behind finishing Wave 2 first. Configurable via env var rather
than hardcoded, so a team mid-way through clearing a large pre-existing
backlog (which is exactly this repo's situation right now — Wave 2 hasn't
started) has an explicit, visible opt-out rather than the check being
silently toothless for everyone.

**Immediate consequence:** any commit touching one of the ~23 files
already flagged with SL003 (across merged Wave 1 PRs for
`compliance-scanner`, `reliability`, `decision`, plus the still-open
`gate` and `adapter-claude-code`) is now blocked until that file is
actually split (Wave 2) — or the commit sets
`MINIFORGE_STRATUM_BUDGET_MODE=warn` explicitly.

## Changes in Detail

- `tasks/stratum.clj`:
  - `budget-mode-env`/`warn-only?` (new, Layer 0): reads
    `MINIFORGE_STRATUM_BUDGET_MODE`; `"warn"` opts into non-blocking,
    anything else (including unset) blocks.
  - `advisory-lint!` renamed to `post-fix-lint!` and now fails the
    commit (`System/exit 1`) on any remaining finding unless
    `warn-only?` — an unexpected exit code (neither 0 nor 1) still
    always fails the commit regardless of mode, unchanged from before.
  - `autofix-and-restage!`'s call site updated to the new name.

## Testing Plan

Functional, in an isolated scratch repo: a file with 4 real layers
(1 over budget) staged and touched.

- Default (no env var): `bb lint:stratum` exits 1, prints `❌`.
- `MINIFORGE_STRATUM_BUDGET_MODE=warn`: same file, same finding, prints
  `⚠️`, exits 0, file still gets re-staged with the mechanical fix
  applied.
- Verified `tasks/stratum.clj`/`tasks/lint.clj` themselves still lint
  clean (self-referential — this file is exactly the kind this check
  covers).

## Deployment Plan

Merges to `main`. Takes effect on the next commit touching a `.clj`/
`.cljc` file with a remaining stratum-lint finding. Follow-on: `gate`'s
open PR (#1464) needs an actual Wave 2 namespace split for its 5
over-budget files before it can be committed further under the new
default — flagged separately, not attempted here.

## Related Issues/PRs

- Directly requested in response to `work/stratum-lint-baseline-2026-07-24.md`
  Wave 1 review of PR #1464.
- Affects: `fix/stratum-lint-wave1-gate` (#1464) — blocked pending Wave 2
  for that component.

## Checklist

- [x] Both modes (default-block, opt-in-warn) verified functionally
- [x] Unexpected-exit-code case still always fails regardless of mode
      (unchanged behavior, re-verified)
- [x] This file and `tasks/lint.clj` lint clean under the tool this
      change governs
