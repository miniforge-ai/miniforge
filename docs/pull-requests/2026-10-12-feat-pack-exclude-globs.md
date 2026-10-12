<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(pack): honour a rule's declared path-scope exclusion

## Overview

A pack rule's path scope has only ever been expressible as an inclusion list
(`:file-globs`). This adds the inverse, `:rule/applies-to :exclude-globs`,
compiled from `excludeGlobs` in MDC frontmatter, and makes the gate and the
compliance scanner honour it.

## Motivation

`workflows/bb-over-shell` (dewey 740) is a `hard-halt` `:content-scan` rule on
standards `main`. Over this repo it fires on exactly three files:

```text
examples/demo-polyglot-repo/run-demo.sh
examples/demo-polyglot-repo/scripts/setup.sh
examples/demo/run-demo.sh
```

All three are under `examples/`, which the rule's own body exempts — "Scripts
there exist to be analysed and MUST NOT be migrated". The exemption is prose.
The compiler reads frontmatter, so the gate blocks on the files the rule says
to leave alone. That is the one thing standing between this repo and re-pinning
`standards/miniforge` to `main`.

### Why not the existing `remediation.excludePathContains`

It does something else. `build-remediation-config` turns it into
`:exclude-contexts`, which `classify` reads to decide `:auto-fixable?` and
nothing more. It never suppresses detection and never suppresses a gate — a
`hard-halt` rule carrying it still blocks, it just blocks without offering a
fix. `bb-over-shell` has no remediation block at all.

### Why an applicability field rather than a detection field

The carve-out is a statement about whether the rule applies to those paths, not
about how it detects. If it lived under `:rule/detection`, the rule would still
be "applicable" to a fixture script — injected as guidance while an agent edits
it, telling the agent to migrate a script the rule forbids migrating. Scope
exclusion belongs beside `:file-globs`, which is also where the standards-side
field sits (the Activation table in `meta/rule-format`, not the automated-fix
table).

## Changes in Detail

### `policy-pack`

- `schema_types.clj` — `:exclude-globs` on `RuleApplicability`, documented as
  the one field with inverse polarity: it applies whether or not `:file-globs`
  is present.
- `mdc_compiler.clj` — `excludeGlobs` normalized through the same
  `normalize-globs` as `globs` (so list, inline-array and bare-string forms all
  work) and kept even when `globs` is absent. An `alwaysApply` rule with no
  globs is still path-tested by scanners and gates, so the carve-out has to
  reach them.
- `registry/support.clj` — `path-in-scope?`, the one place the two-sided rule
  lives: scope is `:file-globs` (absent means everything) minus
  `:exclude-globs`, exclusion wins. `rule-applies?` moves to Layer 2 and calls
  it, replacing its inline glob check.
- `applicability.clj` — `rule-applies-to-artifact?` delegates to
  `path-in-scope?` instead of re-deriving the test. This is the predicate the
  workflow gate reaches through `filter-applicable-rules`, and it and
  `rule-applies?` were two independent implementations of the same question. An
  exclusion honoured by one and ignored by the other is a worse failure than one
  honoured by neither, so they now share the predicate rather than agreeing by
  coincidence.

### `compliance-scanner`

- `scan_pack_config.clj` — `globs->file-pred` takes excludes and returns a
  predicate that is "matches an include and no exclude". Both pattern sets are
  compiled to `PathMatcher`s once, outside the returned closure, which runs once
  per file in the repo index.

  The no-globs include fallback changes from `["**/*"]` to `["**"]`, named
  `every-path-globs`. java.nio's `glob:**/*` requires a separator, so it does
  not match a root-level path: a rule carrying only `:exclude-globs` is
  documented as covering every non-excluded path, and with `**/*` it would
  silently skip every file at the repo root. No rule reaches this fallback today
  (all five `:content-scan` rules declare globs), so there is no behaviour
  change — but the field this PR adds is exactly what makes a rule with no
  `globs` worth writing, so the fallback has to be right before that happens.

  The exclusion is applied at **file selection**, not after detection, because
  a `hard-halt` rule that detects a violation it is not allowed to act on still
  blocks the gate.

  Matcher compilation is inlined rather than extracted to a helper: a helper
  adds a call-graph layer, and `stratum-lint` then reports this namespace at 3
  of 3 layers with no headroom. The docstring says so, so the next reader does
  not undo it.

