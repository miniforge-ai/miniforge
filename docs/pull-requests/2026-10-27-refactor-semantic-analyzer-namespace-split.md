<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(semantic-analyzer): split core.clj into seven namespaces (rule 210)

**Theme:** standards / rule 210 layer budget
**Type:** pure movement — no behaviour change

MINIFORGE_PR_BUDGET_OVERRIDE: Mechanical namespace split mandated by rule 210.
All 33 definitions move byte-identically (verified by normalized form-by-form
comparison against `origin/main`); the only edits are namespace-alias
qualification, visibility on the definitions that now cross a namespace
boundary, and one local rebinding. core.clj cannot be committed in pieces —
see "Why this is one commit" below.

## Problem

`components/semantic-analyzer/src/ai/miniforge/semantic_analyzer/core.clj` ran
8 distinct call-graph layers against the rule 210 budget of 3. It carried no
`^{:stratum n}` annotations, which is the only reason `bb lint:stratum` passed:
the linter skips files without `Layer N` headings, and core.clj's three headings
were decorative — they did not match the real graph.

So the first edit to the file triggered `stratum-lint --fix`, which annotated it
from the actual reference graph and then reported:

```text
core.clj:363:1: SL003 file uses 8 distinct layers (max 3); split the namespace or extract a component
```

Verified pre-existing, not caused by any pending change — reproduced on a clean
copy from `origin/main`:

```text
=== baseline lint (no fix) ===      exit=0
=== --fix ===                       rewrote core.clj   exit=0
=== post-fix lint ===               SL003 ... 8 distinct layers (max 3)   exit=1
```

This blocked real work. Two changes were implemented and then reverted out of
PR #2025 (`feat/pack-exclude-globs`) solely because of it — honouring
`:rule/applies-to :exclude-globs` at the three file-selection sites, and routing
this component's path scope through the shared matcher. Both land in a follow-up
on top of this split.

## The layer assignment that forced the split

`stratum-lint --fix` on the `origin/main` copy assigned strata 0 through 7:

| Stratum | Definitions |
|---------|-------------|
| 0 | `judge-prompt-resource`, `rule->prompt-bindings`, `strip-code-fences`, `raw->violation`, `limits`, `glob-matcher`, `relativize`, `->rule-kw`, `index-by-rule`, `behavioral-rules` |
| 1 | `judge-templates`, `parse-judge-response`, `judge-response->violations`, `max-file-size-bytes`, `max-files-per-rule`, `file-matches-globs?`, `default-rule-timeout-ms` |
| 2 | `build-judge-prompt`, `under-size-limit?`, `file-under-size-limit?`, `batched-rules-block` |
| 3 | `analyze-file`, `select-files-for-rule`, `build-batched-prompt`, `judgeable-file?` |
| 4 | `analyze-file-in-repo`, `analyze-rule-on-files`, `analyze-file-batched` |
| 5 | `analyze-rule`, `file-rule-violations` |
| 6 | `analyze-rules-on-files`, `analyze-rule-with-timeout` |
| 7 | `analyze-rules-parallel` |

The groups in the split were chosen from that graph, not from the heading
comments, and each resulting file was re-checked with the linter rather than by
hand.

## Changes

Seven new namespaces plus a reduced `core.clj`. Layer counts are the linter's,
after `--fix`:

| Namespace | Layers | What it is |
|-----------|--------|------------|
| `templates` | 2 | The prompt-template resource path and the loaded template map (with the fallback for an absent resource). |
| `prompts` | 2 | Prompt construction: interpolation bindings, the batched rules block, and the single-rule and batched system/user pairs. |
| `parsing` | 2 | Judge response to canonical violations: fence stripping, EDN parsing, per-violation canonicalization, rule-id coercion, by-rule indexing. |
| `limits` | 2 | The operational limits read from `config/semantic-analyzer/limits.edn`: max file size, max files per rule, default per-rule timeout. |
| `selection` | 3 | What gets judged: behavioral-rule filtering, glob matching, path relativization, the size and judgeability predicates, and repo file selection for one rule. |
| `judge` | 2 | One judge call against one file — single-rule, repo-file, and batched. |
| `changed-files` | 2 | The changed-files scope: one rule over the changed files, and the batched one-call-per-file fold. |
| `core` | 3 | The repo-scan scope: one rule over a repo, the same under a timeout, and many rules in parallel batches. |

`interface.clj` keeps exactly the same eight public vars and now forwards to the
new homes. Verified at the REPL:

```text
:interface-vars (analyze-file analyze-rule analyze-rule-on-files
                 analyze-rules-on-files analyze-rules-parallel
                 behavioral-rules build-judge-prompt select-files-for-rule)
:all-ifn? true
```

No consumer changes. `components/gate/src/ai/miniforge/gate/policy_pack.clj`
and `bases/cli/src/ai/miniforge/cli/main/commands/scan.clj` both reach the
component through `.interface` only; both still load.

### `templates` exists so `prompts` fits the budget

Keeping `judge-prompt-resource` and `judge-templates` alongside the four
construction functions gives `prompts` four layers
(`judge-prompt-resource` → `judge-templates` → `batched-rules-block` →
`build-batched-prompt`). Inlining the resource path would have fitted, but
rule 006 names "file paths embedded in code" as a magic literal, so the constant
stays and the resource boundary becomes its own namespace.

### Visibility changes

