# chore: gitignore `work/failed/` — only `:not-started` and `:finished` belong in git

## Overview

Ignore everything under `work/failed/` (except `.gitkeep`). Failed specs are
local intermediate state, not a tracked spec lifecycle stage.

## Motivation

The `work/` directory tracks specs across two real states:

- **`:not-started`** — specs queued for execution, sitting at `work/<name>.spec.edn`
- **`:finished`** — specs that completed successfully, kept for provenance

Anything else is a transient runtime state. A failed spec at `work/failed/<name>.spec.edn`
is just an in-flight spec that hit an error during execution. If the underlying
work needs to be redone, the source spec returns to `work/` and re-runs from there.

Tracking failed specs in git creates spurious diffs across machines and across
runs, and it implies the failed state is a meaningful checkpoint when it isn't.
This matches the existing convention in the file: dogfood session artifacts and
WIP variants are already gitignored under `work/`.

## Changes

| File/Area | Change |
|-----------|--------|
| `.gitignore` | Add `work/failed/*` with `!work/failed/.gitkeep` to keep the directory present but exclude its contents |

## Testing

- [x] `.gitkeep` remains tracked (verified `work/failed/.gitkeep` is not ignored)
- [x] No code touched

## Checklist

### Required

- [x] All tests pass (n/a — gitignore-only)
- [x] Pre-commit validation passes (no `--no-verify`)
- [x] No commented-out tests
- [x] No giant functions
- [x] Functions are small and composable (n/a)
- [x] New behavior has tests (n/a — gitignore-only)

### Best Practices

- [x] Code follows development guidelines (n/a)
- [x] Linting passes
- [x] README/docs updated if needed (n/a)
- [x] PR doc created
- [x] Focused PR (single concern: housekeeping for `work/failed/`)

## Related

- Surfaced when cleaning a stale `work/failed/<spec>.edn` from a local working
  tree — the file should never have been a candidate for tracking.
