<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: express multi-line literals in MDC remediation templates

## Overview

Expand `\n`, `\t` and `\\` escapes in `remediation.template` when compiling an
`.mdc` rule, scoped to that one field so regex-source fields keep their
backslashes.

## Motivation

MDC frontmatter has no block-scalar (`|`) syntax, and
`frontmatter-values/strip-quotes` does no escape processing. A multi-line
prepend header can therefore only be declared as a single-line quoted scalar
carrying `\n`. That value reached `execute-patch/apply-prepend` as a backslash
followed by an `n`. `apply-prepend` is `(str template "\n" content)`, so the
patcher wrote the whole header on one line.

For 810 `project/header-copyright` the corruption then hid itself. That one
garbled line contains both `Christopher Lester` and `christopher@miniforge.ai`.
`scan-content/header-present?` checks the first ten lines for both patterns,
so it reported the corrupted file compliant. A silent corruption that
satisfies its own gate.

This is reproducible today from the committed pack resource,
`components/phase/resources/packs/miniforge-standards.pack.edn`. Its
`:std/header-copyright` rule still carries `:strategy :mechanical`,
`:type :prepend`, `:auto-fixable-default true` and a one-line template of
literal `\n` characters.

The rule was downgraded to `strategy: manual` in miniforge-standards#129 to
stop the bleeding. This change removes the format limit that forced that
downgrade.

## Layer

Foundations — `mdc-compiler` value parsing and rule-config assembly. No
dependencies; branched from main `534c5526`.

## Changes in Detail

1. Add `frontmatter-values/unescape-literal-text`: a single left-to-right pass
   expanding `\n`, `\t` and `\\`, returning every other sequence verbatim
   (backslash included) and passing non-strings through.
2. Route `remediation.template` through it in
   `rule-config/build-remediation-config`, which is the one place that knows
   which key it is reading.

The scoping is the substance of the change, not an implementation detail. A
blanket unescape of all quoted scalars would corrupt nothing today. An audit
covered every backslash-bearing quoted frontmatter value in all 59 `.mdc` files
on miniforge-standards `main`. It found no `\\` anywhere and no `\n` or `\t` in
any `detection.pattern`. But a blanket unescape would diverge silently the
moment a pattern needed one. So the expansion is scoped per field:

1. Expanded: `remediation.template`, which is literal text.
2. Not expanded: `detection.pattern` and `detection.emailPattern`, which are
   regex source.
3. Not expanded: `remediation.replacement`, a regex replacement string with
   `$1`-style group references (`"(get $2 $1 $3)"`, `"$1.$2.$3.0"`).
4. Not expanded: the `exclude*Contains` lists, matched with `str/includes?`.

## Testing Plan

New namespace `mdc-compiler.frontmatter-values-test`: 8 tests, 57 assertions,
passing. Reverting only the `rule-config` call site fails 8 assertions across
all four integration tests, so none of them is vacuous.

Coverage:

1. A template `\n` becomes a real newline; `\t` becomes a tab.
2. `\\n` yields a literal backslash followed by `n` — escaping the escape works,
   and the result stays on one line.
3. The five real regex values from miniforge-standards `main` survive
   byte-for-byte, as do `\s \d \b \w \( \) \[ \] \{ \} \. \$`.
4. `$` is not interpreted as a group reference, including beside an expanded
   escape. `str/replace`'s string-replacement arm would have read `$1` as a
   backreference; the function arm quotes it.
5. An odd run of backslashes leaves one escaped backslash and expands the rest.
6. `build-remediation-config` expands `template` and leaves `replacement` and
   the exclude lists alone.
7. Round trip through `mdc->rule` and through `compile-standards-pack` from a
   scratch directory, asserting line counts and the absence of a literal `\n`.

Gates: `bb lint:clj` 0 errors 0 warnings; `bb lint:stratum` clean and
idempotent; `bb poly:check` OK. `bb review` reports 10 violations across 4336
files. All are pre-existing exceptions-as-data rows in other components, none
in the changed files. Policy-pack compiler suites run together: 80 tests, 747
assertions, 0 failures.

Sliced into two commits to stay inside the 200-line commit budget; each commit
carries its own tests.

## Deployment Plan

Two paths read rules, and they change at different times.

1. A scan from source changes at once. `scan/scan-repo` calls
   `compile-standards-pack` whenever no precompiled `:pack` is supplied, so it
   gets decoded templates without any rebuild.
2. A consumer of the committed pack resource does not change until the pack is
   recompiled with `bb standards:pack` from a populated `standards/miniforge`.
   The committed pack keeps its broken one-line template until then, so the
   corruption path above stays open on the pack as shipped.

A guard would close that path whatever the pack holds. It would refuse a
prepend whose template still holds a literal `\n`, and say so in the plan. It
is not in this PR because it rewrites `classify/classify-one`, which
miniforge-ai/miniforge#2020 also changes. It follows once that merges.

Re-pinning the standards submodule is out of scope. The pin is 57 commits
behind and the bump is independently blocked.

## Related Issues/PRs

1. miniforge-ai/miniforge-standards#129 — downgraded 810 to `strategy: manual`
   and documents this format limit at the rule. Its rationale needs updating
   once this lands: the format can express the template now, and `manual`
   remains only for the Apache-license-scope question.
2. miniforge-ai/miniforge#2020 — rewrites `classify/classify-one` and
   `scan-pack-config/enrich-violation`. Blocks the follow-up guard, not this PR.

## Not in this PR

Flipping 810 `project/header-copyright` back to `strategy: mechanical` +
`type: prepend` + `autoFixable: true` needs two independent gates cleared.
This change clears only one of them. The other is the rule's Apache-2.0
scope condition: apply only when the repository root holds a `LICENSE`
containing `Apache`. No `detection` or `remediation` field expresses that
prose. It exists to stop an Apache header being prepended to a
proprietary file. That is a human judgement call. The same applies to
`miniforge-ai/thesium-standards` `project/header-proprietary`.

## Checklist

- [x] Scope escape expansion to literal-text fields; leave regex source intact.
- [x] Tests that fail without the fix, including the real regex corpus.
- [x] Lint, stratum, poly, self-review and compiler suites pass.
- [ ] Follow-up: the loud guard in `classify`, after #2020 merges.
- [ ] Follow-up: update miniforge-standards#129's rationale.
