# fix: stratum-lint autofix for components/adapter-claude-code (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/adapter-claude-code` (src + test)
to replace decorative, non-monotonic `Layer N` section headings with
headings that reflect the file's real same-file reference graph, and adds
`^{:stratum n}` metadata to every top-level def — a mechanical
relabel-and-regroup pass, one of the per-component PRs of Wave 1. Also
fixes two issues automated review caught in this specific redo:
`discovery.clj`'s namespace docstring still described the file's old
3-layer decorative structure after `--fix` revealed 5 real layers, and
`tool_profiles.clj`'s `load-profiles` silently returned nil on a missing
classpath resource instead of failing fast like the sibling
`discovery/load-config` does for the identical failure mode. Both are
narrow, verified fixes — not part of the mechanical heading pass.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md` (Wave 1) identified
`adapter-claude-code` as one of the 75 components/bases where rule 210's
`Layer N` heading convention (`standards/miniforge/languages/clojure.mdc`)
had been cargo-culted into repeated visual-break banners rather than a
true one-way dependency DAG. The component carried 8 findings — 7 SL002
(heading reused non-monotonically) and 1 SL003 (over the 3-layer budget)
— and, notably, **zero SL001** (no upward-reference findings), which is
exactly why the baseline doc named it a safe early target: no cycle or
reordering-correctness risk to reason about before running `--fix`,
unlike the 18 files flagged for Wave 3's SL001 triage.

This branch went through several redo cycles before this final version,
each catching a real, separate issue — recorded for anyone reading the
history:

1. First pass (sha `acd82a2f`) — the baseline autofix; hit
   [stratum-lint#8](https://github.com/miniforge-ai/stratum-lint/pull/8)
   (comment-directive detachment).
2. Second pass, same commit's own validation, found
   [stratum-lint#10](https://github.com/miniforge-ai/stratum-lint/pull/10)
   (defrecord-constructor reference blind spot — a real compile break,
   not cosmetic).
3. Both fixed upstream, picked up via pin bump #1465
   (`59b4b9a3`) — this PR originally landed on top of that.
4. This branch's own `tasks/stratum.clj` had never been rebased onto
   `main` through the two subsequent pin bumps (#1470, picking up the
   same-line-trailing-comment idempotency fix
   [stratum-lint#13](https://github.com/miniforge-ai/stratum-lint/pull/13);
   and #1471, the SL003 blocking-by-default change) — the same
   branch-staleness root cause found and fixed on `gate`'s (#1464) and
   `reliability`'s Wave 1 redo. Reset the branch onto current `main`
   (pin `80699e37`) and re-ran `--fix` fresh for this final version, so
   the committed state matches what the pre-commit hook will re-verify
   on the next commit, instead of fighting a stale pin.

## Changes in Detail

Ran, against the pin now current on `main` (`tasks/stratum.clj`, bumped
through #1465/#1470/#1471):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/adapter-claude-code
```

against all 6 `.clj` files in the component. Every file was rewritten:
headings regrouped from the tool's own reference-graph inference, every
top-level def annotated with `^{:stratum n}`, defs physically reordered to
match their real stratum. No file's function/test bodies changed —
verified by reading full diffs, not just `--stat`.

- `src/.../discovery.clj` — 2 decorative Layer 0 repeats collapsed;
  file's real shape is 5 layers (0–4), so it still trips SL003 after the
  fix (see Testing Plan).
- `src/.../impl.clj` — decorative headings collapsed; real shape is 6
  layers (0–5), still trips SL003. `create-adapter` (calls the
  `->ClaudeCodeAdapter` defrecord constructor) correctly lands at the
  higher Layer 5, while `defrecord ClaudeCodeAdapter` itself stays at
  Layer 4 — but still *earlier in the file*, since Clojure compiles
  top-to-bottom and the constructor symbol must exist before anything
  calls it. Higher stratum number, later file position: the two track
  together here, not opposed. This ordering was specifically
  re-verified after this redo — see Testing Plan.
- `src/.../interface.clj` — single-heading file, no reordering, just
  `^{:stratum 0}` metadata added.
- `src/.../tool_profiles.clj` — decorative headings collapsed to 5 real
  layers (still trips SL003). The `#_{:clj-kondo/ignore
  [:unused-private-var]}` reader-discard directive immediately preceding
  `(defonce ^:private registered? ...)` stays attached to that def after
  the fix (Bug 1 above, verified still resolved at this pin).
- `test/.../interface_test.clj`, `test/.../tool_profiles_test.clj` —
  `deftest` forms regrouped by real stratum (constants/fixtures at Layer
  0, everything exercising them at Layer 1). Reordering `deftest` forms
  doesn't change test behavior; each file's tests still pass individually
  and in the full suite (see Testing Plan).

