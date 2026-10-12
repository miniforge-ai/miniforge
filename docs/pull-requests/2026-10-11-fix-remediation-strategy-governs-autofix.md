<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(compliance-scanner): let the declared remediation strategy govern auto-fix

## Problem

A pack rule's `remediation.strategy` had no effect on whether the compliance
scanner would mechanically patch a file. Two independent defects:

### 1. The strategy never reached the classifier

`classify/classify-one` refuses to auto-fix a `:semantic` strategy, and its
docstring said so. But `scan-pack-config/enrich-violation` carried
`:remediation-type`, `:remediation-template`, `:auto-fixable-default` and
`:exclude-contexts` from the pack rule onto the violation — and **not**
`:strategy`. Nothing anywhere else wrote `:remediation-strategy` or
`[:remediation :strategy]`, so the only reader in the codebase was reading a key
no writer produced.

Demonstrated before the fix: a pack rule declaring `{:strategy :semantic
:auto-fixable-default true}` enriched to a violation whose strategy was visible
to `classify` as `nil`, and classified `:auto-fixable? true` with rationale
`"Literal default, non-JSON field"`.

So the refusal was unreachable, and `strategy:` in MDC frontmatter was inert.

### 2. A rule id was treated as a remediation type

`execute-patch/prepend-violation?` returned true for
`(= :std/header-copyright (get violation :rule/id))` independently of
`remediation.type`. That rule was therefore patched as a prepend whatever its
pack declaration asked for — including after the declaration stopped asking.

## Why this matters now

`project/header-copyright` (dewey 810) declares a `remediation.template` whose
newlines are written as `\n` escapes. MDC frontmatter performs no escape
processing (`mdc-compiler/frontmatter-values/strip-quotes` strips surrounding
quotes and nothing else) and has no block-scalar syntax, so those sequences stay
as a literal backslash and `n`.

Since `apply-prepend` is `(str template "\n" content)`, a mechanical fix writes
the whole header as **one line of literal `\n` characters**. Detection for that
rule is `mode: negative` over the first ten lines, requiring both
`Christopher Lester` and the email address — and the broken one-liner contains
both. So the scanner corrupts the file and then reports it compliant. It is not
a loop that stacks headers; it is a single silent corruption that satisfies its
own gate.

`miniforge-ai/miniforge-standards#129` moves that rule to `strategy: manual` and
drops `type: prepend`. Neither half of that declaration would have had any
effect here: `strategy` was dropped in enrichment, and the rule id hardcode kept
the prepend alive regardless of `type`. This PR makes the declaration govern.

## Changes in Detail

### `scan_pack_config.clj`

- `enrich-violation` now carries `:remediation-strategy` from the pack rule's
  `:strategy`, alongside the fields it already carried.
- Docstring states why the field must be carried.

### `classify.clj`

- `semantic-strategy?` (a `:semantic`-only predicate) is replaced by
  `non-mechanical-strategies` — the named set `#{:semantic :manual}` — plus
  `remediation-strategy`, which reads the declared strategy.
- `:manual` now blocks auto-fix the same way `:semantic` does. A declared
  non-mechanical strategy overrides `:auto-fixable-default` rather than being
  weighed against it.
- The rationale `cond` uses the set membership as its fallback rather than
  matching each strategy exhaustively, so a strategy added to the set without
  its own branch still reports "not machine-applicable" instead of falling
  through to the auto-fixable rationale.
- `:mechanical` is unaffected and stays auto-fixable.

### `execute_patch.clj`

- `prepend-violation?` drops the `:std/header-copyright` special case. The
  declared `remediation.type` is the only trigger.

### `messages/en-US.edn`

- Adds `:classify/manual-strategy`. The rationale goes through `messages/t`
  rather than hardcoded English.

## Testing Plan

New tests, all confirmed to fail against the pre-fix code and pass after —
**9 failures before, 0 after**, so none of them are vacuous:

| Test | Guards |
|---|---|
| `classify-manual-strategy-is-never-auto-fixable` | `:manual` blocks auto-fix despite `autoFixable: true` |
| `classify-semantic-strategy-is-never-auto-fixable` | `:semantic` still blocks (reader regression guard) |
| `classify-mechanical-strategy-stays-auto-fixable` | the set does not over-block |
| `classify-nested-remediation-strategy-is-honoured` | the `[:remediation :strategy]` form still works |
| `enriched-manual-strategy-reaches-classify` | enrich → classify carries `:manual` end to end |
| `enriched-semantic-strategy-reaches-classify` | the original dead-guard defect specifically |
| `prepend-requires-declared-remediation-type-test` | no prepend off the rule id alone |
| `prepend-honours-declared-type-for-any-rule-test` | declared `:prepend` governs for any rule |

