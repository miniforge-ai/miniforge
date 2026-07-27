# fix: stratum-lint autofix for components/connector-http (Wave 1)

## Overview

Runs `stratum-lint --fix` over the whole `connector-http` component
(`src` + `test`) to replace the file's `Layer N` headings with headings
regenerated from its real same-file reference graph, and tags each
`def`/`defn`/`deftest` with `^{:stratum n}` metadata. Purely mechanical:
no logic changes. Part of the per-component Wave 1 sweep from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`components/connector-http` carried exactly one baseline finding: `SL003`
on `rate_limit.clj` (file reporting 4 distinct layers against the 3-layer
budget), with zero `SL001` (upward-reference) findings — the reason
this component was Wave 1 scope rather than needing the SL001 human
triage from Wave 3.

## Changes in Detail

Reset onto current `origin/main` first (the pinned-sha staleness bug that
hit three earlier Wave 1 PRs), confirmed the `tasks/stratum.clj` pin
(`80699e378cb8ebbb6daeb928431aa4a6b373c07e`), then ran:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-http
```

15 files rewritten (9 `src`, 6 `test`) — every `.clj` file in the
component, including files with no prior findings (`--fix` normalizes
those too). Diff stat: 15 files changed, 312 insertions(+), 316
deletions(-).

`rate_limit.clj`'s own real reference graph turned out shallower than its
old headings claimed: `time-based-acquire!` and `update-rate-state!`
don't call anything else in the file (real Layer 0, alongside
`parse-long-header`, `ms-until-reset`, `executor`, `default-threshold`),
and `parse-rate-headers`/`acquire-permit!` each call one Layer-0 def (real
Layer 1). Two real layers, not four — the old headings were
over-reporting depth, not under-reporting it as seen in other Wave 1
components. No SL003 remains after the fix.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` on `components/connector-http`
   before the fix — reproduced the single baseline `SL003` finding on
   `rate_limit.clj`.
2. Ran `--fix`, then ran it a second time to verify idempotency — zero
   diff between the two passes (`diff -rq` on the full component tree).
3. Read the full diff for every one of the 15 changed files. Confirmed
   heading regrouping, `^{:stratum n}` metadata, and def reordering only.
   Checked specifically for the known tool limitation where a same-line
   trailing comment can get reattached to the wrong def after reordering
   — no same-line trailing comments existed in this component before or
   after the fix (only leading `;;`/`;` block comments, which moved
   correctly with their following def), so no hand-fix was needed.
4. Ran `clj-kondo --lint components/connector-http`: 0 errors, 1 warning
   (unused binding `r` in `request_test.clj`), confirmed pre-existing on
   `origin/main` at the same content (line number only shifted from 129
   to 94 due to reordering) — not introduced by this change.
5. Re-ran plain `stratum-lint` on `components/connector-http` after the
   fix: exit 0, zero findings remain. No `SL003` (or any other check)
   left over — nothing deferred to Wave 2 for this component.
6. Ran the component's full test suite via
   `clojure -M:poly test brick:connector-http`: 6 namespaces, 46 tests,
   146 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
changes — comment/metadata/reorder only. The pre-commit hook's
`lint:stratum` autofixer keeps this component clean going forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1 —
  mechanical relabeling via `--fix`, decorative-heading files only)
- Depends on: `2026-07-24-fix-stratum-lint-autofix-precommit.md` (upstream
  sha bump + autofix wiring)
- Precedent PRs from the same wave: `2026-07-24-fix-stratum-lint-wave1-compliance-scanner.md`,
  `-reliability.md`, `-gate.md`, `-decision.md`, `-adapter-claude-code.md`

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Idempotency verified: second `--fix` pass produced zero diff
- [x] Diff read in full for every changed file; confirmed mechanical-only
      (heading + metadata + reorder); no trailing-comment misattachment
      found or needed hand-fixing
- [x] `clj-kondo` clean (0 errors; 1 pre-existing warning, unaffected by
      this change)
- [x] Plain lint re-run post-fix: zero findings (no `SL003` remains — no
      Wave 2 follow-on needed for this component)
- [x] Full component test suite passing (46 tests, 146 assertions, 0
      failures/errors)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