## Known gap: the semantic analyzer

`semantic-analyzer/core.clj` has three file-selection sites that read
`:file-globs` directly — `select-files-for-rule` (reached by `bb miniforge
scan`), `analyze-rule-on-files`, and `file-rule-violations` (reached by the
gate's batched judge). They do **not** honour `:exclude-globs` yet.

The batched gate path is the sharp one, and it is not simply "the exclusion is
ignored". `batched-judge-rules` admits a rule when **any** artifact in the run
matches it; `batched-semantic-analyzer` then hands every acting rule and every
changed file to `analyze-rules-on-files`, which assigns rules to files
per-file in `file-rule-violations` using `:file-globs` alone. So in a mixed
change — one in-scope file and one excluded file — the in-scope file admits the
rule and the excluded file is then judged against it. An `:exclude-globs` path
can produce a blocking semantic violation.

This was implemented and then deliberately removed from this PR. Touching that
file causes `stratum-lint --fix` to annotate it for the first time, which
surfaces a pre-existing **SL003: 8 distinct layers (max 3)**. Verified
pre-existing: autofixing the unmodified `origin/main` copy and re-linting it
reports the same 8 layers, so the change adds no layer — it only makes the
existing violation visible. Committing the annotations would leave a file that
fails pre-commit for everyone who touches it next, and splitting a 384-line,
34-def namespace is its own PR, not a rider on this one.

The gap is latent rather than live: `:exclude-globs` has exactly one user today
(`bb-over-shell`), which is `:content-scan`, so it never reaches the semantic
judge. Follow-up order is the namespace split first, then the three sites, in a
PR small enough to review.

## Testing Plan

| Check | Result |
|---|---|
| `policy-pack` + `compliance-scanner` + `semantic-analyzer` + `gate` suites | 596 tests, 3767 assertions, 0 failures |
| `bb lint:clj` | 0 errors, 0 warnings |
| `bb lint:stratum` | clean, no rewrites |
| `bb poly:check` | OK |

New tests:

- `policy-pack/path_scope_test.clj` — a new namespace covering the agreement
  between the three entry points. `path-in-scope?` over a truth table built
  from `bb-over-shell`'s actual shape; exclusion with `:file-globs` absent;
  no-constraint and nil-path cases; `rule-applies-to-artifact?` and
  `rule-applies?` reaching the same answer on the same paths;
  `filter-applicable-rules` (the gate's filter) dropping the rule for an
  excluded path while the other `applies-to` filters still compose.
- `mdc_compiler_test.clj` — `excludeGlobs` compiles to `:exclude-globs` in list,
  inline-array and bare-string form; kept without `globs`; absent (not `[]`)
  when undeclared.
- `scan_test.clj` — end to end through `scan-repo` over a temp git repo holding
  one real automation script, two fixture scripts and one root-level script. The same one-rule pack is
  run **with and without** the carve-out: without it all three are flagged, with
  it only `scripts/deploy.sh`. The control arm is the point — it shows the
  exclusion, not the include globs, is what changed the outcome. A third
  assertion pins `:rules-scanned`, since narrowing files must not disable the
  rule. A fourth runs the same rule with `:file-globs` removed — the shape an
  `alwaysApply` rule carrying only a carve-out compiles to — and asserts the
  root-level script is in scope both with and without the exclusion. Both
  root-path assertions fail against the `["**/*"]` fallback, so they are not
  vacuous.

### End to end against the real standards trees

Compiled both trees and ran the scanner's own `file-pred` plus the gate's
`rule-applies-to-artifact?` over this repo:

| Standards tree | `:exclude-globs` | Glob-matched | Files firing | Gate applies to the 3 `examples/` files |
|---|---|---|---|---|
| `main` (`ee05f44`) | absent | 12 | **3** | yes |
| `main` + miniforge-standards#130 | `["examples/**"]` | 9 | **0** | no |

`scripts/test-since-stable.bb` — real automation — stays in scope in both runs,
so the exclusion did not over-reach. `:std/header-copyright` reports
`auto-fixable-default false` in both, unchanged by this PR.

## Deployment Plan

**The hard constraint is on step 3, not on the order of 1 and 2:** the automated
bump PR must not merge until both feature PRs are in. Consumer-first is the
*preferred* order because it means the bump PR is safe the moment it appears.

1. **This PR merges** (preferred first). Inert on its own — no rule in the pinned pack declares
   `excludeGlobs`, and `components/phase/resources/packs/miniforge-standards.pack.edn`
   (the artifact the runtime actually reads, not a live compile) still carries
   `bb-over-shell` as `{:type :custom}` / `:audit` from the current pin.
2. **miniforge-ai/miniforge-standards#130 merges.** A push to standards `main`
   fires `notify-consumers.yml`, which sends a `standards-updated`
   `repository_dispatch` to this repo. That runs `.github/workflows/bump-standards.yml`,
   which re-pins the submodule to standards `main`, runs `bb standards:pack`,
   and opens an automated PR.
3. **Review and merge that automated bump PR.** Its pack diff is where the gate
   actually arms: `:std/bb-over-shell` flips to `:content-scan` / `:hard-halt`
   / `:high` **and** gains `:exclude-globs ["examples/**"]`, and
   `:std/header-copyright` goes `auto-fixable-default` `true` → `false`.

If #130 merges first nothing breaks on its own: the bump PR arrives carrying a
recompiled pack in which `bb-over-shell` is a live `hard-halt` while nothing in
this repo reads `:exclude-globs`, and it sits there as a review-only PR. The
cost is a coordination window in which merging that PR would block the gate on
the three `examples/` files. Consumer-first closes the window; it is not a
correctness requirement, and #130 does not need to wait on this PR.

### Fan-out beyond this repo

`notify-consumers.yml` dispatches the same event to five other consumers —
`thesium-career`, `thesium`, `thesium-workflows`, `thesium-app-foundation`,
`miniforge-control`. Each gets its own bump PR pinning the standards that make
`bb-over-shell` a live `hard-halt`. Whether that is safe there depends on their
own `.sh`/`.py` inventory and on which compiler they use, neither of which this
PR changes or verifies. Flagged, not addressed.

## Related Issues/PRs

- miniforge-ai/miniforge-standards#130 — the standards half.
- miniforge-ai/miniforge#2023 — makes the compiler warn on nested-block
  frontmatter, which is why `bb-over-shell` was inert at the old pin. No file
  overlap with this PR beyond `mdc_compiler.clj`, in different functions.
- Follow-up: split `semantic-analyzer/core.clj` to the rule 210 layer budget,
  then honour `:exclude-globs` at its three file-selection sites.
- Follow-up: three independent glob implementations decide rule applicability
  (`registry.support/glob-matches?` hand-rolled, plus two `PathMatcher` sites)
  and they disagree on root-level paths — `glob:**/*` does not match a
  single-segment path. Out of scope here; the divergence is pinned by a test
  comment rather than changed.

## Breaking Changes

None. `:exclude-globs` is optional; a rule without it behaves exactly as before.
`rule-applies-to-artifact?` now returns a strict boolean where it previously
returned `some`'s truthy value — both are only ever consumed as predicates.

## Checklist

- [x] `:exclude-globs` on the applicability schema, documented
- [x] Compiler reads `excludeGlobs`, including without `globs`
- [x] One shared `path-in-scope?`, used by both policy-pack entry points
- [x] Scanner `file-pred` excludes at file selection, matchers compiled once
- [x] End-to-end scan test with a control arm (with and without the carve-out)
- [x] Verified against both standards trees: 3 files firing → 0
- [x] Semantic-analyzer gap stated, cause verified pre-existing, follow-up named
- [x] Full suites for every touched component green