Verification runs:

- Targeted: 42 tests / 129 assertions, 0 failures.
- `compliance-scanner` + `policy-pack` + `workflow-compliance-scanner`:
  428 tests / 3108 assertions, 0 failures.
- `evidence-bundle` + `pr-lifecycle` + `gate` + `gate-classification`:
  882 tests / 5155 assertions, 0 failures.
- `bb lint:clj`: 0 errors, 0 warnings (also cleared a pre-existing unresolved
  `clojure.string` warning in `classify_test.clj`).
- `bb lint:stratum`: clean, no net rewrite.
- `bb poly:check`: OK.
- `bb commit-budget`: 144 / 200.

The `Violation` malli schema is an open `[:map ...]`, so the added
`:remediation-strategy` key needs no schema change — consistent with
`:remediation-type` and `:remediation-template`, which are already carried
without being declared there.

## Deployment Plan

Independent of the standards change; neither blocks the other's correctness.
This PR hardens the mechanism, the standards PR corrects the declaration.

**Neither removes the live hazard on its own.** This repo's
`standards/miniforge` pin is 57 commits behind standards `main` and still loads
the rule with `autoFixable: true`, so the autofix is live here until the pin
moves. See the deliberately-unchecked item below.

## Related Issues/PRs

- `miniforge-ai/miniforge-standards#129` — moves 810 to `strategy: manual`,
  drops `type: prepend`, documents the format limit.
- `miniforge-ai/thesium-standards#1` — same defect in
  `project/header-proprietary`. Still open.

## Not in this PR

Deliberately left out, with reasons:

1. **The submodule re-pin.** Pinning `standards/miniforge` from `72f451d` to
   standards `main` is what actually disarms the live autofix. Held back because
   the 57-commit bump also changes a second rule: `:std/bb-over-shell` goes from
   `severity :low` / `enforcement :audit` / `detection {:type :custom}` to
   `severity :high` / `enforcement :hard-halt` / a real `:content-scan` pattern.
   It was previously inert because its frontmatter used nested-block style
   (`detection:` / `mode:`), which the compiler does not parse — it silently
   fell back to `:custom` and `:audit` with no warning. On `main` it is dotted
   style and live.

   Activating it as a blocking gate matches three files in this repo:
   `examples/demo-polyglot-repo/run-demo.sh`,
   `examples/demo-polyglot-repo/scripts/setup.sh`, and
   `examples/demo/run-demo.sh` — all three inside `examples/`, which that rule's
   own body **explicitly exempts** ("Scripts there exist to be analysed and MUST
   NOT be migrated"). The carve-out lives in prose, not in the frontmatter, and
   `excludePathContains` sits under `remediation` (it gates auto-fix, not
   detection), so the exemption is currently inexpressible. The re-pin should
   follow a standards change that makes it machine-readable.

2. **Unescaping `\n` in frontmatter templates.** The principled fix for the
   underlying format limit, but it belongs in `policy-pack`, not here. An audit
   of every backslash-bearing quoted frontmatter value on standards `main` found
   `\b`, `\.`, `\s`, `\d` and bracket escapes in `detection.pattern` values, no
   `\\` anywhere, and `\n` **only** in the 810 template — so a `template`-scoped
   unescape would corrupt nothing today, while a blanket unescape of all quoted
   scalars would start diverging the moment a pattern needs `\n`, `\t` or `\\`.
   `detection.pattern` is regex source and must not be string-unescaped;
   `remediation.template` is literal text and should be. They want separate
   handling, and that pairs naturally with a guard refusing to apply any
   prepend/append template still containing a literal `\n`.

3. **Dead `:classify/*` message keys.** `:classify/header-absent`,
   `:classify/header-incorrect`, `:classify/datever` and
   `:classify/unknown-rule` have no readers. `:classify/header-absent`
   ("Copyright header absent; can be prepended") is now actively false. Removing
   them is dead-code cleanup, a different stratum.

## Checklist

- [x] Both defects reproduced before fixing
- [x] Every new test confirmed to fail pre-fix (9 failures) and pass post-fix
- [x] Rationale text added to the message catalog, not hardcoded
- [x] Downstream consumers of classify output re-tested
- [x] `lint:clj`, `lint:stratum`, `poly:check`, `commit-budget` all pass
- [ ] `standards/miniforge` re-pinned — blocked on the `bb-over-shell`
      `examples/` carve-out becoming machine-readable
