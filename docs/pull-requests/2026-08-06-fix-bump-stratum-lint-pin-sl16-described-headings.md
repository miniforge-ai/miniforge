<!--
  Title: Bump stratum-lint pin for the described-heading recognition fix
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: bump stratum-lint pin for the described-heading recognition fix

## Overview

Bumps `tasks/stratum.clj`'s pinned `stratum-lint` sha from `bef8657a`
(current on `main`) to `ccde3a11`, which includes
[stratum-lint#16](https://github.com/miniforge-ai/stratum-lint/pull/16):
`heading-re` never recognized a `;--- Layer N: <description>` heading
(a described sub-heading, e.g. `;--- Layer 1: Branch Resolution`) as
a heading at all — silently invisible to every check, not a
miscount. The def underneath silently inherited whatever the last
*recognized* (bare) heading said instead.

## Motivation

Found resolving Copilot review comments on
[#1485](https://github.com/miniforge-ai/miniforge/pull/1485) (the
`workflow` component's `dag_orchestrator.clj` split): a described
heading sat over `:stratum 0` defs and neither plain lint nor `--fix`
ever flagged it, despite the file already having `^{:stratum n}`
metadata that plain lint should have cross-checked against the
heading. Confirmed empirically before touching the tool: reverted the
hand-fix, staged the known-mismatched file, re-ran `--fix` directly —
exit 0, zero findings, byte-identical output.

Root cause: `heading-re` = `^;+\s*-*\s*Layer\s+(\d+)\s*-*\s*$`,
anchored to end right after the number (plus optional dashes) — any
`: <description>` suffix fails the match, so `heading-layer` returns
nil and `collect` never registers the line as a heading at all. This
is a different bug from the already-known `;;---- Layer N: <label>`
decorative-banner pattern (that one IS correctly recognized-and-
ignored per the tool's own spec, just cosmetically ugly) — this one is
a genuine false negative on a heading shape used pervasively across
the split files that surfaced it.

Blast radius checked directly (`grep -rlE '^;--- Layer [0-9]+:'`
across `components`/`bases`/`projects`): exactly 3 files repo-wide,
all inside #1485's own split — `dag_orchestrator.clj`, `dag_plan.clj`
(both hand-fixed already), and `dag_resilience.clj` (uses the same
described-header convention but has no `^{:stratum n}` metadata on its
defs at all, so SL006 had nothing to compare against — genuinely
clean, not a hidden miss). The rest of the codebase never adopted this
pattern.

## Changes in Detail

- `tasks/stratum.clj`: `stratum-lint-deps`'s pinned sha,
  `bef8657a` → `ccde3a11`.

## Testing Plan

- Confirmed the sha resolves via `bb -Sdeps` (ran the actual
  `stratum-lint.interface` invocation against `tasks/stratum.clj`
  itself, not just a deps-resolve check) — zero findings.
- `bb pre-commit` passes clean (331 tests / 1241 assertions + 8
  GraalVM/Babashka compat tests, 0 failures/errors).
- stratum-lint#16 itself: 5 new tests, `bb check` (46 tests +
  self-dogfood) green upstream before merge; CI green on all 4
  matrix jobs (check, check-clojure, check-rust, check-swift).
- One post-review fix on #16 (a README example wrapping across a
  line break inside its inline-code span) verified and merged before
  bumping this pin.

## Deployment Plan

Merges to `main` immediately. Per the blast-radius check above, no
other file in the repo needs a pre-emptive sweep — the described-
heading pattern is specific to #1485's own authoring style, not a
general Wave-1-wide exposure.

## Related Issues/PRs

- Fix consumed: [stratum-lint#16](https://github.com/miniforge-ai/stratum-lint/pull/16)
- Found via: [#1485](https://github.com/miniforge-ai/miniforge/pull/1485),
  the `workflow` component's `dag_orchestrator.clj` split
- Part of: `work/stratum-lint-baseline-2026-07-24.md`

## Checklist

- [x] Sha resolves via `bb -Sdeps`
- [x] Pre-commit hook passes clean
- [x] Blast radius checked directly (repo-wide grep) — 3 files, all
      already hand-fixed in #1485, no pre-emptive sweep needed