Sixteen definitions that were `defn-`/`^:private` now cross a namespace boundary
and are plain `defn`/`def`: `judge-templates`, `build-batched-prompt`,
`raw->violation`, `->rule-kw`, `index-by-rule`, `parse-judge-response`,
`judge-response->violations`, the three `limits` values, `relativize`,
`file-under-size-limit?`, `file-matches-globs?`, `judgeable-file?`,
`analyze-file-in-repo`, `analyze-file-batched`. They remain internal to the
component — only `interface.clj` is public, per rule 210's component layout.
Every one of them has a caller in another namespace of this component; none is
exported.

Still private in their new homes: `judge-prompt-resource`,
`rule->prompt-bindings`, `batched-rules-block`, `strip-code-fences`, `limits`,
`glob-matcher`, `under-size-limit?`, `file-rule-violations`,
`analyze-rule-with-timeout`.

### One local rebinding

`build-judge-prompt` and `build-batched-prompt` bound a local named `templates`.
That now shadows the `templates` namespace alias by name, so the local is `tpl`.
Resolution was never ambiguous — a local cannot shadow a qualified symbol — but
a reader should not have to know that.

### One dead binding removed

`core_test.clj` declared `(def under-size-limit? (var-get #'sut/under-size-limit?))`
and never used it. It is not carried into `selection_test.clj`.

## Tests

The six test namespaces mirror the src split. The same 16 tests and 69
assertions as before, none rewritten:

| Namespace | Tests |
|-----------|-------|
| `prompts-test` | `build-judge-prompt-test` |
| `parsing-test` | `strip-code-fences-test`, `parse-judge-response-valid-test`, `parse-judge-response-invalid-test`, `raw->violation-test` |
| `selection-test` | `file-matches-globs-test`, `select-files-for-rule-test`, `behavioral-rules-test` |
| `judge-test` | `analyze-file-violations-test`, `analyze-file-clean-test`, `analyze-file-llm-error-test` |
| `changed-files-test` | `analyze-rule-on-files-test`, `analyze-rules-on-files-batched-test`, `analyze-rules-on-files-untagged-fails-closed-test` |
| `core-test` | `analyze-rule-integration-test`, `analyze-rules-parallel-test` |

`parse-judge-response`, `raw->violation` and `file-matches-globs?` are now
component-internal public vars, so their tests call them directly instead of
through `(var-get #'sut/...)`. `strip-code-fences` is still private and still
reached that way.

## Verification

```text
stratum-lint (8 src files + interface)    exit=0, no findings
clj-kondo (src + test)                    errors: 0, warnings: 0
bb poly:check                             OK
semantic-analyzer suites                  16 tests, 69 assertions, 0 failures, 0 errors
gate suite                                174 tests, 633 assertions, 0 failures, 0 errors
policy-pack + compliance-scanner suites   400 tests, 3032 assertions, 0 failures, 0 errors
gate/policy_pack.clj                      loads
cli/main/commands/scan.clj                loads
```

Movement check: every one of the 33 definitions was extracted from
`origin/main:core.clj` and from the new files with a paren-aware splitter, then
compared after normalizing away the deliberate edits (alias qualification,
`^{:stratum n}` metadata, `defn-` → `defn`, the `tpl` rebinding). Result:
33 definitions on both sides, none missing from either, zero differing bodies.

## Why this is one commit

The repo's 200-line commit budget asks for a stack of small commits, and this
change is 1102 reportable lines. It is still one commit, because no lawful slice
exists:

- `lint:stratum` runs `--fix` over staged files and fails on any remaining
  SL003. core.clj only drops to 3 layers once the last group leaves it. After
  extracting `templates`/`prompts`/`parsing`/`limits` it still carries 6 layers;
  no extraction order gets it under budget earlier.
- Staging core.clj in an earlier commit therefore fails pre-commit unless
  `MINIFORGE_STRATUM_BUDGET_MODE=warn` is set for it — which would wave through
  the exact violation this PR fixes.
- Staging only the new files first would duplicate every definition for a
  commit, leaving the new namespaces unreferenced.
- The src-only and test-only halves are 649 and 453 reportable lines, so even a
  two-way split clears neither budget.

`MINIFORGE_COMMIT_BUDGET_OVERRIDE` and `MINIFORGE_PR_BUDGET_OVERRIDE` both carry
this rationale.

## Follow-up (not in this PR)

On top of this split, in a small PR stacked on #2025:

1. Honour `:rule/applies-to :exclude-globs` at the three file-selection sites —
   `selection/select-files-for-rule`, `changed-files/analyze-rule-on-files`, and
   `changed-files/file-rule-violations`. All three read `:file-globs` directly
   today. The gate path is the sharp one: `gate/policy_pack.clj`'s
   `acting-semantic-rules` admits a rule when any artifact matches, then
   `batched-semantic-analyzer` hands every acting rule and every changed file to
   `analyze-rules-on-files`, which assigns rules per file on `:file-globs`
   alone — so in a mixed change an excluded path can still produce a blocking
   semantic violation.
2. Replace `selection/file-matches-globs?` (and `glob-matcher`) with
   `policy-pack/path-in-scope?`. This component is the last place a rule's path
   scope is decided by a different implementation: `java.nio`'s `PathMatcher`
   reads `**/` as one-or-more segments, while the shared matcher reads it as
   zero-or-more, so `glob:**/*.clj` does not match a root-level `build.clj` and
   `src/**/*.ts` does not match `src/a.ts`. Routing through the shared matcher
   gets the exclusion and the agreement in one move.

`path-in-scope?` and `policy-pack.glob` do not exist on `main` yet — they arrive
with #2025, so the follow-up stacks on that branch rather than this one.
`components/semantic-analyzer/deps.edn` already depends on `policy-pack`, so no
dependency change is needed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
