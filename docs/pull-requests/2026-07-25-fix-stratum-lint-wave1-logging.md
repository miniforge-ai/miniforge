# fix: stratum-lint autofix for components/logging (Wave 1)

## Overview

Runs `stratum-lint --fix` (sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e`)
over all 8 Clojure files (6 `src`, 2 `test`) in `components/logging`,
regrouping each file's defs under regenerated `;---- Layer N` headings and
tagging every def with `^{:stratum n}` metadata inferred from the real
same-file reference graph. No logic changes — headings, metadata, and def
order only.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md` (Wave 0) found rule 210's
stratified-design headings had been cargo-culted across most of the tree
into decorative section banners that don't track a real dependency DAG.
`components/logging` carried exactly 1 reported finding — `SL003` on
`interface.clj` (4 distinct layers against the 3-layer budget) — and
**zero `SL001`** upward-reference findings, which is what puts it in the
Wave 1 safe-to-autofix batch: no cycle/upward-call risk to reason about
before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/logging
```

All 8 files rewritten: `core.clj`, `format.clj`, `http.clj`, `interface.clj`,
`messages.clj`, `sinks.clj`, `interface_test.clj`, `sinks_test.clj`. Diff
stat: 8 files changed, 291 insertions(+), 291 deletions(-) — perfectly
balanced, consistent with pure reordering plus metadata addition and no
content loss.

`interface.clj` — the file the baseline flagged — is now resolved to 2 real
layers (was mislabeled as 4). `--fix` also processed `sinks.clj`, which the
original baseline scan reported zero findings for; it turns out its
decorative headings (`;;------ Layer N: <label>`, e.g. `Layer 2: Fleet
Sink`) carry trailing descriptive text after the layer number, which
`stratum-lint`'s heading regex (`^;+\s*-*\s*Layer\s+(\d+)\s*-*\s*$`)
doesn't match — it requires nothing but optional dashes/whitespace after
`Layer N`, not a colon and a label. So these were never recognized as
headings at all (semicolon count is irrelevant to the regex; the trailing
text is what breaks the match), and the file was silently skipped by the
original scan rather than passing — exactly the "documented limitation"
the baseline doc warned about (files with no recognized heading
under-report the true debt). Once `--fix` computed `sinks.clj`'s real
reference graph, it came out to **5 distinct layers**, over the 3-layer
budget. This is pre-existing structural debt this PR did not create — it's
the same code, now correctly labeled — and is out of scope here; tracked as
Wave 2 (needs an actual namespace split).

Checked every changed file for the known tool limitation where a same-line
trailing comment (`closing-form])  ; comment`) can get detached and
reattached next to the wrong def during reordering: one such comment
exists in `sinks.clj` (`[:file]) ;; Default to file sink`, inside a `let`
binding in `create-sinks-from-config`), and it stayed correctly attached
to the same binding after the fix — no hand-correction needed.

**Follow-up (post-review):** because the old double-semicolon banners in
`sinks.clj` were never recognized as real headings, `--fix` left them in
place as ordinary comments — dragged along with whichever def ended up
nearest them after the reorder. The result: 5 stale banners
(`Layer 0: File Sink`, `Layer 1: Stream Sinks`, `Layer 2: Fleet Sink`,
`Layer 3: Multi-Sink`, `Layer 4: Sink Factory`) ended up sitting at layer
numbers that contradict the real, adjacent single-semicolon heading and
the def's own `^{:stratum n}` metadata — e.g. `Layer 2: Fleet Sink` sitting
directly above `fleet-sink`, which is tagged `^{:stratum 0}`. Flagged by
Copilot review on this PR. Not a `stratum-lint` bug (the regex is working
exactly as documented) — a per-file cleanup call. Removed all 5: each
one's descriptive fragment (`File Sink`, `Stream Sinks`, `Fleet Sink`,
`Multi-Sink`, `Sink Factory`) is redundant with either the function name
or its own docstring's opening line, so deleting loses no information
that isn't already stated nearby; keeping a labeled-but-wrong-numbered
banner next to a correct one would only re-create the exact confusion
being fixed. Re-ran `--fix` twice after the deletion: no output either
time (nothing left to rewrite), confirming the removal didn't touch the
real heading structure — same single `SL003` finding on `sinks.clj`, same
5-layer count, only the line number shifted. Checked every other changed
file in the component for the same pattern (`grep` for `;;.*Layer\s+\d+.*:`)
— none found; `sinks.clj` was the only offender.

## Testing Plan

1. Ran plain (non-`--fix`) lint before fixing: 1 finding (`SL003` on
   `interface.clj`), 0 `SL001` — matches the baseline.
2. Ran `--fix`, then ran the identical `--fix` command a second time:
   zero-diff, confirming idempotency.
3. Read the full diff for all 8 changed files. Confirmed the only changes
   are heading placement, def reordering, and `^{:stratum n}` metadata;
   insertions and deletions are equal (291/291) across the whole diff.
4. `clj-kondo --lint components/logging`: 0 errors, 0 warnings.
5. Re-ran plain `stratum-lint` after the fix: `SL003` remains, now on
   `sinks.clj` (5 real layers) instead of `interface.clj` — a
   previously-invisible over-budget file surfaced by `--fix`'s
   heading-format-independent reference-graph analysis, not a regression.
   Wave 2 scope.
6. Ran the component's test suite (`interface-test` + `sinks-test`, via
   babashka with the component's resolved classpath, since
   `ai.miniforge.config.user` — a transitive dep — requires
   `babashka.process`, which isn't declared as a Maven dep and is only
   available under `bb`): 11 tests, 54 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
changes — comment/metadata/order-only. The pre-commit hook's `lint:stratum`
autofixer keeps this component clean going forward for any file it
touches; the remaining `SL003` on `sinks.clj` stays flagged (build config
is `MINIFORGE_STRATUM_BUDGET_MODE=warn` on this commit, since the finding
is pre-existing and not created or worsened by this PR) until Wave 2 splits
the namespace.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1 —
  mechanical relabeling via `--fix`, decorative-heading files only)
- Precedent: #1461 (`compliance-scanner`), #1462 (`reliability`), #1463
  (`decision`), #1464 (`gate`), #1467 (`adapter-claude-code`) — same Wave 1
  pattern
- Follow-on: Wave 2 namespace split for `sinks.clj` (now `SL003`, 5 real
  layers)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Idempotency verified (second `--fix` run, zero diff)
- [x] Diff reviewed file-by-file; confirmed mechanical-only (heading +
      metadata + reorder)
- [x] Checked for same-line trailing-comment reattachment; one found,
      confirmed correctly attached, no hand-fix needed
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Plain lint re-run post-fix: `SL003` remains on `sinks.clj`
      (pre-existing, documented, Wave 2 scope)
- [x] Component test suite passes (11 tests, 54 assertions, 0
      failures/errors)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
- [x] Post-review: stale double-semicolon banners with wrong `Layer N:`
      labels removed from `sinks.clj` (5); confirmed absent elsewhere in
      the component; re-verified idempotency and unchanged finding after
      removal
