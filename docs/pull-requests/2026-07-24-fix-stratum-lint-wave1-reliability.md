# fix: stratum-lint autofix for components/reliability (Wave 1)

## Overview

Runs `stratum-lint --fix` (sha `acd82a2f`) over all 13 Clojure files (9
`src`, 4 `test`) in `components/reliability`, regrouping each file's defs
under regenerated `;---- Layer N` headings and tagging every def with
`^{:stratum n}` metadata inferred from the real same-file reference graph.
No logic changes — headings and metadata only.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md` (Wave 0) found rule 210's
stratified-design headings had been cargo-culted across most of the tree
into decorative section banners that didn't track a real dependency DAG.
`reliability` carried 12 findings (11 SL002 heading-order, 1 SL003
over-budget, **zero SL001** upward-references) — the zero-SL001 profile is
what puts it in the first safe-to-autofix batch (autofix cannot be trusted
to resolve a genuine upward reference; it can always resolve mis-ordered or
decorative headings). This PR is that Wave 1 pass for `reliability`.

## Changes in Detail

Ran:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "acd82a2f5c0155cb03d92ce1f4465cc064125895" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/reliability
```

All 13 files rewritten:

- `src/.../budget.clj`, `degradation.clj`, `dependency_health.clj`,
  `engine.clj`, `interface.clj`, `messages.clj`, `schema.clj`, `sli.clj`,
  `slo.clj`
- `test/.../degradation_test.clj`, `dependency_health_test.clj`,
  `engine_test.clj`, `sli_test.clj`

Every named top-level form (`def`/`defn`/`defn-`/`defrecord`/`deftest`/`ns`)
was verified byte-identical before and after, ignoring the added
`^{:stratum n}` metadata — confirmed with a form-level comparison script
reading both versions with the Clojure reader (see Testing Plan). No
function body, test assertion, or `ns` form changed; only heading
placement, def order, and metadata did.

**`interface.clj` (the file the baseline flagged as the one SL003
over-budget finding) is now fully resolved to a single Layer 0.** It's a
pure pass-through file — every def just aliases a var in another
namespace — so the real same-file reference graph has no internal edges,
and the previously decorative 6-heading structure collapses to what it
actually is: flat. Zero re-splitting needed here.

**Two cosmetic comment misplacements**, both the same tool quirk: a
trailing inline comment on a `defrecord`'s last field (e.g.
`config])       ; data-driven degradation policy`) gets detached during
reordering and reattached as a leading comment on an unrelated def that
happened to land at that position afterward (`degradation.clj`'s
`DegradationManager` comment ends up above `mode-rank`; `engine.clj`'s
`ReliabilityEngine` comment ends up above `create-engine`). Left as the
autofixer produced them rather than hand-patched, so this PR's diff is
exactly and only what `--fix` emits — verifiable and reproducible. Harmless
(comments, not code; the affected line is still valid, still describes the
same field's shape in prose nearby), but worth an upstream issue against
`stratum-lint`'s comment-reattachment heuristic for `defrecord` trailing
field comments — flagged separately, out of scope for this PR.

Also noticed: a handful of `ns` docstrings state a smaller layer count in
prose than the file now really has (e.g. `budget.clj`'s docstring says
"Layer 0: Constants / Layer 1: Pure computation functions" but the file is
now 4 real layers). `--fix` only rewrites structural headings/metadata, not
free-text docstrings, so these are stale by construction until someone
updates the prose by hand. Cosmetic, not blocking.

## Testing Plan

1. Ran plain (non-`--fix`) lint before fixing: 12 findings, exactly matching
   the baseline (11 SL002, 1 SL003 in `interface.clj`, 0 SL001).
2. Ran `--fix`; reviewed `git diff --stat` and read every one of the 13
   changed files in full.
3. Wrote and ran a babashka script
   (`/tmp` scratch, not committed) that reads the pre-fix (`git show HEAD:`)
   and post-fix version of each file with the Clojure reader, strips the
   `:stratum` key from metadata, and diffs every named top-level form's body
   between versions by name. Result: zero missing forms, zero added forms,
   **zero body differences** across all 13 files (188 total named forms
   compared, including `ns` and `deftest` forms).
4. Ran plain lint again after `--fix`. **Not clean** — 6 of 9 `src` files
   still report SL003:

   ```text
   budget.clj:            4 distinct layers (max 3)
   degradation.clj:       7 distinct layers (max 3)
   dependency_health.clj: 6 distinct layers (max 3)
   engine.clj:            4 distinct layers (max 3)
   sli.clj:               4 distinct layers (max 3)
   slo.clj:               4 distinct layers (max 3)
   ```

   This is larger than the baseline's "1 over-budget file" prediction — but
   that number came from the *pre-fix* decorative headings, which cycled
   through 0/1/2 repeatedly without exceeding 3 distinct labels even when
   badly misordered (that's what SL002 was catching). Once `--fix` computes
   the *real* per-file stratification from the actual call graph, several
   files turn out to have genuine DAG depth greater than 3 — the decorative
   headings were masking this, not just misordering it. This is exactly the
   SL003-remainder case flagged as expected/out-of-scope in the task
   instructions and in the baseline's Wave 2 section (real namespace
   splits) — noted here, not fixed in this PR.
5. Ran the component's test suite directly
   (`cd components/reliability && clojure -M:test -m cognitect.test-runner`):
   30 tests, 80 assertions, 0 failures, 0 errors.
6. Ran `bb poly:check`: passes (one pre-existing warning in an unrelated
   component, `config`, not touched by this PR).
7. Ran `clj-kondo --lint components/reliability/src components/reliability/test`
   directly: 0 errors, 0 warnings.

## Deployment Plan

Merges to `main`. No runtime behavior change — pure source reformatting.
No migration, no config change, no rollout sequencing needed.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 0) —
  `reliability` is one of the smaller-reference-graph components
  recommended for an early Wave 1 batch (`compliance-scanner`,
  `reliability`, `gate`, `decision`, `adapter-claude-code`).
- Depends on: #1459 (`fix: stratum-lint pre-commit autofixes instead of just
  failing`), which bumped the pinned sha to `acd82a2f` and wired `--fix`
  into pre-commit.
- Follow-on: Wave 2 namespace splits for the 6 files above (`budget.clj`,
  `degradation.clj`, `dependency_health.clj`, `engine.clj`, `sli.clj`,
  `slo.clj`), each genuinely over the 3-layer budget once decorative
  headings are replaced with real ones.
- Worth filing upstream against `miniforge-ai/stratum-lint`: the
  `defrecord`-trailing-field-comment reattachment quirk noted above.

## Checklist

- [x] Ran `--fix` over the whole component (src + test)
- [x] Reviewed full diff for all 13 changed files; confirmed mechanical
      (headings + `^{:stratum n}` metadata only)
- [x] Form-level equivalence verified programmatically (188 named forms,
      zero body differences)
- [x] Re-ran plain lint post-fix; 6 SL003 remainders documented above as
      expected Wave 2 work, not a regression or failure
- [x] Component test suite green (30 tests, 80 assertions)
- [x] `poly:check` and `clj-kondo` clean
- [x] Two cosmetic comment-placement quirks identified, explained, and left
      unmodified (exact `--fix` output preserved) rather than silently
      hand-patched