## Testing Plan

1. **Idempotency**: `--fix` run twice in a row on the whole component
   before committing; zero diff between the two passes (confirmed
   directly via `diff -r`, not assumed from #13 being merged).

2. **Diff review**: read every changed file in full (not just `git diff
   --stat`) to confirm each diff is heading regrouping + `^{:stratum n}`
   metadata + def reordering, with zero changes to test assertions.
   Two narrow exceptions, both from automated review on this redo (see
   Overview): `discovery.clj`'s docstring layer summary, and
   `tool_profiles.clj`'s `load-profiles` fail-fast behavior.

3. **`create-adapter`/`ClaudeCodeAdapter` ordering** (the defrecord-
   constructor bug's exact shape, #10): re-verified `create-adapter`
   still lands after `defrecord ClaudeCodeAdapter` in file order at this
   pin — `clj-kondo` reports zero unresolved-symbol errors.

4. **Post-fix full-component `clj-kondo --lint components/adapter-claude-code`**:
   0 errors, 0 warnings, across both src and test files.

5. **Plain (non-`--fix`) re-lint** of the component after the fix:

   ```bash
   bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface components/adapter-claude-code
   ```

   3 SL003 findings remain — **expected, out of scope for this PR**:

   - `discovery.clj` — 5 real layers (max 3)
   - `impl.clj` — 6 real layers (max 3)
   - `tool_profiles.clj` — 5 real layers (max 3)

   All 3 are genuine over-budget files (not decorative headings — the
   tool's own reference-graph inference produced these layer counts),
   matching the baseline doc's prediction that some Wave 1 files would
   still need an actual namespace split. That's Wave 2 work
   (`work/stratum-lint-baseline-2026-07-24.md`), not a defect in this PR.
   Zero SL001, SL002, or SL004 findings remain. Committed with
   `MINIFORGE_STRATUM_BUDGET_MODE=warn` for these 3 pre-existing files,
   same reason as `gate`'s and `reliability`'s Wave 1 redos.

6. **`load-profiles` fail-fast**: no existing test called `load-profiles`
   directly or asserted on its nil-return behavior, so nothing to update.
   `clj-kondo` clean after the change. The classpath resource is present
   in this repo, so `claude-cli-profiles` still resolves normally at
   namespace load — the change only affects the already-broken-packaging
   case, which previously loaded silently as `nil`.

7. **Full test suite for the touched namespaces**:
   `ai.miniforge.adapter-claude-code.interface-test` and
   `ai.miniforge.adapter-claude-code.tool-profiles-test`, both passing,
   re-run after this redo.

## Deployment Plan

Merges to `main`. Mostly source-reformatting; two small behavior fixes
from review (see Overview) — `load-profiles` now throws instead of
silently registering nothing when its classpath resource is missing,
which only changes behavior for an already-broken deployment. No
migration, no config change. Safe to merge independently of other
Wave 1 component PRs (each is its own PR per the baseline doc's decided
PR granularity, one component per PR).

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Upstream fixes consumed:
  [miniforge-ai/stratum-lint#8](https://github.com/miniforge-ai/stratum-lint/pull/8)
  (comment-directive detachment),
  [#10](https://github.com/miniforge-ai/stratum-lint/pull/10)
  (defrecord-constructor reference blind spot),
  [#13](https://github.com/miniforge-ai/stratum-lint/pull/13)
  (same-line trailing-comment idempotency)
- Precommit autofix wiring: #1459; pin bumps #1465, #1470, #1471 (this
  PR is rebased on top of all three)
- Same branch-staleness root cause as gate's (#1464) and reliability's
  Wave 1 redo
- Follow-on: Wave 2 (namespace splits for `discovery.clj`, `impl.clj`,
  `tool_profiles.clj`'s remaining SL003), other Wave 1 component PRs

## Checklist

- [x] Idempotency verified directly (two `--fix` passes, zero diff)
      before committing, not just after one pass
- [x] Diff read in full for every changed file, not just `--stat`
- [x] Confirmed the only non-mechanical changes are the two narrow,
      verified fixes from review (docstring accuracy, fail-fast on
      missing resource) — everything else is headings/metadata/def order
- [x] `create-adapter`/`ClaudeCodeAdapter` ordering re-verified correct
      at this pin
- [x] Post-fix `clj-kondo --lint` clean across the whole component
- [x] Post-fix plain stratum-lint re-run: 3 expected SL003
      (over-budget) findings remain, documented above as Wave 2 work;
      zero SL001/SL002/SL004
- [x] Full test suite run for both touched test namespaces: all passing
