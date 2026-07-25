# fix: bump stratum-lint pin to pick up two correctness fixes

## Overview

Bumps `tasks/stratum.clj`'s pinned `stratum-lint` sha from `acd82a2f`
(current on `main`, includes the SL001 scoping and comment-block fixes
from #1459) to `8a40bdea`, which additionally includes:

- [stratum-lint#8](https://github.com/miniforge-ai/stratum-lint/pull/8):
  `--fix` keeps a `#_{...}` reader-discard (e.g.
  `#_{:clj-kondo/ignore [...]}`) attached to its def instead of
  detaching it.
- [stratum-lint#10](https://github.com/miniforge-ai/stratum-lint/pull/10):
  `--fix` orders a `defrecord`/`deftype` before any def calling its
  auto-generated constructor (`->Name`/`map->Name`) — the old pin could
  produce code that fails to compile.

## Motivation

Found mid-Wave-1: the pre-commit gate (`bb lint:stratum`, via
`tasks/stratum.clj`) runs `--fix` on every commit touching a staged
`.clj`/`.cljc` file, using its *own* pinned sha — independent of whatever
sha someone ran by hand beforehand. Against `components/adapter-claude-code`
(which has both a `#_{:clj-kondo/ignore [...]}` directive and a
`defrecord`), the pre-commit hook's stale pin silently re-introduced both
bugs into already-hand-verified, correctly-fixed staged files at commit
time — confirmed live via a failing `test:graalvm` step (`Unable to
resolve symbol: ->ClaudeCodeAdapter`) after a commit that had, moments
earlier, verified clean.

Every other Wave 1 PR so far (`compliance-scanner`, `reliability`,
`decision`, `gate`) happened not to hit this — none combine a reader-
discard with a problematic constructor-before-definition ordering — but
any future component that does would silently corrupt on commit without
this bump. This is now the priority blocker for continuing Wave 1.

## Changes in Detail

- `tasks/stratum.clj`: `stratum-lint-deps`'s pinned sha,
  `acd82a2f` → `8a40bdea`.

## Testing Plan

Confirmed the sha resolves via `bb -Sdeps`. This change lints itself via
the pre-commit hook it wires into — a clean pre-commit run against this
one-line change is the relevant end-to-end confirmation.

## Deployment Plan

Merges to `main` immediately — every commit touching a `.clj`/`.cljc`
file goes through this pin until it's bumped.

## Related Issues/PRs

- Fixes consumed: [stratum-lint#8](https://github.com/miniforge-ai/stratum-lint/pull/8), [#10](https://github.com/miniforge-ai/stratum-lint/pull/10)
- Blocks: `fix/stratum-lint-wave1-adapter-claude-code` (currently stuck on
  this exact staleness)
- Part of: `work/stratum-lint-baseline-2026-07-24.md`, Wave 1

## Checklist

- [x] Sha resolves via `bb -Sdeps`
- [x] Pre-commit hook (which this file itself wires into) passes clean
