# fix: stratum-lint autofix for components/control-plane-adapter (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/control-plane-adapter` (`src` +
`test`) to replace decorative, non-monotonic `Layer N` headings with real
ones derived from the file's actual same-file reference graph, and to add
`^{:stratum n}` metadata to every def. Mechanical: no logic changes. One
of the Wave 1 per-component PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

The component carried 4 findings under the baseline's cargo-cult
diagnosis, all `SL002` (a `Layer N` heading repeated instead of increasing):
`protocol.clj` had two separate `Layer 0` headings for its two
`defprotocol`s, and `interface_test.clj` had a `Layer 1` heading reused
three times across unrelated `deftest` groups. Zero `SL001` findings, so
no upward-reference/cycle risk to reason about before running the
mechanical fixer — matches the baseline's Wave 1 batch criteria.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/control-plane-adapter
```

3 files rewritten: `protocol.clj`, `interface.clj` (both `src`), and
`interface_test.clj` (`test`) — `--fix` normalizes every file in the
component, not just the ones with findings.

- `protocol.clj`: both `defprotocol`s (`ControlPlaneAdapter`,
  `ControlPlaneAdapterLogs`) are genuinely independent (no same-file
  reference between them), so they collapse under one real `Layer 0`
  heading instead of two. Each gained `^{:stratum 0}`.
- `interface.clj` had no prior findings; `--fix` only added
  `^{:stratum n}` metadata to its existing defs (all Layer 0, plus
  `heartbeat-interval-for-vendor` at Layer 1 since it composes the
  Layer-0 `vendor-heartbeat-ms` map) — no reordering, no heading changes.
- `interface_test.clj`: three `deftest`s that reference the file-local
  `openai-status-map` fixture (`normalize-status-known-mapping-test`,
  `normalize-status-keyword-input-test`, `normalize-status-unknown-test`)
  moved from their old position (labeled `Layer 1`, same as everything
  else) down to after the vendor/heartbeat tests, now correctly at real
  `Layer 1` (one above the `Layer 0` fixture they reference).
  `normalize-status-empty-mapping-test`, which uses a literal `{}` instead
  of the fixture, stayed at `Layer 0`. All other test groups collapsed
  onto the same two real layers (0 and 1) instead of the old three
  decorative ones (0, 1, 2).

No same-line trailing comment was displaced onto the wrong def — checked
each file for the known tool limitation and found none of that shape.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 4 `SL002` findings exactly (same files, same lines).
2. Ran `--fix`, then a second `--fix` pass immediately after on a saved
   copy of the tree — zero diff, confirms idempotency.
3. Read the full diff for all 3 changed files. Confirmed only heading
   text, `^{:stratum n}` metadata, and def/deftest reordering changed; no
   executable code touched.
4. `clj-kondo --lint components/control-plane-adapter`: 0 errors, 0
   warnings.
5. Ran `ai.miniforge.control-plane-adapter.interface-test` directly via
   `clojure -A:test`: 13 tests, 35 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: zero findings remain across
   the whole component — no `SL003` surfaced (real depth stayed within
   the 3-layer budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 3 changed files; mechanical-only
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (13 tests, 35 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings remain, no `SL003`
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
